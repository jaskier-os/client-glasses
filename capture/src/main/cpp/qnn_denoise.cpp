// JNI shim that runs the int8 SplitterNet denoise model on the Hexagon V73 NPU
// (HTP) via the QNN C API directly.
//
// Why this exists: ONNX Runtime's QNN execution provider on this AR1 silicon
// (soc_model 58, Hexagon v73) cannot create the HTP device -- it always uses the
// signed process domain, which the chip rejects with
// QNN_DEVICE_ERROR_INVALID_CONFIG, then silently falls back to CPU. The QNN HTP
// backend's DEFAULT process domain is UNSIGNED (see QnnTFLiteDelegate.h /
// QnnDspBackend.h), so talking to the C API directly with a default (NULL) device
// config gives us the unsigned PD the chip needs. We deliberately do NOT pass a
// custom device config with soc_model / htp_arch -- that is exactly what tripped
// ORT into INVALID_CONFIG.
//
// Model load: QnnContext_createFromBinary can ingest EITHER a serialized context
// .bin OR a raw .dlc (it auto-detects and extracts/prepares as needed). We load
// the .dlc fresh on first run (on-device prepare, ~12s), serialize the resulting
// context to a cache file, and load that cache (~0.6s) on subsequent runs. This
// also sidesteps any 2.27-vs-2.47 binary incompatibility because the context is
// always prepared by the same on-device libs that execute it.

#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

#include "QnnInterface.h"
#include "QnnBackend.h"
#include "QnnContext.h"
#include "QnnDevice.h"
#include "QnnGraph.h"
#include "QnnTensor.h"
#include "QnnTypes.h"
#include "QnnMem.h"
#include "System/QnnSystemInterface.h"
#include "System/QnnSystemContext.h"
#include "HTP/QnnHtpDevice.h"

#define LOG_TAG "Cap:QnnNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

constexpr const char* kGraphName = "splitternet_constpad";
constexpr size_t kTileElems = 256 * 256 * 3;  // [1,256,256,3] f32
constexpr uint32_t kSocModel = 58;            // SSG2125P "AR1 Gen1" -> QNN enum 58

typedef Qnn_ErrorHandle_t (*QnnInterfaceGetProvidersFn_t)(
    const QnnInterface_t***, uint32_t*);
typedef Qnn_ErrorHandle_t (*QnnSystemInterfaceGetProvidersFn_t)(
    const QnnSystemInterface_t***, uint32_t*);

// rpcmem (ION/dma-buf) allocator from libcdsprpc -- HTP requires I/O tensors be
// backed by ION memory registered via QnnMem_register, NOT plain heap pointers
// (a heap clientBuf only partially DMAs back, yielding zeroed output rows).
typedef void* (*RpcMemAllocFn_t)(int heapid, uint32_t flags, int size);
typedef void (*RpcMemFreeFn_t)(void* po);
typedef int (*RpcMemToFdFn_t)(void* po);
constexpr int kRpcmemHeapIdSystem = 25;       // RPCMEM_HEAP_ID_SYSTEM
constexpr uint32_t kRpcmemDefaultFlags = 1;   // RPCMEM_DEFAULT_FLAGS (cached)

struct QnnEngine {
  void* backendLib = nullptr;
  void* systemLib = nullptr;

  QNN_INTERFACE_VER_TYPE qnn{};
  QNN_SYSTEM_INTERFACE_VER_TYPE sys{};

  Qnn_BackendHandle_t backend = nullptr;
  Qnn_DeviceHandle_t device = nullptr;
  Qnn_ContextHandle_t context = nullptr;
  Qnn_GraphHandle_t graph = nullptr;

  // Owned, mutable copies of the graph's input/output tensor descriptors. We
  // deep-copy the dimensions arrays so they outlive the system-context handle.
  std::vector<Qnn_Tensor_t> inputs;
  std::vector<Qnn_Tensor_t> outputs;
  std::vector<std::vector<uint32_t>> dimStore;  // backing storage for dims
  std::string graphName;  // actual graph name from the binary

  bool fromCache = false;

  // ION-backed I/O (HTP shared memory).
  void* cdspLib = nullptr;     // libcdsprpc handle (rpcmem)
  RpcMemAllocFn_t rpcAlloc = nullptr;
  RpcMemFreeFn_t rpcFree = nullptr;
  RpcMemToFdFn_t rpcToFd = nullptr;
  void* inIon = nullptr;       // ION input buffer (kTileElems bytes if uint8)
  void* outIon = nullptr;      // ION output buffer
  Qnn_MemHandle_t inMem = nullptr;   // QnnMem handle for inIon
  Qnn_MemHandle_t outMem = nullptr;  // QnnMem handle for outIon
  bool ionReady = false;       // true => tensors bound to ION mem handles

  // Quantization: this graph's I/O is uint8 (UFIXED_POINT_8). We quantize the
  // float input and dequantize the uint8 output per-tile using these per-tensor
  // scale/offset params. float = (q + offset) * scale.
  bool inQuant = false, outQuant = false;
  float inScale = 1.f, outScale = 1.f;
  int32_t inOffset = 0, outOffset = 0;
  uint32_t inElemBytes = 4, outElemBytes = 4;  // 1 if uint8, 4 if float32

