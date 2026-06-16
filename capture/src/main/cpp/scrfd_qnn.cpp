// JNI shim that runs the int8 SCRFD-10G face detector on the Hexagon V73 NPU
// (HTP) via the QNN C API directly. This mirrors capture/.../qnn_denoise.cpp but
// for a multi-output (9 output tensors) detection graph, and does the SCRFD
// letterbox preprocessing + stride-8/16/32 decode + NMS in C++ so the Kotlin
// side just hands over an RGBA bitmap and gets face boxes back.
//
// See capture/src/main/cpp/qnn_denoise.cpp for the rationale on:
//   - why we talk to the raw QNN C API (ORT-QNN forces a signed PD this AR1
//     silicon rejects; unsigned PD is the QNN HTP default),
//   - ADSP_LIBRARY_PATH must be SEMICOLON-separated,
//   - libcdsprpc.so must be pre-loaded RTLD_GLOBAL,
//   - on-device context-binary prepare/cache (x86 bake requests 4MB VTCM the
//     chip rejects, err 0x138d),
//   - ION-backed I/O (heap clientBuf only partially DMAs back -> zeroed rows).
//
// SCRFD recipe (validated, /media/varingait/Lobotomite/scrfd_qnn/RECIPE.md):
//   input 1x3x640x640 NCHW float; preprocess = letterbox to 640 + (px-127.5)/128,
//   RGB. Outputs (onnx names) scores 448/471/494, bbox 451/474/497, kps
//   454/477/500 for strides 8/16/32; 2 anchors/location; score thr 0.5, NMS 0.4.

#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <dlfcn.h>
#include <algorithm>
#include <cmath>
#include <ctime>
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

#define LOG_TAG "App:ScrfdQnn"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

constexpr int kInputSize = 640;
constexpr int kInputElems = 3 * 640 * 640;  // NCHW float
constexpr uint32_t kSocModel = 58;           // SSG2125P "AR1 Gen1" -> QNN enum 58
constexpr float kScoreThr = 0.5f;
constexpr float kNmsThr = 0.4f;

// Strides + per-stride decode metadata (matches scrfd_util.py).
struct StrideInfo { int stride; int count; };
const StrideInfo kStrides[3] = {{8, 12800}, {16, 3200}, {32, 800}};

typedef Qnn_ErrorHandle_t (*QnnInterfaceGetProvidersFn_t)(
    const QnnInterface_t***, uint32_t*);
typedef Qnn_ErrorHandle_t (*QnnSystemInterfaceGetProvidersFn_t)(
    const QnnSystemInterface_t***, uint32_t*);

typedef void* (*RpcMemAllocFn_t)(int heapid, uint32_t flags, int size);
typedef void (*RpcMemFreeFn_t)(void* po);
typedef int (*RpcMemToFdFn_t)(void* po);
constexpr int kRpcmemHeapIdSystem = 25;
constexpr uint32_t kRpcmemDefaultFlags = 1;

// Per-tensor I/O metadata + ION buffer.
struct IoTensor {
  std::string onnxName;       // tensor name from the binary
  uint32_t elems = 0;         // element count (product of dims)
  uint32_t elemBytes = 4;     // 1 if int8/uint8, 4 if float32
  bool quant = false;
  float scale = 1.f;
  int32_t offset = 0;
  bool isSigned = false;      // SFIXED vs UFIXED
  void* ion = nullptr;
  int fd = -1;
  Qnn_MemHandle_t mem = nullptr;
};

struct ScrfdEngine {
  void* backendLib = nullptr;
  void* systemLib = nullptr;
  QNN_INTERFACE_VER_TYPE qnn{};
  QNN_SYSTEM_INTERFACE_VER_TYPE sys{};

  Qnn_BackendHandle_t backend = nullptr;
  Qnn_DeviceHandle_t device = nullptr;
  Qnn_ContextHandle_t context = nullptr;
  Qnn_GraphHandle_t graph = nullptr;

  std::vector<Qnn_Tensor_t> inputs;
  std::vector<Qnn_Tensor_t> outputs;
  std::vector<std::vector<uint32_t>> dimStore;
  std::string graphName;
  bool fromCache = false;

  // I/O metadata aligned 1:1 with inputs/outputs.
  std::vector<IoTensor> inMeta;
  std::vector<IoTensor> outMeta;

  void* cdspLib = nullptr;
  RpcMemAllocFn_t rpcAlloc = nullptr;
  RpcMemFreeFn_t rpcFree = nullptr;
  RpcMemToFdFn_t rpcToFd = nullptr;
  bool ionReady = false;