  ~QnnEngine() {
    // Best-effort teardown so error paths that just `delete e` still release the
    // dlopen'd libs and any QNN handles created before the failure.
    if (inMem && qnn.memDeRegister) qnn.memDeRegister(&inMem, 1);
    if (outMem && qnn.memDeRegister) qnn.memDeRegister(&outMem, 1);
    if (inIon && rpcFree) rpcFree(inIon);
    if (outIon && rpcFree) rpcFree(outIon);
    if (context && qnn.contextFree) qnn.contextFree(context, nullptr);
    if (device && qnn.deviceFree) qnn.deviceFree(device);
    if (backend && qnn.backendFree) qnn.backendFree(backend);
    if (cdspLib) dlclose(cdspLib);
    if (systemLib) dlclose(systemLib);
    if (backendLib) dlclose(backendLib);
  }
};

// --- tensor field access (V1/V2 agnostic) -----------------------------------

uint32_t tensorRank(const Qnn_Tensor_t& t) {
  return t.version == QNN_TENSOR_VERSION_2 ? t.v2.rank : t.v1.rank;
}
const uint32_t* tensorDims(const Qnn_Tensor_t& t) {
  return t.version == QNN_TENSOR_VERSION_2 ? t.v2.dimensions : t.v1.dimensions;
}
[[maybe_unused]] const char* tensorName(const Qnn_Tensor_t& t) {
  return t.version == QNN_TENSOR_VERSION_2 ? t.v2.name : t.v1.name;
}

// Deep-copy a descriptor tensor (from system context) into a self-owned tensor
// we can mutate for execution. We keep id/name/type/dataType/quant/dims, force
// memType=RAW and clear the client buffer (filled per-run in runTile).
Qnn_Tensor_t cloneTensorForExec(const Qnn_Tensor_t& src,
                                std::vector<std::vector<uint32_t>>& dimStore) {
  Qnn_Tensor_t dst = src;  // shallow copy of scalar fields + version
  uint32_t rank = tensorRank(src);
  const uint32_t* sdims = tensorDims(src);
  dimStore.emplace_back(sdims, sdims + rank);
  uint32_t* dimsCopy = dimStore.back().data();

  if (dst.version == QNN_TENSOR_VERSION_2) {
    dst.v2.dimensions = dimsCopy;
    dst.v2.memType = QNN_TENSORMEMTYPE_RAW;
    dst.v2.clientBuf.data = nullptr;
    dst.v2.clientBuf.dataSize = 0;
    dst.v2.isDynamicDimensions = nullptr;
  } else {
    dst.version = QNN_TENSOR_VERSION_1;
    dst.v1.dimensions = dimsCopy;
    dst.v1.memType = QNN_TENSORMEMTYPE_RAW;
    dst.v1.clientBuf.data = nullptr;
    dst.v1.clientBuf.dataSize = 0;
  }
  return dst;
}

void setTensorBuf(Qnn_Tensor_t& t, void* data, uint32_t size) {
  if (t.version == QNN_TENSOR_VERSION_2) {
    t.v2.clientBuf.data = data;
    t.v2.clientBuf.dataSize = size;
  } else {
    t.v1.clientBuf.data = data;
    t.v1.clientBuf.dataSize = size;
  }
}

void setTensorMemHandle(Qnn_Tensor_t& t, Qnn_MemHandle_t h) {
  if (t.version == QNN_TENSOR_VERSION_2) {
    t.v2.memType = QNN_TENSORMEMTYPE_MEMHANDLE;
    t.v2.memHandle = h;
  } else {
    t.v1.memType = QNN_TENSORMEMTYPE_MEMHANDLE;
    t.v1.memHandle = h;
  }
}

Qnn_DataType_t tensorDataType(const Qnn_Tensor_t& t) {
  return t.version == QNN_TENSOR_VERSION_2 ? t.v2.dataType : t.v1.dataType;
}

const Qnn_QuantizeParams_t& tensorQuant(const Qnn_Tensor_t& t) {
  return t.version == QNN_TENSOR_VERSION_2 ? t.v2.quantizeParams
                                           : t.v1.quantizeParams;
}

// Extract per-tensor scale/offset. Returns false if not a simple scale-offset
// uint8 quant (caller then treats the tensor as plain float32).
bool getScaleOffset(const Qnn_Tensor_t& t, float* scale, int32_t* offset) {
  const Qnn_QuantizeParams_t& q = tensorQuant(t);
  if (q.quantizationEncoding == QNN_QUANTIZATION_ENCODING_SCALE_OFFSET) {
    *scale = q.scaleOffsetEncoding.scale;
    *offset = q.scaleOffsetEncoding.offset;
    return true;
  }
  return false;
}

// --- file helpers -----------------------------------------------------------

bool readFile(const std::string& path, std::vector<uint8_t>& out) {
  FILE* f = fopen(path.c_str(), "rb");
  if (!f) return false;
  fseek(f, 0, SEEK_END);
  long n = ftell(f);
  fseek(f, 0, SEEK_SET);
  if (n <= 0) { fclose(f); return false; }
  out.resize(static_cast<size_t>(n));
  size_t rd = fread(out.data(), 1, out.size(), f);
  fclose(f);
  return rd == out.size();
}

bool writeFile(const std::string& path, const void* data, size_t size) {
  std::string tmp = path + ".tmp";
  FILE* f = fopen(tmp.c_str(), "wb");
  if (!f) return false;
  size_t wr = fwrite(data, 1, size, f);
  fclose(f);
  if (wr != size) { remove(tmp.c_str()); return false; }
  return rename(tmp.c_str(), path.c_str()) == 0;
}