  ~ScrfdEngine() {
    for (auto& m : inMeta) {
      if (m.mem && qnn.memDeRegister) qnn.memDeRegister(&m.mem, 1);
      if (m.ion && rpcFree) rpcFree(m.ion);
    }
    for (auto& m : outMeta) {
      if (m.mem && qnn.memDeRegister) qnn.memDeRegister(&m.mem, 1);
      if (m.ion && rpcFree) rpcFree(m.ion);
    }
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
const char* tensorName(const Qnn_Tensor_t& t) {
  return t.version == QNN_TENSOR_VERSION_2 ? t.v2.name : t.v1.name;
}
Qnn_DataType_t tensorDataType(const Qnn_Tensor_t& t) {
  return t.version == QNN_TENSOR_VERSION_2 ? t.v2.dataType : t.v1.dataType;
}
const Qnn_QuantizeParams_t& tensorQuant(const Qnn_Tensor_t& t) {
  return t.version == QNN_TENSOR_VERSION_2 ? t.v2.quantizeParams
                                           : t.v1.quantizeParams;
}

Qnn_Tensor_t cloneTensorForExec(const Qnn_Tensor_t& src,
                                std::vector<std::vector<uint32_t>>& dimStore) {
  Qnn_Tensor_t dst = src;
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

void setTensorMemHandle(Qnn_Tensor_t& t, Qnn_MemHandle_t h) {
  if (t.version == QNN_TENSOR_VERSION_2) {
    t.v2.memType = QNN_TENSORMEMTYPE_MEMHANDLE;
    t.v2.memHandle = h;
  } else {
    t.v1.memType = QNN_TENSORMEMTYPE_MEMHANDLE;
    t.v1.memHandle = h;
  }
}

bool getScaleOffset(const Qnn_Tensor_t& t, float* scale, int32_t* offset) {
  const Qnn_QuantizeParams_t& q = tensorQuant(t);
  if (q.quantizationEncoding == QNN_QUANTIZATION_ENCODING_SCALE_OFFSET) {
    *scale = q.scaleOffsetEncoding.scale;
    *offset = q.scaleOffsetEncoding.offset;
    return true;
  }
  return false;
}

void fillMeta(const Qnn_Tensor_t& t, IoTensor& m) {
  m.onnxName = tensorName(t) ? tensorName(t) : "";
  uint32_t rank = tensorRank(t);
  const uint32_t* d = tensorDims(t);
  m.elems = 1;
  for (uint32_t k = 0; k < rank; ++k) m.elems *= d[k];
  Qnn_DataType_t dt = tensorDataType(t);
  bool i8 = (dt == QNN_DATATYPE_UFIXED_POINT_8 ||
             dt == QNN_DATATYPE_SFIXED_POINT_8 || dt == QNN_DATATYPE_UINT_8 ||
             dt == QNN_DATATYPE_INT_8);
  m.elemBytes = i8 ? 1 : 4;
  m.isSigned = (dt == QNN_DATATYPE_SFIXED_POINT_8 || dt == QNN_DATATYPE_INT_8);
  m.quant = i8 && getScaleOffset(t, &m.scale, &m.offset);
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
  if (!getProviders) { LOGE("dlsym QnnInterface_getProviders: %s", dlerror()); return false; }
  const QnnInterface_t** providers = nullptr;
  uint32_t num = 0;
  if (getProviders(&providers, &num) != QNN_SUCCESS || num == 0 || !providers) {
    LOGE("getProviders num=%u", num); return false;
  }
  for (uint32_t i = 0; i < num; ++i) {
    if (providers[i]->apiVersion.coreApiVersion.major == QNN_API_VERSION_MAJOR) {
      *out = providers[i]->QNN_INTERFACE_VER_NAME;
      return true;
    }
  }
  *out = providers[0]->QNN_INTERFACE_VER_NAME;
  return true;
}
bool resolveSystemInterface(void* lib, QNN_SYSTEM_INTERFACE_VER_TYPE* out) {
  auto getProviders = reinterpret_cast<QnnSystemInterfaceGetProvidersFn_t>(
      dlsym(lib, "QnnSystemInterface_getProviders"));
  if (!getProviders) { LOGE("dlsym sys getProviders: %s", dlerror()); return false; }
  const QnnSystemInterface_t** providers = nullptr;
  uint32_t num = 0;
  if (getProviders(&providers, &num) != QNN_SUCCESS || num == 0 || !providers) {
    LOGE("sys getProviders empty"); return false;
  }
  *out = providers[0]->QNN_SYSTEM_INTERFACE_VER_NAME;
  return true;
}

void logCb(const char* fmt, QnnLog_Level_t level, uint64_t, va_list argp) {
  if (!fmt) return;
  char buf[1024];
  vsnprintf(buf, sizeof(buf), fmt, argp);
  int prio = level == QNN_LOG_LEVEL_ERROR ? ANDROID_LOG_ERROR
             : level == QNN_LOG_LEVEL_WARN ? ANDROID_LOG_WARN
                                           : ANDROID_LOG_INFO;
  __android_log_print(prio, "App:ScrfdQnnLog", "%s", buf);
}

bool extractGraphIO(ScrfdEngine* e, const std::vector<uint8_t>& binary) {
  QnnSystemContext_Handle_t sysCtx = nullptr;
  if (e->sys.systemContextCreate(&sysCtx) != QNN_SUCCESS || !sysCtx) {
    LOGE("systemContextCreate failed"); return false;
  }
  const QnnSystemContext_BinaryInfo_t* info = nullptr;
  Qnn_ContextBinarySize_t infoSize = 0;
  Qnn_ErrorHandle_t err = e->sys.systemContextGetBinaryInfo(
      sysCtx, const_cast<void*>(static_cast<const void*>(binary.data())),
      binary.size(), &info, &infoSize);
  if (err != QNN_SUCCESS || !info) {
    LOGE("getBinaryInfo err=0x%llx", (unsigned long long)err);
    e->sys.systemContextFree(sysCtx); return false;
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
    LOGE("unknown binary info version %d", info->version);
    e->sys.systemContextFree(sysCtx); return false;
  }

  bool found = false;
  for (uint32_t g = 0; g < numGraphs && !found; ++g) {
    const QnnSystemContext_GraphInfo_t& gi = graphs[g];
    const char* gname = nullptr;
    uint32_t numIn = 0, numOut = 0;
    const Qnn_Tensor_t *ins = nullptr, *outs = nullptr;
    if (gi.version == QNN_SYSTEM_CONTEXT_GRAPH_INFO_VERSION_1) {
      gname = gi.graphInfoV1.graphName; numIn = gi.graphInfoV1.numGraphInputs;
      numOut = gi.graphInfoV1.numGraphOutputs; ins = gi.graphInfoV1.graphInputs;
      outs = gi.graphInfoV1.graphOutputs;
    } else if (gi.version == QNN_SYSTEM_CONTEXT_GRAPH_INFO_VERSION_2) {
      gname = gi.graphInfoV2.graphName; numIn = gi.graphInfoV2.numGraphInputs;
      numOut = gi.graphInfoV2.numGraphOutputs; ins = gi.graphInfoV2.graphInputs;
      outs = gi.graphInfoV2.graphOutputs;
    } else {
      gname = gi.graphInfoV3.graphName; numIn = gi.graphInfoV3.numGraphInputs;
      numOut = gi.graphInfoV3.numGraphOutputs; ins = gi.graphInfoV3.graphInputs;
      outs = gi.graphInfoV3.graphOutputs;
    }
    LOGI("binary graph[%u] name=%s in=%u out=%u", g, gname ? gname : "?", numIn, numOut);
    // Single-graph DLC: accept the sole graph.
    if (numGraphs != 1 && g != 0) continue;
    e->graphName = gname ? gname : "scrfd";
    e->dimStore.reserve(numIn + numOut);
    for (uint32_t i = 0; i < numIn; ++i)
      e->inputs.push_back(cloneTensorForExec(ins[i], e->dimStore));
    for (uint32_t i = 0; i < numOut; ++i)
      e->outputs.push_back(cloneTensorForExec(outs[i], e->dimStore));
    found = true;
  }
  e->sys.systemContextFree(sysCtx);
  if (!found) { LOGE("no graph in binary"); return false; }

  e->inMeta.resize(e->inputs.size());
  e->outMeta.resize(e->outputs.size());
  for (size_t i = 0; i < e->inputs.size(); ++i) {
    fillMeta(e->inputs[i], e->inMeta[i]);
    LOGI("input[%zu] name=%s elems=%u bytes=%u quant=%d scale=%.6g off=%d signed=%d",
         i, e->inMeta[i].onnxName.c_str(), e->inMeta[i].elems, e->inMeta[i].elemBytes,
         e->inMeta[i].quant, e->inMeta[i].scale, e->inMeta[i].offset, e->inMeta[i].isSigned);
  }
  for (size_t i = 0; i < e->outputs.size(); ++i) {
    fillMeta(e->outputs[i], e->outMeta[i]);
    LOGI("output[%zu] name=%s elems=%u bytes=%u quant=%d scale=%.6g off=%d signed=%d",
         i, e->outMeta[i].onnxName.c_str(), e->outMeta[i].elems, e->outMeta[i].elemBytes,
         e->outMeta[i].quant, e->outMeta[i].scale, e->outMeta[i].offset, e->outMeta[i].isSigned);
  }
  return true;
}

bool setupIonIo(ScrfdEngine* e) {
  if (!e->rpcAlloc || !e->rpcToFd || !e->qnn.memRegister) {
    LOGW("rpcmem/memRegister unavailable; ION disabled"); return false;
  }
  auto allocBind = [&](const Qnn_Tensor_t& t, IoTensor& m, Qnn_Tensor_t& execTensor) -> bool {
    int bytes = (int)(m.elems * m.elemBytes);
    m.ion = e->rpcAlloc(kRpcmemHeapIdSystem, kRpcmemDefaultFlags, bytes);
    if (!m.ion) { LOGE("rpcmem_alloc %d failed for %s", bytes, m.onnxName.c_str()); return false; }
    m.fd = e->rpcToFd(m.ion);
    if (m.fd <= 0) { LOGE("rpcmem_to_fd failed for %s", m.onnxName.c_str()); return false; }
    uint32_t rank = tensorRank(t);
    const uint32_t* dims = tensorDims(t);
    std::vector<uint32_t> dimBuf(dims, dims + rank);
    Qnn_MemDescriptor_t d; memset(&d, 0, sizeof(d));
    d.memShape.numDim = rank;
    d.memShape.dimSize = dimBuf.data();
    d.memShape.shapeConfig = nullptr;
    d.dataType = tensorDataType(t);
    d.memType = QNN_MEM_TYPE_ION;
    d.ionInfo.fd = m.fd;
    Qnn_ErrorHandle_t er = e->qnn.memRegister(e->context, &d, 1, &m.mem);
    if (er != QNN_SUCCESS || !m.mem) {
      LOGE("memRegister(%s) err=0x%llx", m.onnxName.c_str(), (unsigned long long)er);
      return false;
    }
    setTensorMemHandle(execTensor, m.mem);
    return true;
  };
  for (size_t i = 0; i < e->inputs.size(); ++i)
    if (!allocBind(e->inputs[i], e->inMeta[i], e->inputs[i])) return false;
  for (size_t i = 0; i < e->outputs.size(); ++i)
    if (!allocBind(e->outputs[i], e->outMeta[i], e->outputs[i])) return false;
  e->ionReady = true;
  LOGI("ION I/O ready (%zu in, %zu out)", e->inputs.size(), e->outputs.size());
  return true;
}

// --- SCRFD decode (matches scrfd_util.py exactly) ---------------------------

struct Det { float x0, y0, x1, y1, score; float kps[10]; };

float iou(const Det& a, const Det& b) {
  float xx1 = std::max(a.x0, b.x0), yy1 = std::max(a.y0, b.y0);
  float xx2 = std::min(a.x1, b.x1), yy2 = std::min(a.y1, b.y1);
  float w = std::max(0.f, xx2 - xx1 + 1.f), h = std::max(0.f, yy2 - yy1 + 1.f);
  float inter = w * h;
  float a1 = (a.x1 - a.x0 + 1.f) * (a.y1 - a.y0 + 1.f);
  float a2 = (b.x1 - b.x0 + 1.f) * (b.y1 - b.y0 + 1.f);
  return inter / (a1 + a2 - inter + 1e-9f);
}

// Read a dequantized float at element index i from an output IoTensor's ION buf.
inline float readOut(const IoTensor& m, uint32_t i) {
  if (m.elemBytes == 1) {
    if (m.isSigned) {
      int8_t q = reinterpret_cast<const int8_t*>(m.ion)[i];
      return ((int)q - m.offset) * m.scale;
    }
    uint8_t q = reinterpret_cast<const uint8_t*>(m.ion)[i];
    return ((int)q + m.offset) * m.scale;
  }
  return reinterpret_cast<const float*>(m.ion)[i];
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_repository_glasses_capture_ScrfdQnnNative_nativeInit(
    JNIEnv* env, jclass, jstring jBackendDir, jstring jModelPath,
    jstring jCacheDir, jstring jPrebuiltCtxPath) {
  const char* backendDir = env->GetStringUTFChars(jBackendDir, nullptr);
  const char* modelPath = env->GetStringUTFChars(jModelPath, nullptr);
  const char* cacheDir = env->GetStringUTFChars(jCacheDir, nullptr);
  const char* prebuilt = jPrebuiltCtxPath
                             ? env->GetStringUTFChars(jPrebuiltCtxPath, nullptr) : "";
  std::string backendDirS(backendDir), modelPathS(modelPath), cacheDirS(cacheDir);
  std::string prebuiltS(prebuilt);
  env->ReleaseStringUTFChars(jBackendDir, backendDir);
  env->ReleaseStringUTFChars(jModelPath, modelPath);
  env->ReleaseStringUTFChars(jCacheDir, cacheDir);
  if (jPrebuiltCtxPath) env->ReleaseStringUTFChars(jPrebuiltCtxPath, prebuilt);

  // Semicolon-separated (NOT colon) -- see qnn_denoise.cpp / RECIPE.md gotcha.
  std::string adsp = backendDirS + ";/vendor/dsp/cdsp;/vendor/lib/rfsa/adsp";
  setenv("ADSP_LIBRARY_PATH", adsp.c_str(), 1);

  // Pre-load the Qualcomm CDSP fastRPC client (bare name, resolved via the
  // <uses-native-library libcdsprpc.so> manifest grant -- works for this
  // /data/app-installed NON-privileged capture process) so libQnnHtpV73Stub.so's
  // transitive SONAME dependency resolves against this already-loaded copy.
  void* cdsp = dlopen("libcdsprpc.so", RTLD_NOW | RTLD_GLOBAL);
  if (cdsp) LOGI("pre-loaded libcdsprpc.so for HTP fastRPC stub");
  else LOGW("failed to pre-load libcdsprpc.so: %s", dlerror());

  auto* e = new ScrfdEngine();
  e->cdspLib = cdsp;
  if (cdsp) {
    e->rpcAlloc = reinterpret_cast<RpcMemAllocFn_t>(dlsym(cdsp, "rpcmem_alloc"));
    e->rpcFree = reinterpret_cast<RpcMemFreeFn_t>(dlsym(cdsp, "rpcmem_free"));
    e->rpcToFd = reinterpret_cast<RpcMemToFdFn_t>(dlsym(cdsp, "rpcmem_to_fd"));
  }

  std::string htpPath = backendDirS + "/libQnnHtp.so";
  std::string sysPath = backendDirS + "/libQnnSystem.so";
  e->backendLib = dlopen(htpPath.c_str(), RTLD_NOW | RTLD_LOCAL);
  if (!e->backendLib) { LOGE("dlopen %s: %s", htpPath.c_str(), dlerror()); delete e; return 0; }
  e->systemLib = dlopen(sysPath.c_str(), RTLD_NOW | RTLD_LOCAL);
  if (!e->systemLib) { LOGE("dlopen %s: %s", sysPath.c_str(), dlerror()); delete e; return 0; }

  if (!resolveBackendInterface(e->backendLib, &e->qnn) ||
      !resolveSystemInterface(e->systemLib, &e->sys)) { delete e; return 0; }

  Qnn_LogHandle_t logHandle = nullptr;
  if (e->qnn.logCreate(logCb, QNN_LOG_LEVEL_WARN, &logHandle) != QNN_SUCCESS) logHandle = nullptr;

  if (e->qnn.backendCreate(logHandle, nullptr, &e->backend) != QNN_SUCCESS || !e->backend) {
    LOGE("backendCreate failed"); delete e; return 0;
  }

  // Unsigned-PD HTP device (the only PD this AR1 silicon accepts).
  QnnHtpDevice_CustomConfig_t htpSoc; memset(&htpSoc, 0, sizeof(htpSoc));
  htpSoc.option = QNN_HTP_DEVICE_CONFIG_OPTION_SOC; htpSoc.socModel = kSocModel;
  QnnHtpDevice_CustomConfig_t htpArch; memset(&htpArch, 0, sizeof(htpArch));
  htpArch.option = QNN_HTP_DEVICE_CONFIG_OPTION_ARCH;
  htpArch.arch.deviceId = 0; htpArch.arch.arch = QNN_HTP_DEVICE_ARCH_V73;
  QnnHtpDevice_CustomConfig_t htpPd; memset(&htpPd, 0, sizeof(htpPd));
  htpPd.option = QNN_HTP_DEVICE_CONFIG_OPTION_SIGNEDPD;
  htpPd.useSignedProcessDomain.deviceId = 0;
  htpPd.useSignedProcessDomain.useSignedProcessDomain = false;
  QnnDevice_Config_t cfgSoc; memset(&cfgSoc, 0, sizeof(cfgSoc));
  cfgSoc.option = QNN_DEVICE_CONFIG_OPTION_CUSTOM; cfgSoc.customConfig = &htpSoc;
  QnnDevice_Config_t cfgArch; memset(&cfgArch, 0, sizeof(cfgArch));
  cfgArch.option = QNN_DEVICE_CONFIG_OPTION_CUSTOM; cfgArch.customConfig = &htpArch;
  QnnDevice_Config_t cfgPd; memset(&cfgPd, 0, sizeof(cfgPd));
  cfgPd.option = QNN_DEVICE_CONFIG_OPTION_CUSTOM; cfgPd.customConfig = &htpPd;
  const QnnDevice_Config_t* devCfgs[] = {&cfgSoc, &cfgArch, &cfgPd, nullptr};
  Qnn_ErrorHandle_t derr = e->qnn.deviceCreate(logHandle, devCfgs, &e->device);
  if (derr != QNN_SUCCESS) {
    LOGW("deviceCreate(full) err=0x%llx; retry PD-only", (unsigned long long)derr);
    const QnnDevice_Config_t* pdOnly[] = {&cfgPd, nullptr};
    derr = e->qnn.deviceCreate(logHandle, pdOnly, &e->device);
    if (derr != QNN_SUCCESS) { LOGE("deviceCreate(PD-only) err=0x%llx", (unsigned long long)derr); e->device = nullptr; }
  }
  if (e->device) LOGI("QnnDevice_create OK (unsigned PD)");

  std::string cachePath = cacheDirS + "/scrfd_htp_v73_w8a8.qnnctx";
  std::vector<uint8_t> modelBin;
  bool isContextBin = false;
  if (fileExists(cachePath) && readFile(cachePath, modelBin) && !modelBin.empty()) {
    e->fromCache = true; isContextBin = true;
    LOGI("loading prepared context cache (%zu bytes)", modelBin.size());
  } else if (!prebuiltS.empty() && fileExists(prebuiltS) &&
             readFile(prebuiltS, modelBin) && !modelBin.empty()) {
    e->fromCache = true; isContextBin = true;
    LOGI("loading prebuilt context binary %s (%zu bytes)", prebuiltS.c_str(), modelBin.size());
  } else {
    if (!readFile(modelPathS, modelBin) || modelBin.empty()) {
      LOGE("failed to read DLC %s", modelPathS.c_str()); delete e; return 0;
    }
    LOGI("preparing from DLC fresh (%zu bytes)", modelBin.size());
  }
  bool haveCache = isContextBin;

  Qnn_ContextHandle_t ctx = nullptr;
  Qnn_ErrorHandle_t cerr = e->qnn.contextCreateFromBinary(
      e->backend, e->device, nullptr, modelBin.data(), modelBin.size(), &ctx, nullptr);
  if (cerr != QNN_SUCCESS || !ctx) {
    LOGE("contextCreateFromBinary err=0x%llx", (unsigned long long)cerr); delete e; return 0;
  }
  e->context = ctx;

  std::vector<uint8_t> ctxBin;
  if (haveCache) {
    ctxBin = modelBin;
  } else {
    Qnn_ContextBinarySize_t binSize = 0;
    if (e->qnn.contextGetBinarySize(e->context, &binSize) != QNN_SUCCESS || binSize == 0) {
      LOGE("getBinarySize failed"); delete e; return 0;
    }
    ctxBin.resize(binSize);
    Qnn_ContextBinarySize_t written = 0;
    if (e->qnn.contextGetBinary(e->context, ctxBin.data(), binSize, &written) != QNN_SUCCESS || written == 0) {
      LOGE("getBinary failed"); delete e; return 0;
    }
    ctxBin.resize(written);
    if (writeFile(cachePath, ctxBin.data(), written))
      LOGI("wrote prepared context cache %s (%llu bytes)", cachePath.c_str(), (unsigned long long)written);
    else LOGW("failed to write context cache %s", cachePath.c_str());
  }

  if (!extractGraphIO(e, ctxBin)) { delete e; return 0; }

  const char* gname = e->graphName.empty() ? "scrfd" : e->graphName.c_str();
  if (e->qnn.graphRetrieve(e->context, gname, &e->graph) != QNN_SUCCESS || !e->graph) {
    LOGE("graphRetrieve(%s) failed", gname); delete e; return 0;
  }

  if (e->inputs.size() != 1 || e->outputs.size() != 9) {
    LOGW("unexpected IO count in=%zu out=%zu (expected 1/9)", e->inputs.size(), e->outputs.size());
  }
  if (!setupIonIo(e)) { LOGW("ION I/O setup failed; HTP output may be partial"); delete e; return 0; }

  LOGI("nativeInit OK (fromCache=%d) graph=%s ion=%d", e->fromCache ? 1 : 0, gname, e->ionReady ? 1 : 0);
  return reinterpret_cast<jlong>(e);
}

// Detect faces in an ARGB_8888 bitmap. Returns a float[] laid out as
// [count, then per-face: score,x0,y0,x1,y1, kx0,ky0,...kx4,ky4 (15 floats)].
// Boxes/keypoints are in ORIGINAL bitmap pixel coordinates.
JNIEXPORT jfloatArray JNICALL
Java_com_repository_glasses_capture_ScrfdQnnNative_nativeDetect(
    JNIEnv* env, jclass, jlong handle, jobject bitmap) {
  auto* e = reinterpret_cast<ScrfdEngine*>(handle);
  if (!e || e->inputs.empty() || e->outputs.size() != 9 || !e->ionReady) return nullptr;

  AndroidBitmapInfo info;
  if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) {
    LOGE("getInfo failed"); return nullptr;
  }
  if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
    LOGE("bitmap not RGBA_8888 (fmt=%d)", info.format); return nullptr;
  }
  int W = (int)info.width, H = (int)info.height;
  void* pixels = nullptr;
  if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
    LOGE("lockPixels failed"); return nullptr;
  }

  // --- Letterbox preprocessing (matches scrfd_util.preprocess) -------------
  // im_ratio = H/W. model_ratio=1. If im_ratio>1: new_h=640,new_w=640/ratio
  // else new_w=640, new_h=640*ratio. det_scale = new_h/H.
  float imRatio = (float)H / (float)W;
  int newW, newH;
  if (imRatio > 1.0f) { newH = kInputSize; newW = (int)(kInputSize / imRatio); }
  else { newW = kInputSize; newH = (int)(kInputSize * imRatio); }
  if (newW < 1) newW = 1; if (newH < 1) newH = 1;
  float detScale = (float)newH / (float)H;

  // Input ION buffer. Graph input is NCHW; channel order RGB; value (px-127.5)/128.
  // If the input tensor is int8-quantized we additionally quantize.
  IoTensor& im = e->inMeta[0];
  const uint32_t stride = info.stride;  // bytes per row
  const uint8_t* src = reinterpret_cast<const uint8_t*>(pixels);
  const int plane = kInputSize * kInputSize;

  // Nearest-neighbor resize from (W,H) into (newW,newH) top-left of a 640x640
  // zero-padded canvas, writing directly into the NCHW channel planes.
  auto writeInput = [&](auto writeFn) {
    for (int c = 0; c < 3; ++c) {
      // RGB order: c=0 R, c=1 G, c=2 B. Source RGBA bytes: [0]=R,[1]=G,[2]=B,[3]=A.
      for (int y = 0; y < kInputSize; ++y) {
        for (int x = 0; x < kInputSize; ++x) {
          float val127 = 0.f;  // pad region -> (0-127.5)/128
          if (x < newW && y < newH) {
            int sx = (int)((x + 0.5f) * W / newW); if (sx >= W) sx = W - 1;
            int sy = (int)((y + 0.5f) * H / newH); if (sy >= H) sy = H - 1;
            const uint8_t* p = src + (size_t)sy * stride + (size_t)sx * 4;
            uint8_t pv = p[c];  // c maps R/G/B directly
            val127 = ((float)pv - 127.5f) / 128.0f;
          } else {
            val127 = (0.f - 127.5f) / 128.0f;
          }
          writeFn(c * plane + y * kInputSize + x, val127);
        }
      }
    }
  };

  if (im.elemBytes == 1 && im.quant) {
    uint8_t* q = reinterpret_cast<uint8_t*>(im.ion);
    int8_t* qs = reinterpret_cast<int8_t*>(im.ion);
    float invScale = 1.0f / im.scale;
    bool sgn = im.isSigned;
    writeInput([&](int idx, float v) {
      int iv = (int)lrintf(v * invScale) - im.offset;
      if (sgn) { if (iv < -128) iv = -128; else if (iv > 127) iv = 127; qs[idx] = (int8_t)iv; }
      else { if (iv < 0) iv = 0; else if (iv > 255) iv = 255; q[idx] = (uint8_t)iv; }
    });
  } else {
    float* f = reinterpret_cast<float*>(im.ion);
    writeInput([&](int idx, float v) { f[idx] = v; });
  }

  AndroidBitmap_unlockPixels(env, bitmap);

  // --- Execute on HTP ------------------------------------------------------
  struct timespec ts0, ts1;
  clock_gettime(CLOCK_MONOTONIC, &ts0);
  Qnn_ErrorHandle_t err = e->qnn.graphExecute(
      e->graph, e->inputs.data(), (uint32_t)e->inputs.size(),
      e->outputs.data(), (uint32_t)e->outputs.size(), nullptr, nullptr);
  clock_gettime(CLOCK_MONOTONIC, &ts1);
  long execMs = (ts1.tv_sec - ts0.tv_sec) * 1000 +
                (ts1.tv_nsec - ts0.tv_nsec) / 1000000;
  LOGI("graphExecute %ldms err=0x%llx", execMs, (unsigned long long)err);
  if (err != QNN_SUCCESS) {
    LOGE("graphExecute failed err=0x%llx", (unsigned long long)err);
    return nullptr;
  }

  // --- Decode (stride 8/16/32, 2 anchors, distance2bbox/kps) ---------------
  // Map outputs by onnx name to (kind, stride). Names: scores 448/471/494,
  // bbox 451/474/497, kps 454/477/500.
  // QNN names the outputs with a leading underscore (e.g. "_448"), so match the
  // onnx tensor number against the trailing digits, ignoring a leading '_'.
  auto findOut = [&](const char* name) -> const IoTensor* {
    for (auto& m : e->outMeta) {
      const char* tn = m.onnxName.c_str();
      if (tn[0] == '_') tn++;  // skip QNN's leading underscore
      if (strcmp(tn, name) == 0) return &m;
    }
    return nullptr;
  };
  struct StrideTensors { const IoTensor *score, *bbox, *kps; int stride; };
  const char* scoreN[3] = {"448", "471", "494"};
  const char* bboxN[3] = {"451", "474", "497"};
  const char* kpsN[3] = {"454", "477", "500"};
  StrideTensors st[3];
  bool byName = true;
  for (int s = 0; s < 3; ++s) {
    st[s].stride = kStrides[s].stride;
    st[s].score = findOut(scoreN[s]);
    st[s].bbox = findOut(bboxN[s]);
    st[s].kps = findOut(kpsN[s]);
    if (!st[s].score || !st[s].bbox || !st[s].kps) byName = false;
  }
  if (!byName) {
    // Fallback: map by element count + dim. score elems=count*1, bbox=count*4,
    // kps=count*10. Group per stride by count.
    for (int s = 0; s < 3; ++s) {
      int cnt = kStrides[s].count;
      st[s].stride = kStrides[s].stride;
      st[s].score = st[s].bbox = st[s].kps = nullptr;
      for (auto& m : e->outMeta) {
        if (m.elems == (uint32_t)cnt * 1) st[s].score = &m;
        else if (m.elems == (uint32_t)cnt * 4) st[s].bbox = &m;
        else if (m.elems == (uint32_t)cnt * 10) st[s].kps = &m;
      }
    }
    LOGW("decode: output name match failed, using elem-count fallback");
  }

  std::vector<Det> dets;
  for (int s = 0; s < 3; ++s) {
    const IoTensor *sc = st[s].score, *bb = st[s].bbox, *kp = st[s].kps;
    if (!sc || !bb || !kp) { LOGW("missing tensors for stride %d", st[s].stride); continue; }
    int stride = st[s].stride;
    int fw = kInputSize / stride;  // feature width/height
    int fh = fw;
    int loc = 0;
    // Anchor centers: meshgrid over (fh,fw), *stride, then duplicated x2 per
    // anchor (so index = (row*fw + col)*2 + anchor).
    for (int row = 0; row < fh; ++row) {
      for (int col = 0; col < fw; ++col) {
        for (int a = 0; a < 2; ++a) {
          uint32_t idx = (uint32_t)loc;  // sequential anchor index
          float scv = readOut(*sc, idx);
          if (scv >= kScoreThr) {
            float cx = (float)(col * stride);
            float cy = (float)(row * stride);
            float d0 = readOut(*bb, idx * 4 + 0) * stride;
            float d1 = readOut(*bb, idx * 4 + 1) * stride;
            float d2 = readOut(*bb, idx * 4 + 2) * stride;
            float d3 = readOut(*bb, idx * 4 + 3) * stride;
            Det d;
            d.x0 = (cx - d0) / detScale;
            d.y0 = (cy - d1) / detScale;
            d.x1 = (cx + d2) / detScale;
            d.y1 = (cy + d3) / detScale;
            d.score = scv;
            for (int k = 0; k < 5; ++k) {
              float kx = (cx + readOut(*kp, idx * 10 + k * 2 + 0) * stride) / detScale;
              float ky = (cy + readOut(*kp, idx * 10 + k * 2 + 1) * stride) / detScale;
              d.kps[k * 2 + 0] = kx;
              d.kps[k * 2 + 1] = ky;
            }
            dets.push_back(d);
          }
          ++loc;
        }
      }
    }
  }

  // NMS (sort by score desc, IoU 0.4). Cap the candidate set to the top-K by
  // score before the O(n^2) NMS: a real frame has a handful of faces, but a
  // degenerate/uniform input (e.g. a blank warmup bitmap) can push thousands of
  // anchors past the 0.5 threshold, which would make NMS take seconds. InsightFace
  // applies the same pre-NMS top-k. 1000 is far more than any real face count.
  constexpr size_t kPreNmsTopK = 1000;
  std::sort(dets.begin(), dets.end(), [](const Det& a, const Det& b) { return a.score > b.score; });
  if (dets.size() > kPreNmsTopK) dets.resize(kPreNmsTopK);
  std::vector<Det> keep;
  std::vector<char> removed(dets.size(), 0);
  for (size_t i = 0; i < dets.size(); ++i) {
    if (removed[i]) continue;
    keep.push_back(dets[i]);
    for (size_t j = i + 1; j < dets.size(); ++j)
      if (!removed[j] && iou(dets[i], dets[j]) > kNmsThr) removed[j] = 1;
  }

  int n = (int)keep.size();
  int total = 1 + n * 15;
  jfloatArray out = env->NewFloatArray(total);
  if (!out) return nullptr;
  std::vector<float> buf(total);
  buf[0] = (float)n;
  for (int i = 0; i < n; ++i) {
    const Det& d = keep[i];
    int o = 1 + i * 15;
    buf[o + 0] = d.score;
    buf[o + 1] = d.x0; buf[o + 2] = d.y0; buf[o + 3] = d.x1; buf[o + 4] = d.y1;
    for (int k = 0; k < 10; ++k) buf[o + 5 + k] = d.kps[k];
  }
  env->SetFloatArrayRegion(out, 0, total, buf.data());
  return out;
}

JNIEXPORT jboolean JNICALL
Java_com_repository_glasses_capture_ScrfdQnnNative_nativeFromCache(
    JNIEnv*, jclass, jlong handle) {
  auto* e = reinterpret_cast<ScrfdEngine*>(handle);
  return (e && e->fromCache) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_repository_glasses_capture_ScrfdQnnNative_nativeClose(
    JNIEnv*, jclass, jlong handle) {
  auto* e = reinterpret_cast<ScrfdEngine*>(handle);
  if (e) delete e;
}

}  // extern "C"