bool fileExists(const std::string& p) {
  FILE* f = fopen(p.c_str(), "rb");
  if (f) { fclose(f); return true; }
  return false;
}

// --- interface resolution ---------------------------------------------------

bool resolveBackendInterface(void* lib, QNN_INTERFACE_VER_TYPE* out) {
  auto getProviders = reinterpret_cast<QnnInterfaceGetProvidersFn_t>(
      dlsym(lib, "QnnInterface_getProviders"));
  if (!getProviders) {
    LOGE("dlsym QnnInterface_getProviders failed: %s", dlerror());
    return false;
  }
  const QnnInterface_t** providers = nullptr;
  uint32_t num = 0;
  if (getProviders(&providers, &num) != QNN_SUCCESS || num == 0 || !providers) {
    LOGE("QnnInterface_getProviders returned no providers (num=%u)", num);
    return false;
  }
  for (uint32_t i = 0; i < num; ++i) {
    // Match the API version this header set was compiled against.
    if (providers[i]->apiVersion.coreApiVersion.major == QNN_API_VERSION_MAJOR) {
      *out = providers[i]->QNN_INTERFACE_VER_NAME;
      LOGI("QNN backend provider matched coreApi %u.%u.%u",
           providers[i]->apiVersion.coreApiVersion.major,
           providers[i]->apiVersion.coreApiVersion.minor,
           providers[i]->apiVersion.coreApiVersion.patch);
      return true;
    }
  }
  // Fall back to the first provider if no exact major match.
  *out = providers[0]->QNN_INTERFACE_VER_NAME;
  LOGW("QNN no exact-major provider match; using provider[0] coreApi %u.%u",
       providers[0]->apiVersion.coreApiVersion.major,
       providers[0]->apiVersion.coreApiVersion.minor);
  return true;
}

bool resolveSystemInterface(void* lib, QNN_SYSTEM_INTERFACE_VER_TYPE* out) {
  auto getProviders = reinterpret_cast<QnnSystemInterfaceGetProvidersFn_t>(
      dlsym(lib, "QnnSystemInterface_getProviders"));
  if (!getProviders) {
    LOGE("dlsym QnnSystemInterface_getProviders failed: %s", dlerror());
    return false;
  }
  const QnnSystemInterface_t** providers = nullptr;
  uint32_t num = 0;
  if (getProviders(&providers, &num) != QNN_SUCCESS || num == 0 || !providers) {
    LOGE("QnnSystemInterface_getProviders returned no providers");
    return false;
  }
  *out = providers[0]->QNN_SYSTEM_INTERFACE_VER_NAME;
  return true;
}

void logCb(const char* fmt, QnnLog_Level_t level, uint64_t, va_list argp) {
  if (!fmt) return;
  char buf[1024];
  vsnprintf(buf, sizeof(buf), fmt, argp);
  int prio = ANDROID_LOG_INFO;
  if (level == QNN_LOG_LEVEL_ERROR) prio = ANDROID_LOG_ERROR;
  else if (level == QNN_LOG_LEVEL_WARN) prio = ANDROID_LOG_WARN;
  __android_log_print(prio, "Cap:QnnLog", "%s", buf);
}

// Pull the graph's input/output tensor descriptors out of the serialized binary
// (works for both a context .bin and an unprepared .dlc). We need these to bind
// I/O for execute; QnnGraph_retrieve alone does not hand back tensor specs.
bool extractGraphIO(QnnEngine* e, const std::vector<uint8_t>& binary) {
  QnnSystemContext_Handle_t sysCtx = nullptr;
  if (e->sys.systemContextCreate(&sysCtx) != QNN_SUCCESS || !sysCtx) {
    LOGE("QnnSystemContext_create failed");
    return false;
  }
  const QnnSystemContext_BinaryInfo_t* info = nullptr;
  Qnn_ContextBinarySize_t infoSize = 0;
  Qnn_ErrorHandle_t err = e->sys.systemContextGetBinaryInfo(
      sysCtx, const_cast<void*>(static_cast<const void*>(binary.data())),
      binary.size(), &info, &infoSize);
  if (err != QNN_SUCCESS || !info) {
    LOGE("QnnSystemContext_getBinaryInfo failed err=0x%llx",
         (unsigned long long)err);
    e->sys.systemContextFree(sysCtx);
    return false;
  }

  uint32_t numGraphs = 0;
  const QnnSystemContext_GraphInfo_t* graphs = nullptr;
  if (info->version == QNN_SYSTEM_CONTEXT_BINARY_INFO_VERSION_1) {
    numGraphs = info->contextBinaryInfoV1.numGraphs;
    graphs = info->contextBinaryInfoV1.graphs;
  } else if (info->version == QNN_SYSTEM_CONTEXT_BINARY_INFO_VERSION_2) {
    numGraphs = info->contextBinaryInfoV2.numGraphs;
    graphs = info->contextBinaryInfoV2.graphs;
  } else if (info->version == QNN_SYSTEM_CONTEXT_BINARY_INFO_VERSION_3) {
    numGraphs = info->contextBinaryInfoV3.numGraphs;
    graphs = info->contextBinaryInfoV3.graphs;
  } else {
    LOGE("Unknown binary info version %d", info->version);
    e->sys.systemContextFree(sysCtx);
    return false;
  }

  bool found = false;
  for (uint32_t g = 0; g < numGraphs && !found; ++g) {
    const QnnSystemContext_GraphInfo_t& gi = graphs[g];
    const char* gname = nullptr;
    uint32_t numIn = 0, numOut = 0;
    const Qnn_Tensor_t *ins = nullptr, *outs = nullptr;
    if (gi.version == QNN_SYSTEM_CONTEXT_GRAPH_INFO_VERSION_1) {
      gname = gi.graphInfoV1.graphName;
      numIn = gi.graphInfoV1.numGraphInputs;
      numOut = gi.graphInfoV1.numGraphOutputs;
      ins = gi.graphInfoV1.graphInputs;
      outs = gi.graphInfoV1.graphOutputs;
    } else if (gi.version == QNN_SYSTEM_CONTEXT_GRAPH_INFO_VERSION_2) {
      gname = gi.graphInfoV2.graphName;
      numIn = gi.graphInfoV2.numGraphInputs;
      numOut = gi.graphInfoV2.numGraphOutputs;
      ins = gi.graphInfoV2.graphInputs;
      outs = gi.graphInfoV2.graphOutputs;
    } else {
      gname = gi.graphInfoV3.graphName;
      numIn = gi.graphInfoV3.numGraphInputs;
      numOut = gi.graphInfoV3.numGraphOutputs;
      ins = gi.graphInfoV3.graphInputs;
      outs = gi.graphInfoV3.graphOutputs;
    }
    LOGI("binary graph[%u] name=%s in=%u out=%u", g, gname ? gname : "?",
         numIn, numOut);
    // Accept the target graph by name, or the sole graph if there is only one.
    // The actual graph name in the binary may differ (e.g. "splitternet_cp" in
    // the device-prepared .bin vs "splitternet_constpad" in the DLC), so capture
    // whatever name we matched and use it for QnnGraph_retrieve.
    bool match = (gname && strcmp(gname, kGraphName) == 0) || numGraphs == 1;
    if (!match) continue;

    e->graphName = gname ? gname : kGraphName;
    e->dimStore.reserve(numIn + numOut);
    for (uint32_t i = 0; i < numIn; ++i)
      e->inputs.push_back(cloneTensorForExec(ins[i], e->dimStore));
    for (uint32_t i = 0; i < numOut; ++i)
      e->outputs.push_back(cloneTensorForExec(outs[i], e->dimStore));
    found = true;
  }

  e->sys.systemContextFree(sysCtx);
  if (!found) {
    LOGE("graph %s not found in binary", kGraphName);
    return false;
  }
  LOGI("extracted graph IO: %zu inputs, %zu outputs", e->inputs.size(),
       e->outputs.size());
  for (size_t i = 0; i < e->inputs.size(); ++i) {
    uint32_t r = tensorRank(e->inputs[i]);
    const uint32_t* d = tensorDims(e->inputs[i]);
    char b[128]; int o = 0;
    for (uint32_t k = 0; k < r && o < 110; ++k) o += snprintf(b+o, sizeof(b)-o, "%u,", d[k]);
    LOGI("  input[%zu] rank=%u dims=[%s]", i, r, b);
  }
  for (size_t i = 0; i < e->outputs.size(); ++i) {
    uint32_t r = tensorRank(e->outputs[i]);
    const uint32_t* d = tensorDims(e->outputs[i]);
    char b[128]; int o = 0;
    for (uint32_t k = 0; k < r && o < 110; ++k) o += snprintf(b+o, sizeof(b)-o, "%u,", d[k]);
    LOGI("  output[%zu] rank=%u dims=[%s] dtype=0x%x", i, r, b,
         (unsigned)tensorDataType(e->outputs[i]));
  }
  LOGI("  input[0] dtype=0x%x", (unsigned)tensorDataType(e->inputs[0]));
  return true;
}

// Allocate ION-backed input/output buffers, register them with the context, and
// bind the graph's I/O tensors to those memory handles. Without this the HTP
// only partially DMAs a plain-heap clientBuf back (zeroed output rows). Returns
// true on success; on failure the caller can fall back to clientBuf binding.
bool setupIonIo(QnnEngine* e) {
  if (!e->rpcAlloc || !e->rpcToFd || !e->qnn.memRegister) {
    LOGW("rpcmem/memRegister unavailable; ION I/O disabled");
    return false;
  }

  // Determine per-element byte size + quantization from the actual tensor dtype.
  Qnn_DataType_t inDt = tensorDataType(e->inputs[0]);
  Qnn_DataType_t outDt = tensorDataType(e->outputs[0]);
  e->inElemBytes = (inDt == QNN_DATATYPE_UFIXED_POINT_8 ||
                    inDt == QNN_DATATYPE_SFIXED_POINT_8 ||
                    inDt == QNN_DATATYPE_UINT_8) ? 1 : 4;
  e->outElemBytes = (outDt == QNN_DATATYPE_UFIXED_POINT_8 ||
                     outDt == QNN_DATATYPE_SFIXED_POINT_8 ||
                     outDt == QNN_DATATYPE_UINT_8) ? 1 : 4;
  e->inQuant = (e->inElemBytes == 1) &&
               getScaleOffset(e->inputs[0], &e->inScale, &e->inOffset);
  e->outQuant = (e->outElemBytes == 1) &&
                getScaleOffset(e->outputs[0], &e->outScale, &e->outOffset);
  LOGI("quant in: bytes=%u quant=%d scale=%.6g offset=%d | out: bytes=%u quant=%d "
       "scale=%.6g offset=%d", e->inElemBytes, e->inQuant, e->inScale, e->inOffset,
       e->outElemBytes, e->outQuant, e->outScale, e->outOffset);

  const int inBytes = (int)(kTileElems * e->inElemBytes);
  const int outBytes = (int)(kTileElems * e->outElemBytes);
  e->inIon = e->rpcAlloc(kRpcmemHeapIdSystem, kRpcmemDefaultFlags, inBytes);
  e->outIon = e->rpcAlloc(kRpcmemHeapIdSystem, kRpcmemDefaultFlags, outBytes);
  if (!e->inIon || !e->outIon) {
    LOGE("rpcmem_alloc failed (in=%p out=%p)", e->inIon, e->outIon);
    return false;
  }
  int inFd = e->rpcToFd(e->inIon);
  int outFd = e->rpcToFd(e->outIon);
  if (inFd <= 0 || outFd <= 0) {
    LOGE("rpcmem_to_fd failed (in=%d out=%d)", inFd, outFd);
    return false;
  }

  // Build mem descriptors matching the I/O tensors (dims + dataType).
  auto makeDesc = [](const Qnn_Tensor_t& t, int fd, std::vector<uint32_t>& dimBuf,
                     Qnn_MemDescriptor_t& d) {
    uint32_t rank = tensorRank(t);
    const uint32_t* dims = tensorDims(t);
    dimBuf.assign(dims, dims + rank);
    memset(&d, 0, sizeof(d));
    d.memShape.numDim = rank;
    d.memShape.dimSize = dimBuf.data();
    d.memShape.shapeConfig = nullptr;
    d.dataType = tensorDataType(t);
    d.memType = QNN_MEM_TYPE_ION;
    d.ionInfo.fd = fd;
  };

  std::vector<uint32_t> inDims, outDims;
  Qnn_MemDescriptor_t inDesc, outDesc;
  makeDesc(e->inputs[0], inFd, inDims, inDesc);
  makeDesc(e->outputs[0], outFd, outDims, outDesc);

  Qnn_ErrorHandle_t er = e->qnn.memRegister(e->context, &inDesc, 1, &e->inMem);
  if (er != QNN_SUCCESS || !e->inMem) {
    LOGE("QnnMem_register(input) failed err=0x%llx", (unsigned long long)er);
    return false;
  }
  er = e->qnn.memRegister(e->context, &outDesc, 1, &e->outMem);
  if (er != QNN_SUCCESS || !e->outMem) {
    LOGE("QnnMem_register(output) failed err=0x%llx", (unsigned long long)er);
    return false;
  }

  setTensorMemHandle(e->inputs[0], e->inMem);
  setTensorMemHandle(e->outputs[0], e->outMem);
  e->ionReady = true;
  LOGI("ION I/O ready (in fd=%d out fd=%d, in=%d out=%d bytes)", inFd, outFd,
       inBytes, outBytes);
  return true;
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_repository_glasses_capture_QnnNative_nativeInit(
    JNIEnv* env, jclass, jstring jBackendDir, jstring jModelPath,
    jstring jCacheDir, jstring jPrebuiltCtxPath) {
  const char* backendDir = env->GetStringUTFChars(jBackendDir, nullptr);
  const char* modelPath = env->GetStringUTFChars(jModelPath, nullptr);
  const char* cacheDir = env->GetStringUTFChars(jCacheDir, nullptr);
  const char* prebuilt = jPrebuiltCtxPath
                             ? env->GetStringUTFChars(jPrebuiltCtxPath, nullptr)
                             : "";
  std::string backendDirS(backendDir), modelPathS(modelPath), cacheDirS(cacheDir);
  std::string prebuiltS(prebuilt);
  env->ReleaseStringUTFChars(jBackendDir, backendDir);
  env->ReleaseStringUTFChars(jModelPath, modelPath);
  env->ReleaseStringUTFChars(jCacheDir, cacheDir);
  if (jPrebuiltCtxPath) env->ReleaseStringUTFChars(jPrebuiltCtxPath, prebuilt);

  // fastRPC reads libQnnHtpV73Skel.so from ADSP_LIBRARY_PATH (apps-side file I/O).
  // The Qualcomm fastRPC loader splits this var on ';' (NOT ':' -- see
  // apps_std_get_search_paths_with_env(ADSP_LIBRARY_PATH, ";", ...) in
  // libcdsprpc.so). Using ':' merges everything into one bogus path and the skel
  // is never found, so the unsigned-PD session open fails (qnn_open 0x80000406).
  std::string adsp = backendDirS + ";/vendor/dsp/cdsp;/vendor/lib/rfsa/adsp";
  setenv("ADSP_LIBRARY_PATH", adsp.c_str(), 1);

  // The HTP stub (libQnnHtpV73Stub.so) dlopens libcdsprpc.so (the Qualcomm CDSP
  // fastRPC client) transitively. It is a public vendor lib, but the QNN backend
  // libs are loaded into a separate classloader-namespace that does NOT expose
  // /vendor/lib64, so the stub's load fails with "library libcdsprpc.so not
  // found" -> err 4000 -> device-create INVALID_CONFIG (0x36b1). Pre-loading it
  // here from OUR (default app) namespace puts it in the process so the stub's
  // dependency resolves against the already-loaded copy.
  // Bare name only: the <uses-native-library libcdsprpc.so> manifest grant lets
  // the app namespace resolve this public vendor lib by name. An absolute
  // /vendor/lib64 path is rejected by the namespace permitted_paths.
  void* cdsp = dlopen("libcdsprpc.so", RTLD_NOW | RTLD_GLOBAL);
  if (cdsp) {
    LOGI("pre-loaded libcdsprpc.so (RTLD_GLOBAL) for HTP fastRPC stub");
  } else {
    LOGW("failed to pre-load libcdsprpc.so: %s (HTP may fail to reach DSP)",
         dlerror());
  }

  auto* e = new QnnEngine();

  // Hold the cdsprpc handle on the engine and resolve rpcmem for ION I/O buffers.
  e->cdspLib = cdsp;
  if (cdsp) {
    e->rpcAlloc = reinterpret_cast<RpcMemAllocFn_t>(dlsym(cdsp, "rpcmem_alloc"));
    e->rpcFree = reinterpret_cast<RpcMemFreeFn_t>(dlsym(cdsp, "rpcmem_free"));
    e->rpcToFd = reinterpret_cast<RpcMemToFdFn_t>(dlsym(cdsp, "rpcmem_to_fd"));
  }

  std::string htpPath = backendDirS + "/libQnnHtp.so";
  std::string sysPath = backendDirS + "/libQnnSystem.so";

  e->backendLib = dlopen(htpPath.c_str(), RTLD_NOW | RTLD_LOCAL);
  if (!e->backendLib) {
    LOGE("dlopen %s failed: %s", htpPath.c_str(), dlerror());
    delete e;
    return 0;
  }
  e->systemLib = dlopen(sysPath.c_str(), RTLD_NOW | RTLD_LOCAL);
  if (!e->systemLib) {
    LOGE("dlopen %s failed: %s", sysPath.c_str(), dlerror());
    dlclose(e->backendLib);
    delete e;
    return 0;
  }

  if (!resolveBackendInterface(e->backendLib, &e->qnn) ||
      !resolveSystemInterface(e->systemLib, &e->sys)) {
    delete e;  // dlclose handled in nativeClose only after success; do it here
    return 0;
  }

  Qnn_LogHandle_t logHandle = nullptr;
  Qnn_ErrorHandle_t lerr = e->qnn.logCreate(logCb, QNN_LOG_LEVEL_WARN, &logHandle);
  if (lerr != QNN_SUCCESS || !logHandle) {
    LOGW("QnnLog_create failed err=0x%llx (continuing without log handle)",
         (unsigned long long)lerr);
    logHandle = nullptr;
  }

  // Backend.
  if (e->qnn.backendCreate(logHandle, nullptr, &e->backend) != QNN_SUCCESS ||
      !e->backend) {
    LOGE("QnnBackend_create failed");
    delete e;
    return 0;
  }

  // Device: create on the UNSIGNED process domain. This AR1 silicon REQUIRES the
  // HTP device be created with useSignedProcessDomain=false (matches the working
  // qnn-net-run config pd_session=unsigned); the signed PD that ORT-QNN forced is
  // rejected with QNN_DEVICE_ERROR_INVALID_CONFIG. We also pin soc_model 58 and
  // arch v73 so the prepare/deserialize targets the real silicon (and queries the
  // real hardware VTCM, avoiding the 4MB-request rejection, err 0x138d).
  QnnHtpDevice_CustomConfig_t htpSoc;
  memset(&htpSoc, 0, sizeof(htpSoc));
  htpSoc.option = QNN_HTP_DEVICE_CONFIG_OPTION_SOC;
  htpSoc.socModel = kSocModel;

  QnnHtpDevice_CustomConfig_t htpArch;
  memset(&htpArch, 0, sizeof(htpArch));
  htpArch.option = QNN_HTP_DEVICE_CONFIG_OPTION_ARCH;
  htpArch.arch.deviceId = 0;
  htpArch.arch.arch = QNN_HTP_DEVICE_ARCH_V73;

  QnnHtpDevice_CustomConfig_t htpPd;
  memset(&htpPd, 0, sizeof(htpPd));
  htpPd.option = QNN_HTP_DEVICE_CONFIG_OPTION_SIGNEDPD;
  htpPd.useSignedProcessDomain.deviceId = 0;
  htpPd.useSignedProcessDomain.useSignedProcessDomain = false;  // UNSIGNED PD

  QnnDevice_Config_t cfgSoc;
  memset(&cfgSoc, 0, sizeof(cfgSoc));
  cfgSoc.option = QNN_DEVICE_CONFIG_OPTION_CUSTOM;
  cfgSoc.customConfig = &htpSoc;

  QnnDevice_Config_t cfgArch;
  memset(&cfgArch, 0, sizeof(cfgArch));
  cfgArch.option = QNN_DEVICE_CONFIG_OPTION_CUSTOM;
  cfgArch.customConfig = &htpArch;

  QnnDevice_Config_t cfgPd;
  memset(&cfgPd, 0, sizeof(cfgPd));
  cfgPd.option = QNN_DEVICE_CONFIG_OPTION_CUSTOM;
  cfgPd.customConfig = &htpPd;

  const QnnDevice_Config_t* devCfgs[] = {&cfgSoc, &cfgArch, &cfgPd, nullptr};

  Qnn_ErrorHandle_t derr = e->qnn.deviceCreate(logHandle, devCfgs, &e->device);
  if (derr != QNN_SUCCESS) {
    LOGE("QnnDevice_create (unsigned PD) failed err=0x%llx; retrying PD-only",
         (unsigned long long)derr);
    // Retry with just the unsigned-PD knob (some builds reject soc/arch custom
    // config alongside the binary's own platform info).
    const QnnDevice_Config_t* pdOnly[] = {&cfgPd, nullptr};
    derr = e->qnn.deviceCreate(logHandle, pdOnly, &e->device);
    if (derr != QNN_SUCCESS) {
      LOGE("QnnDevice_create (PD-only) failed err=0x%llx (continuing NULL device)",
           (unsigned long long)derr);
      e->device = nullptr;
    }
  }
  if (e->device) LOGI("QnnDevice_create OK (unsigned PD)");

  // Decide model source: prefer the prepared cache if present, else the raw DLC.
  // NOTE: QnnSystemContext_getBinaryInfo can ONLY parse a serialized CONTEXT
  // binary -- it CANNOT parse a raw .dlc (that path needs libQnnModelDlc.so).
  // QnnContext_createFromBinary, however, DOES auto-detect and prepare a .dlc.
  // So the order is: create the context first, then obtain a serialized context
  // binary (the cache bytes directly, or by serializing the freshly prepared
  // context), and extract graph I/O from THAT.
  // Source preference:
  //   1. writable cache (.qnnctx) -- a context we serialized on a previous run.
  //   2. prebuilt context binary asset (.bin) -- prepared on this exact SoC at
  //      build/stage time, so it already requests the real hardware VTCM. This
  //      avoids the on-device DLC prepare requesting 4MB VTCM the chip rejects
  //      (err 0x138d). This is the fast, reliable path.
  //   3. raw DLC -- on-device prepare fallback (slow, may hit the VTCM request
  //      issue, but kept so the app is not hard-bound to the prebuilt .bin).
  // A loaded context binary (1 or 2) is treated as "fromCache" (no re-serialize).
  std::string cachePath = cacheDirS + "/splitternet_htp_v73.qnnctx";
  std::vector<uint8_t> modelBin;
  bool isContextBin = false;  // true => modelBin is a context binary, not a DLC
  if (fileExists(cachePath) && readFile(cachePath, modelBin) && !modelBin.empty()) {
    e->fromCache = true;
    isContextBin = true;
    LOGI("loading prepared context cache (%zu bytes)", modelBin.size());
  } else if (!prebuiltS.empty() && fileExists(prebuiltS) &&
             readFile(prebuiltS, modelBin) && !modelBin.empty()) {
    e->fromCache = true;
    isContextBin = true;
    LOGI("loading prebuilt context binary %s (%zu bytes)", prebuiltS.c_str(),
         modelBin.size());
  } else {
    if (!readFile(modelPathS, modelBin) || modelBin.empty()) {
      LOGE("failed to read DLC %s", modelPathS.c_str());
      delete e;
      return 0;
    }
    LOGI("preparing from DLC fresh (%zu bytes)", modelBin.size());
  }
  bool haveCache = isContextBin;

  // Create the context from the binary (DLC or cached context, auto-detected).
  Qnn_ContextHandle_t ctx = nullptr;
  Qnn_ErrorHandle_t cerr = e->qnn.contextCreateFromBinary(
      e->backend, e->device, nullptr, modelBin.data(), modelBin.size(), &ctx,
      nullptr);
  if (cerr != QNN_SUCCESS || !ctx) {
    LOGE("QnnContext_createFromBinary failed err=0x%llx", (unsigned long long)cerr);
    delete e;
    return 0;
  }
  e->context = ctx;

  // Obtain a serialized CONTEXT binary for graph-IO extraction. On the cache
  // path the cache file already IS a context binary. On the prepare path we
  // serialize the freshly prepared context (and persist it as the cache).
  std::vector<uint8_t> ctxBin;
  if (haveCache) {
    ctxBin = modelBin;
  } else {
    Qnn_ContextBinarySize_t binSize = 0;
    if (e->qnn.contextGetBinarySize(e->context, &binSize) != QNN_SUCCESS ||
        binSize == 0) {
      LOGE("QnnContext_getBinarySize failed/zero");
      delete e;
      return 0;
    }
    ctxBin.resize(binSize);
    Qnn_ContextBinarySize_t written = 0;
    if (e->qnn.contextGetBinary(e->context, ctxBin.data(), binSize, &written) !=
            QNN_SUCCESS ||
        written == 0) {
      LOGE("QnnContext_getBinary failed");
      delete e;
      return 0;
    }
    ctxBin.resize(written);
    if (writeFile(cachePath, ctxBin.data(), written)) {
      LOGI("wrote prepared context cache %s (%llu bytes)", cachePath.c_str(),
           (unsigned long long)written);
    } else {
      LOGW("failed to write context cache %s", cachePath.c_str());
    }
  }

  // Extract graph I/O tensor descriptors from the serialized context binary.
  if (!extractGraphIO(e, ctxBin)) {
    delete e;
    return 0;
  }

  // Retrieve the graph handle by its actual name (from the binary info).
  const char* gname = e->graphName.empty() ? kGraphName : e->graphName.c_str();
  Qnn_ErrorHandle_t gerr = e->qnn.graphRetrieve(e->context, gname, &e->graph);
  if (gerr != QNN_SUCCESS || !e->graph) {
    LOGE("QnnGraph_retrieve(%s) failed err=0x%llx", gname,
         (unsigned long long)gerr);
    delete e;
    return 0;
  }

  // Bind ION-backed shared memory for I/O (required for full HTP DMA). If this
  // fails we fall back to clientBuf binding in runTile (partial output risk, but
  // the warmup guard will then reject the engine and the app uses CPU).
  if (!setupIonIo(e)) {
    LOGW("ION I/O setup failed; runTile will use clientBuf (HTP output may be partial)");
  }

  LOGI("nativeInit OK (fromCache=%d) graph=%s ion=%d", e->fromCache ? 1 : 0, gname,
       e->ionReady ? 1 : 0);
  return reinterpret_cast<jlong>(e);
}

JNIEXPORT jboolean JNICALL
Java_com_repository_glasses_capture_QnnNative_nativeRunTile(
    JNIEnv* env, jclass, jlong handle, jfloatArray jin, jfloatArray jout) {
  auto* e = reinterpret_cast<QnnEngine*>(handle);
  if (!e || e->inputs.empty() || e->outputs.empty()) return JNI_FALSE;

  jsize inLen = env->GetArrayLength(jin);
  jsize outLen = env->GetArrayLength(jout);
  if (inLen < (jsize)kTileElems || outLen < (jsize)kTileElems) {
    LOGE("runTile bad lengths in=%d out=%d", inLen, outLen);
    return JNI_FALSE;
  }

  bool ok = false;
  if (e->ionReady) {
    // ION path. The graph I/O is uint8-quantized, so quantize the float input
    // into the shared uint8 buffer, execute (HTP reads/writes registered ION
    // memory directly), then dequantize the uint8 output back to float.
    {
      jfloat* jinPtr = env->GetFloatArrayElements(jin, nullptr);
      if (e->inQuant) {
        uint8_t* q = reinterpret_cast<uint8_t*>(e->inIon);
        const float invScale = 1.0f / e->inScale;
        for (size_t i = 0; i < kTileElems; ++i) {
          int v = (int)lrintf(jinPtr[i] * invScale) - e->inOffset;
          if (v < 0) v = 0; else if (v > 255) v = 255;
          q[i] = (uint8_t)v;
        }
      } else {
        memcpy(e->inIon, jinPtr, kTileElems * sizeof(float));
      }
      env->ReleaseFloatArrayElements(jin, jinPtr, JNI_ABORT);
    }

    Qnn_ErrorHandle_t err = e->qnn.graphExecute(
        e->graph, e->inputs.data(), (uint32_t)e->inputs.size(),
        e->outputs.data(), (uint32_t)e->outputs.size(), nullptr, nullptr);
    ok = (err == QNN_SUCCESS);
    if (!ok) {
      LOGE("QnnGraph_execute (ion) failed err=0x%llx", (unsigned long long)err);
    } else {
      jfloat* joutPtr = env->GetFloatArrayElements(jout, nullptr);
      if (e->outQuant) {
        const uint8_t* q = reinterpret_cast<const uint8_t*>(e->outIon);
        for (size_t i = 0; i < kTileElems; ++i)
          joutPtr[i] = ((int)q[i] + e->outOffset) * e->outScale;
      } else {
        memcpy(joutPtr, e->outIon, kTileElems * sizeof(float));
      }
      env->ReleaseFloatArrayElements(jout, joutPtr, 0);  // copy back
    }
  } else {
    // Fallback: plain heap clientBuf (may yield partial HTP output).
    float* inBuf = env->GetFloatArrayElements(jin, nullptr);
    float* outBuf = env->GetFloatArrayElements(jout, nullptr);
    setTensorBuf(e->inputs[0], inBuf, (uint32_t)(kTileElems * sizeof(float)));
    setTensorBuf(e->outputs[0], outBuf, (uint32_t)(kTileElems * sizeof(float)));
    Qnn_ErrorHandle_t err = e->qnn.graphExecute(
        e->graph, e->inputs.data(), (uint32_t)e->inputs.size(),
        e->outputs.data(), (uint32_t)e->outputs.size(), nullptr, nullptr);
    ok = (err == QNN_SUCCESS);
    if (!ok) LOGE("QnnGraph_execute failed err=0x%llx", (unsigned long long)err);
    env->ReleaseFloatArrayElements(jin, inBuf, JNI_ABORT);
    env->ReleaseFloatArrayElements(jout, outBuf, ok ? 0 : JNI_ABORT);
  }
  return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_repository_glasses_capture_QnnNative_nativeFromCache(
    JNIEnv*, jclass, jlong handle) {
  auto* e = reinterpret_cast<QnnEngine*>(handle);
  return (e && e->fromCache) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_repository_glasses_capture_QnnNative_nativeClose(
    JNIEnv*, jclass, jlong handle) {
  auto* e = reinterpret_cast<QnnEngine*>(handle);
  if (!e) return;
  delete e;  // ~QnnEngine releases context/device/backend + dlclose's the libs
}

}  // extern "C"
