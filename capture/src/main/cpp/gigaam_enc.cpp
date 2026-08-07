// JNI shim running the GigaAM v3_rnnt CONFORMER ENCODER (w8a16) on the Hexagon
// V73 NPU via the raw QNN C API, plus the log-mel front end in C++.
//
// This is a THROWAWAY BENCHMARK HARNESS. It reuses the exact QNN bring-up
// pattern validated in clients/glasses/capture/src/main/cpp/scrfd_qnn.cpp:
//   - raw QNN C API (ORT-QNN forces a signed PD this AR1 silicon rejects),
//   - ADSP_LIBRARY_PATH is SEMICOLON-separated (not colon),
//   - libcdsprpc.so must be dlopen'd RTLD_GLOBAL before the V73 stub,
//   - ION/rpcmem-backed I/O (plain heap clientBuf only partially DMAs back),
//   - unsigned PD, soc_model 58 (SSG2125P AR1 Gen1), dsp_arch v73.
//
// The 221 MB context binary is NOT in the APK; it is read from
// the prebuilt context binary (enc_e2e_ctx.bin) (world-readable).
//
// Encoder I/O (verified on-device):
//   in  audio_signal [1,64,500] float32   (64 log-mel bins, 500 frames = 5 s)
//   in  length       [1]        float32   (SAMPLE count, e.g. 80000.0)
//   out encoded      [1,768,125] float32
//   out encoded_len  [1]        float32   (written as FLOAT, not int32)
// Input binding is BY NAME -- the positional order in the binary puts `length`
// first, which silently swaps the tensors if you bind positionally.
//
// Feature front end (verified vs torchaudio, max abs diff 1.05e-05):
//   sr 16000, n_fft 320, win 320, hop 160, 64 mel bins, center=false,
//   htk mel scale, norm=None, PERIODIC hann, power 2.0,
//   then log(clamp(x, 1e-9, 1e9)). NO mean/var normalisation.
// n_fft=320 is not a power of two, so a direct 161x320 real DFT is used; at
// this size it is cheap and exactly matches the reference.

#include <jni.h>
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

#define LOG_TAG "GigaAmEnc"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

constexpr int kMel = 64;
constexpr int kFrames = 500;          // encoder window, 5 s at hop 160
constexpr int kNFft = 320;
constexpr int kHop = 160;
constexpr int kBins = kNFft / 2 + 1;  // 161
constexpr int kEncDim = 768;
constexpr int kEncFrames = 125;
constexpr uint32_t kSocModel = 58;    // SSG2125P "AR1 Gen1" -> QNN enum 58

typedef Qnn_ErrorHandle_t (*QnnInterfaceGetProvidersFn_t)(const QnnInterface_t***, uint32_t*);
typedef Qnn_ErrorHandle_t (*QnnSystemInterfaceGetProvidersFn_t)(const QnnSystemInterface_t***, uint32_t*);
typedef void* (*RpcMemAllocFn_t)(int, uint32_t, int);
typedef void (*RpcMemFreeFn_t)(void*);
typedef int (*RpcMemToFdFn_t)(void*);
constexpr int kRpcmemHeapIdSystem = 25;
constexpr uint32_t kRpcmemDefaultFlags = 1;

struct IoTensor {
  std::string name;
  uint32_t elems = 0;
  uint32_t elemBytes = 4;
  // w8a16 means the ACTIVATIONS are 16-bit quantized: audio_signal and encoded
  // are UFIXED_POINT_16 in this binary, only length/encoded_len are float32.
  // qnn-net-run converted float files silently; the raw C API does not, so we
  // quantize on write and dequantize on read here.
  bool quant = false;
  float scale = 1.f;
  int32_t offset = 0;
  bool isSigned = false;
  void* ion = nullptr;
  int fd = -1;
  Qnn_MemHandle_t mem = nullptr;
};

struct EncEngine {
  void* backendLib = nullptr;
  void* systemLib = nullptr;
  void* cdspLib = nullptr;
  QNN_INTERFACE_VER_TYPE qnn{};
  QNN_SYSTEM_INTERFACE_VER_TYPE sys{};
  Qnn_BackendHandle_t backend = nullptr;
  Qnn_DeviceHandle_t device = nullptr;
  Qnn_ContextHandle_t context = nullptr;
  Qnn_GraphHandle_t graph = nullptr;
  std::vector<Qnn_Tensor_t> inputs, outputs;
  std::vector<std::vector<uint32_t>> dimStore;
  std::vector<IoTensor> inMeta, outMeta;
  std::string graphName;

  RpcMemAllocFn_t rpcAlloc = nullptr;
  RpcMemFreeFn_t rpcFree = nullptr;
  RpcMemToFdFn_t rpcToFd = nullptr;

  // Resolved by NAME, not position (the binary lists `length` first).
  int idxAudio = -1, idxLength = -1, idxEncoded = -1, idxEncLen = -1;

  // Feature front end scratch.
  std::vector<float> melFb;    // [64][161] row-major
  std::vector<float> window;   // periodic hann, 320
  std::vector<float> cosTab, sinTab;  // [161][320] real DFT tables
  std::vector<float> frameBuf, powBuf;

  ~EncEngine() {
    for (auto* v : {&inMeta, &outMeta})
      for (auto& m : *v) {
        if (m.mem && qnn.memDeRegister) qnn.memDeRegister(&m.mem, 1);
        if (m.ion && rpcFree) rpcFree(m.ion);
      }
    if (context && qnn.contextFree) qnn.contextFree(context, nullptr);
    if (device && qnn.deviceFree) qnn.deviceFree(device);
    if (backend && qnn.backendFree) qnn.backendFree(backend);
    // NOTE: deliberately NOT dlclose()ing the QNN/cdsp libs. Their TLS
    // destructors have bitten this codebase before (libcae SIGSEGV); this is a
    // benchmark process that exits anyway.
  }
};

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

Qnn_Tensor_t cloneTensorForExec(const Qnn_Tensor_t& src,
                                std::vector<std::vector<uint32_t>>& dimStore) {
  Qnn_Tensor_t dst = src;
  uint32_t rank = tensorRank(src);
  const uint32_t* sd = tensorDims(src);
  dimStore.emplace_back(sd, sd + rank);
  uint32_t* dc = dimStore.back().data();
  if (dst.version == QNN_TENSOR_VERSION_2) {
    dst.v2.dimensions = dc;
    dst.v2.memType = QNN_TENSORMEMTYPE_RAW;
    dst.v2.clientBuf.data = nullptr;
    dst.v2.clientBuf.dataSize = 0;
    dst.v2.isDynamicDimensions = nullptr;
  } else {
    dst.version = QNN_TENSOR_VERSION_1;
    dst.v1.dimensions = dc;
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
  const Qnn_QuantizeParams_t& q = t.version == QNN_TENSOR_VERSION_2
                                      ? t.v2.quantizeParams : t.v1.quantizeParams;
  if (q.quantizationEncoding == QNN_QUANTIZATION_ENCODING_SCALE_OFFSET) {
    *scale = q.scaleOffsetEncoding.scale;
    *offset = q.scaleOffsetEncoding.offset;
    return true;
  }
  return false;
}

void fillMeta(const Qnn_Tensor_t& t, IoTensor& m) {
  m.name = tensorName(t) ? tensorName(t) : "";
  uint32_t rank = tensorRank(t);
  const uint32_t* d = tensorDims(t);
  m.elems = 1;
  for (uint32_t k = 0; k < rank; ++k) m.elems *= d[k];
  Qnn_DataType_t dt = tensorDataType(t);
  bool i8 = (dt == QNN_DATATYPE_UFIXED_POINT_8 || dt == QNN_DATATYPE_SFIXED_POINT_8 ||
             dt == QNN_DATATYPE_UINT_8 || dt == QNN_DATATYPE_INT_8);
  bool i16 = (dt == QNN_DATATYPE_UFIXED_POINT_16 || dt == QNN_DATATYPE_SFIXED_POINT_16 ||
              dt == QNN_DATATYPE_UINT_16 || dt == QNN_DATATYPE_INT_16);
  m.elemBytes = i8 ? 1 : (i16 ? 2 : 4);
  m.isSigned = (dt == QNN_DATATYPE_SFIXED_POINT_8 || dt == QNN_DATATYPE_INT_8 ||
                dt == QNN_DATATYPE_SFIXED_POINT_16 || dt == QNN_DATATYPE_INT_16);
  m.quant = (i8 || i16) && getScaleOffset(t, &m.scale, &m.offset);
}

// QNN scale/offset convention: real = (q + offset) * scale, so
// q = round(real / scale) - offset.
inline void writeQuant(const IoTensor& m, size_t i, float v) {
  if (!m.quant) { reinterpret_cast<float*>(m.ion)[i] = v; return; }
  long q = lrintf(v / m.scale) - m.offset;
  if (m.elemBytes == 2) {
    if (m.isSigned) {
      if (q < -32768) q = -32768; else if (q > 32767) q = 32767;
      reinterpret_cast<int16_t*>(m.ion)[i] = (int16_t)q;
    } else {
      if (q < 0) q = 0; else if (q > 65535) q = 65535;
      reinterpret_cast<uint16_t*>(m.ion)[i] = (uint16_t)q;
    }
  } else {
    if (m.isSigned) {
      if (q < -128) q = -128; else if (q > 127) q = 127;
      reinterpret_cast<int8_t*>(m.ion)[i] = (int8_t)q;
    } else {
      if (q < 0) q = 0; else if (q > 255) q = 255;
      reinterpret_cast<uint8_t*>(m.ion)[i] = (uint8_t)q;
    }
  }
}

inline float readQuant(const IoTensor& m, size_t i) {
  if (!m.quant) return reinterpret_cast<const float*>(m.ion)[i];
  long q;
  if (m.elemBytes == 2)
    q = m.isSigned ? (long)reinterpret_cast<const int16_t*>(m.ion)[i]
                   : (long)reinterpret_cast<const uint16_t*>(m.ion)[i];
  else
    q = m.isSigned ? (long)reinterpret_cast<const int8_t*>(m.ion)[i]
                   : (long)reinterpret_cast<const uint8_t*>(m.ion)[i];
  return (float)(q + m.offset) * m.scale;
}

bool readFile(const std::string& p, std::vector<uint8_t>& out) {
  FILE* f = fopen(p.c_str(), "rb");
  if (!f) return false;
  fseek(f, 0, SEEK_END);
  long n = ftell(f);
  fseek(f, 0, SEEK_SET);
  if (n <= 0) { fclose(f); return false; }
  out.resize((size_t)n);
  size_t rd = fread(out.data(), 1, out.size(), f);
  fclose(f);
  return rd == out.size();
}

bool resolveBackendInterface(void* lib, QNN_INTERFACE_VER_TYPE* out) {
  auto gp = reinterpret_cast<QnnInterfaceGetProvidersFn_t>(dlsym(lib, "QnnInterface_getProviders"));
  if (!gp) { LOGE("dlsym QnnInterface_getProviders: %s", dlerror()); return false; }
  const QnnInterface_t** pv = nullptr; uint32_t n = 0;
  if (gp(&pv, &n) != QNN_SUCCESS || !n || !pv) { LOGE("getProviders n=%u", n); return false; }
  for (uint32_t i = 0; i < n; ++i)
    if (pv[i]->apiVersion.coreApiVersion.major == QNN_API_VERSION_MAJOR) {
      *out = pv[i]->QNN_INTERFACE_VER_NAME; return true;
    }
  *out = pv[0]->QNN_INTERFACE_VER_NAME;
  return true;
}
bool resolveSystemInterface(void* lib, QNN_SYSTEM_INTERFACE_VER_TYPE* out) {
  auto gp = reinterpret_cast<QnnSystemInterfaceGetProvidersFn_t>(dlsym(lib, "QnnSystemInterface_getProviders"));
  if (!gp) { LOGE("dlsym sys getProviders: %s", dlerror()); return false; }
  const QnnSystemInterface_t** pv = nullptr; uint32_t n = 0;
  if (gp(&pv, &n) != QNN_SUCCESS || !n || !pv) { LOGE("sys getProviders empty"); return false; }
  *out = pv[0]->QNN_SYSTEM_INTERFACE_VER_NAME;
  return true;
}

void logCb(const char* fmt, QnnLog_Level_t level, uint64_t, va_list ap) {
  if (!fmt) return;
  char buf[1024];
  vsnprintf(buf, sizeof(buf), fmt, ap);
  int prio = level == QNN_LOG_LEVEL_ERROR ? ANDROID_LOG_ERROR
             : level == QNN_LOG_LEVEL_WARN ? ANDROID_LOG_WARN : ANDROID_LOG_INFO;
  __android_log_print(prio, "GigaAmEncQnn", "%s", buf);
}

bool extractGraphIO(EncEngine* e, const std::vector<uint8_t>& binary) {
  QnnSystemContext_Handle_t sc = nullptr;
  if (e->sys.systemContextCreate(&sc) != QNN_SUCCESS || !sc) {
    LOGE("systemContextCreate failed"); return false;
  }
  const QnnSystemContext_BinaryInfo_t* info = nullptr;
  Qnn_ContextBinarySize_t infoSize = 0;
  Qnn_ErrorHandle_t err = e->sys.systemContextGetBinaryInfo(
      sc, const_cast<void*>(static_cast<const void*>(binary.data())),
      binary.size(), &info, &infoSize);
  if (err != QNN_SUCCESS || !info) {
    LOGE("getBinaryInfo err=0x%llx", (unsigned long long)err);
    e->sys.systemContextFree(sc); return false;
  }
  uint32_t numGraphs = 0;
  const QnnSystemContext_GraphInfo_t* graphs = nullptr;
  if (info->version == QNN_SYSTEM_CONTEXT_BINARY_INFO_VERSION_1) {
    numGraphs = info->contextBinaryInfoV1.numGraphs; graphs = info->contextBinaryInfoV1.graphs;
  } else if (info->version == QNN_SYSTEM_CONTEXT_BINARY_INFO_VERSION_2) {
    numGraphs = info->contextBinaryInfoV2.numGraphs; graphs = info->contextBinaryInfoV2.graphs;
  } else if (info->version == QNN_SYSTEM_CONTEXT_BINARY_INFO_VERSION_3) {
    numGraphs = info->contextBinaryInfoV3.numGraphs; graphs = info->contextBinaryInfoV3.graphs;
  } else {
    LOGE("unknown binary info version %d", info->version);
    e->sys.systemContextFree(sc); return false;
  }

  bool found = false;
  for (uint32_t g = 0; g < numGraphs && !found; ++g) {
    const QnnSystemContext_GraphInfo_t& gi = graphs[g];
    const char* gname = nullptr; uint32_t nIn = 0, nOut = 0;
    const Qnn_Tensor_t *ins = nullptr, *outs = nullptr;
    if (gi.version == QNN_SYSTEM_CONTEXT_GRAPH_INFO_VERSION_1) {
      gname = gi.graphInfoV1.graphName; nIn = gi.graphInfoV1.numGraphInputs;
      nOut = gi.graphInfoV1.numGraphOutputs; ins = gi.graphInfoV1.graphInputs; outs = gi.graphInfoV1.graphOutputs;
    } else if (gi.version == QNN_SYSTEM_CONTEXT_GRAPH_INFO_VERSION_2) {
      gname = gi.graphInfoV2.graphName; nIn = gi.graphInfoV2.numGraphInputs;
      nOut = gi.graphInfoV2.numGraphOutputs; ins = gi.graphInfoV2.graphInputs; outs = gi.graphInfoV2.graphOutputs;
    } else {
      gname = gi.graphInfoV3.graphName; nIn = gi.graphInfoV3.numGraphInputs;
      nOut = gi.graphInfoV3.numGraphOutputs; ins = gi.graphInfoV3.graphInputs; outs = gi.graphInfoV3.graphOutputs;
    }
    LOGI("binary graph[%u] name=%s in=%u out=%u", g, gname ? gname : "?", nIn, nOut);
    e->graphName = gname ? gname : "";
    e->dimStore.reserve(nIn + nOut);
    for (uint32_t i = 0; i < nIn; ++i) e->inputs.push_back(cloneTensorForExec(ins[i], e->dimStore));
    for (uint32_t i = 0; i < nOut; ++i) e->outputs.push_back(cloneTensorForExec(outs[i], e->dimStore));
    found = true;
  }
  e->sys.systemContextFree(sc);
  if (!found) { LOGE("no graph in binary"); return false; }

  e->inMeta.resize(e->inputs.size());
  e->outMeta.resize(e->outputs.size());
  for (size_t i = 0; i < e->inputs.size(); ++i) {
    fillMeta(e->inputs[i], e->inMeta[i]);
    LOGI("in[%zu] name=%s elems=%u bytes=%u quant=%d scale=%.8g off=%d signed=%d",
         i, e->inMeta[i].name.c_str(), e->inMeta[i].elems, e->inMeta[i].elemBytes,
         e->inMeta[i].quant, e->inMeta[i].scale, e->inMeta[i].offset, e->inMeta[i].isSigned);
  }
  for (size_t i = 0; i < e->outputs.size(); ++i) {
    fillMeta(e->outputs[i], e->outMeta[i]);
    LOGI("out[%zu] name=%s elems=%u bytes=%u quant=%d scale=%.8g off=%d signed=%d",
         i, e->outMeta[i].name.c_str(), e->outMeta[i].elems, e->outMeta[i].elemBytes,
         e->outMeta[i].quant, e->outMeta[i].scale, e->outMeta[i].offset, e->outMeta[i].isSigned);
  }

  // Bind BY NAME. Positional binding swaps audio_signal/length (the binary
  // lists `length` first) and produces garbage.
  auto matches = [](const std::string& n, const char* want) {
    const char* s = n.c_str();
    if (*s == '_') ++s;
    return strstr(s, want) != nullptr;
  };
  for (size_t i = 0; i < e->inMeta.size(); ++i) {
    if (matches(e->inMeta[i].name, "audio_signal")) e->idxAudio = (int)i;
    else if (matches(e->inMeta[i].name, "length")) e->idxLength = (int)i;
  }
  for (size_t i = 0; i < e->outMeta.size(); ++i) {
    if (matches(e->outMeta[i].name, "encoded_len")) e->idxEncLen = (int)i;
    else if (matches(e->outMeta[i].name, "encoded")) e->idxEncoded = (int)i;
  }
  // Fall back to shape-based identification if names did not match.
  if (e->idxAudio < 0 || e->idxLength < 0) {
    for (size_t i = 0; i < e->inMeta.size(); ++i) {
      if (e->inMeta[i].elems == kMel * kFrames) e->idxAudio = (int)i;
      else if (e->inMeta[i].elems == 1) e->idxLength = (int)i;
    }
    LOGW("input name match failed; used shape fallback");
  }
  if (e->idxEncoded < 0) {
    for (size_t i = 0; i < e->outMeta.size(); ++i) {
      if (e->outMeta[i].elems == kEncDim * kEncFrames) e->idxEncoded = (int)i;
      else if (e->outMeta[i].elems == 1) e->idxEncLen = (int)i;
    }
    LOGW("output name match failed; used shape fallback");
  }
  LOGI("bound idxAudio=%d idxLength=%d idxEncoded=%d idxEncLen=%d",
       e->idxAudio, e->idxLength, e->idxEncoded, e->idxEncLen);
  return e->idxAudio >= 0 && e->idxLength >= 0 && e->idxEncoded >= 0;
}

bool setupIonIo(EncEngine* e) {
  if (!e->rpcAlloc || !e->rpcToFd || !e->qnn.memRegister) {
    LOGE("rpcmem/memRegister unavailable"); return false;
  }
  auto bind = [&](const Qnn_Tensor_t& t, IoTensor& m, Qnn_Tensor_t& exec) -> bool {
    int bytes = (int)(m.elems * m.elemBytes);
    m.ion = e->rpcAlloc(kRpcmemHeapIdSystem, kRpcmemDefaultFlags, bytes);
    if (!m.ion) { LOGE("rpcmem_alloc %d for %s failed", bytes, m.name.c_str()); return false; }
    m.fd = e->rpcToFd(m.ion);
    if (m.fd <= 0) { LOGE("rpcmem_to_fd %s failed", m.name.c_str()); return false; }
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
      LOGE("memRegister(%s) err=0x%llx", m.name.c_str(), (unsigned long long)er); return false;
    }
    setTensorMemHandle(exec, m.mem);
    return true;
  };
  for (size_t i = 0; i < e->inputs.size(); ++i)
    if (!bind(e->inputs[i], e->inMeta[i], e->inputs[i])) return false;
  for (size_t i = 0; i < e->outputs.size(); ++i)
    if (!bind(e->outputs[i], e->outMeta[i], e->outputs[i])) return false;
  LOGI("ION I/O ready (%zu in, %zu out)", e->inputs.size(), e->outputs.size());
  return true;
}

}  // namespace

extern "C" {

// backendLibDir = the app's nativeLibraryDir (holds libQnnHtp.so etc).
// ctxPath       = the prebuilt context binary (enc_e2e_ctx.bin)
// melFb         = float[64*161] filterbank from assets.
JNIEXPORT jlong JNICALL
Java_com_repository_glasses_capture_GigaAmNative_nativeInit(
    JNIEnv* env, jclass, jstring jBackendDir, jstring jCtxPath, jfloatArray jMelFb) {
  const char* bd = env->GetStringUTFChars(jBackendDir, nullptr);
  const char* cp = env->GetStringUTFChars(jCtxPath, nullptr);
  std::string backendDir(bd), ctxPath(cp);
  env->ReleaseStringUTFChars(jBackendDir, bd);
  env->ReleaseStringUTFChars(jCtxPath, cp);

  // SEMICOLON separated -- a colon list silently yields "skel not found".
  std::string adsp = backendDir + ";/vendor/dsp/cdsp;/vendor/lib/rfsa/adsp";
  setenv("ADSP_LIBRARY_PATH", adsp.c_str(), 1);

  void* cdsp = dlopen("libcdsprpc.so", RTLD_NOW | RTLD_GLOBAL);
  if (cdsp) LOGI("pre-loaded libcdsprpc.so");
  else LOGW("pre-load libcdsprpc.so failed: %s", dlerror());

  auto* e = new EncEngine();
  e->cdspLib = cdsp;
  if (cdsp) {
    e->rpcAlloc = reinterpret_cast<RpcMemAllocFn_t>(dlsym(cdsp, "rpcmem_alloc"));
    e->rpcFree = reinterpret_cast<RpcMemFreeFn_t>(dlsym(cdsp, "rpcmem_free"));
    e->rpcToFd = reinterpret_cast<RpcMemToFdFn_t>(dlsym(cdsp, "rpcmem_to_fd"));
  }

  std::string htp = backendDir + "/libQnnHtp.so";
  std::string sysp = backendDir + "/libQnnSystem.so";
  e->backendLib = dlopen(htp.c_str(), RTLD_NOW | RTLD_LOCAL);
  if (!e->backendLib) { LOGE("dlopen %s: %s", htp.c_str(), dlerror()); delete e; return 0; }
  e->systemLib = dlopen(sysp.c_str(), RTLD_NOW | RTLD_LOCAL);
  if (!e->systemLib) { LOGE("dlopen %s: %s", sysp.c_str(), dlerror()); delete e; return 0; }
  if (!resolveBackendInterface(e->backendLib, &e->qnn) ||
      !resolveSystemInterface(e->systemLib, &e->sys)) { delete e; return 0; }

  Qnn_LogHandle_t lh = nullptr;
  if (e->qnn.logCreate(logCb, QNN_LOG_LEVEL_WARN, &lh) != QNN_SUCCESS) lh = nullptr;
  if (e->qnn.backendCreate(lh, nullptr, &e->backend) != QNN_SUCCESS || !e->backend) {
    LOGE("backendCreate failed"); delete e; return 0;
  }

  QnnHtpDevice_CustomConfig_t soc; memset(&soc, 0, sizeof(soc));
  soc.option = QNN_HTP_DEVICE_CONFIG_OPTION_SOC; soc.socModel = kSocModel;
  QnnHtpDevice_CustomConfig_t arch; memset(&arch, 0, sizeof(arch));
  arch.option = QNN_HTP_DEVICE_CONFIG_OPTION_ARCH;
  arch.arch.deviceId = 0; arch.arch.arch = QNN_HTP_DEVICE_ARCH_V73;
  QnnHtpDevice_CustomConfig_t pd; memset(&pd, 0, sizeof(pd));
  pd.option = QNN_HTP_DEVICE_CONFIG_OPTION_SIGNEDPD;
  pd.useSignedProcessDomain.deviceId = 0;
  pd.useSignedProcessDomain.useSignedProcessDomain = false;  // unsigned PD only
  QnnDevice_Config_t cSoc{}, cArch{}, cPd{};
  cSoc.option = QNN_DEVICE_CONFIG_OPTION_CUSTOM; cSoc.customConfig = &soc;
  cArch.option = QNN_DEVICE_CONFIG_OPTION_CUSTOM; cArch.customConfig = &arch;
  cPd.option = QNN_DEVICE_CONFIG_OPTION_CUSTOM; cPd.customConfig = &pd;
  const QnnDevice_Config_t* devCfgs[] = {&cSoc, &cArch, &cPd, nullptr};
  Qnn_ErrorHandle_t derr = e->qnn.deviceCreate(lh, devCfgs, &e->device);
  if (derr != QNN_SUCCESS) {
    LOGW("deviceCreate(full) err=0x%llx; retry PD-only", (unsigned long long)derr);
    const QnnDevice_Config_t* pdOnly[] = {&cPd, nullptr};
    if (e->qnn.deviceCreate(lh, pdOnly, &e->device) != QNN_SUCCESS) e->device = nullptr;
  }
  if (e->device) LOGI("QnnDevice created (unsigned PD)");

  struct timespec t0, t1;
  clock_gettime(CLOCK_MONOTONIC, &t0);
  std::vector<uint8_t> bin;
  if (!readFile(ctxPath, bin) || bin.empty()) {
    LOGE("cannot read context binary %s", ctxPath.c_str()); delete e; return 0;
  }
  LOGI("context binary %zu bytes", bin.size());

  Qnn_ErrorHandle_t cerr = e->qnn.contextCreateFromBinary(
      e->backend, e->device, nullptr, bin.data(), bin.size(), &e->context, nullptr);
  if (cerr != QNN_SUCCESS || !e->context) {
    LOGE("contextCreateFromBinary err=0x%llx", (unsigned long long)cerr); delete e; return 0;
  }
  if (!extractGraphIO(e, bin)) { delete e; return 0; }
  bin.clear(); bin.shrink_to_fit();  // release the 221 MB immediately

  const char* gn = e->graphName.empty() ? "gigaam_enc" : e->graphName.c_str();
  if (e->qnn.graphRetrieve(e->context, gn, &e->graph) != QNN_SUCCESS || !e->graph) {
    LOGE("graphRetrieve(%s) failed", gn); delete e; return 0;
  }
  if (!setupIonIo(e)) { delete e; return 0; }
  clock_gettime(CLOCK_MONOTONIC, &t1);
  long loadMs = (t1.tv_sec - t0.tv_sec) * 1000 + (t1.tv_nsec - t0.tv_nsec) / 1000000;
  LOGI("encoder ready in %ld ms (graph=%s)", loadMs, gn);

  // --- Feature front-end tables ---------------------------------------------
  jsize n = env->GetArrayLength(jMelFb);
  if (n != kMel * kBins) { LOGE("melfb size %d != %d", (int)n, kMel * kBins); delete e; return 0; }
  e->melFb.resize(n);
  env->GetFloatArrayRegion(jMelFb, 0, n, e->melFb.data());

  e->window.resize(kNFft);
  for (int i = 0; i < kNFft; ++i)  // PERIODIC hann (denominator N, not N-1)
    e->window[i] = 0.5f - 0.5f * cosf(2.0f * (float)M_PI * (float)i / (float)kNFft);

  e->cosTab.resize((size_t)kBins * kNFft);
  e->sinTab.resize((size_t)kBins * kNFft);
  for (int k = 0; k < kBins; ++k)
    for (int t = 0; t < kNFft; ++t) {
      double a = -2.0 * M_PI * (double)k * (double)t / (double)kNFft;
      e->cosTab[(size_t)k * kNFft + t] = (float)cos(a);
      e->sinTab[(size_t)k * kNFft + t] = (float)sin(a);
    }
  e->frameBuf.resize(kNFft);
  e->powBuf.resize(kBins);

  return reinterpret_cast<jlong>(e);
}

// Compute log-mel [64,500] for `pcm` (float32 mono 16k), zero-padded/truncated
// to the 5 s window, write into the encoder input ION buffer, run the graph,
// and return {encoded[768*125], encodedLen, featMs, execMs}.
// Returns float[768*125 + 3]: encoded, then encodedLen, featMs, execMs.
JNIEXPORT jfloatArray JNICALL
Java_com_repository_glasses_capture_GigaAmNative_nativeEncode(
    JNIEnv* env, jclass, jlong handle, jfloatArray jPcm) {
  auto* e = reinterpret_cast<EncEngine*>(handle);
  if (!e || e->idxAudio < 0) return nullptr;

  jsize nSamp = env->GetArrayLength(jPcm);
  std::vector<float> pcm((size_t)nSamp);
  env->GetFloatArrayRegion(jPcm, 0, nSamp, pcm.data());

  struct timespec t0, t1, t2;
  clock_gettime(CLOCK_MONOTONIC, &t0);

  // center=false: nFrames = floor((n - win)/hop) + 1, capped at 500.
  int nFrames = 0;
  if (nSamp >= kNFft) nFrames = (int)((nSamp - kNFft) / kHop) + 1;
  if (nFrames > kFrames) nFrames = kFrames;
  if (nFrames < 1) nFrames = 1;

  const IoTensor& audioMeta = e->inMeta[e->idxAudio];
  // [1,64,500] layout: mel-major, so index = mel*500 + frame. Pad the tail with
  // log(1e-9) = the value a silent frame produces, matching the reference which
  // pads the WAVEFORM with zeros.
  const float kLogFloor = logf(1e-9f);
  for (int m = 0; m < kMel; ++m)
    for (int t = nFrames; t < kFrames; ++t)
      writeQuant(audioMeta, (size_t)m * kFrames + t, kLogFloor);

  for (int t = 0; t < nFrames; ++t) {
    const size_t off = (size_t)t * kHop;
    for (int i = 0; i < kNFft; ++i) {
      float s = (off + i < (size_t)nSamp) ? pcm[off + i] : 0.0f;
      e->frameBuf[i] = s * e->window[i];
    }
    // Direct real DFT: n_fft=320 is not a power of two; 161x320 is cheap enough
    // (~52k MACs/frame, ~26 MMAC for a full 5 s window) and bit-matches the ref.
    for (int k = 0; k < kBins; ++k) {
      const float* ct = &e->cosTab[(size_t)k * kNFft];
      const float* st = &e->sinTab[(size_t)k * kNFft];
      float re = 0.f, im = 0.f;
      for (int i = 0; i < kNFft; ++i) { re += e->frameBuf[i] * ct[i]; im += e->frameBuf[i] * st[i]; }
      e->powBuf[k] = re * re + im * im;   // power 2.0
    }
    for (int m = 0; m < kMel; ++m) {
      const float* fb = &e->melFb[(size_t)m * kBins];
      float acc = 0.f;
      for (int k = 0; k < kBins; ++k) acc += fb[k] * e->powBuf[k];
      if (acc < 1e-9f) acc = 1e-9f; else if (acc > 1e9f) acc = 1e9f;
      // NO normalisation of any kind -- the reference applies none.
      writeQuant(audioMeta, (size_t)m * kFrames + t, logf(acc));
    }
  }
  clock_gettime(CLOCK_MONOTONIC, &t1);

  // `length` is the SAMPLE count and stays float32 in this binary (only the
  // activations are 16-bit quantized), but route it through writeQuant anyway
  // so it stays correct if the binary is ever requantized.
  int usedSamples = std::min<int>(nSamp, kFrames * kHop + kNFft - kHop);
  writeQuant(e->inMeta[e->idxLength], 0, (float)usedSamples);

  Qnn_ErrorHandle_t err = e->qnn.graphExecute(
      e->graph, e->inputs.data(), (uint32_t)e->inputs.size(),
      e->outputs.data(), (uint32_t)e->outputs.size(), nullptr, nullptr);
  clock_gettime(CLOCK_MONOTONIC, &t2);
  if (err != QNN_SUCCESS) {
    LOGE("graphExecute err=0x%llx", (unsigned long long)err);
    return nullptr;
  }

  float featMs = (t1.tv_sec - t0.tv_sec) * 1e3f + (t1.tv_nsec - t0.tv_nsec) / 1e6f;
  float execMs = (t2.tv_sec - t1.tv_sec) * 1e3f + (t2.tv_nsec - t1.tv_nsec) / 1e6f;

  // encoded_len is written as FLOAT32 by this graph (not int32) -- a previous
  // agent lost time to reading it as int. Prefer the arithmetic value anyway:
  // the quantized pre_encode length path is unreliable.
  int encLen = nFrames / 4;
  if (encLen < 1) encLen = 1;
  if (encLen > kEncFrames) encLen = kEncFrames;

  // `encoded` is UFIXED_POINT_16 -- dequantize before handing it to the CPU
  // joint network, which expects real-valued floats.
  const IoTensor& encMeta = e->outMeta[e->idxEncoded];
  const int total = kEncDim * kEncFrames + 3;
  std::vector<float> enc((size_t)kEncDim * kEncFrames);
  for (size_t i = 0; i < enc.size(); ++i) enc[i] = readQuant(encMeta, i);

  jfloatArray out = env->NewFloatArray(total);
  if (!out) return nullptr;
  env->SetFloatArrayRegion(out, 0, kEncDim * kEncFrames, enc.data());
  float tail[3] = {(float)encLen, featMs, execMs};
  env->SetFloatArrayRegion(out, kEncDim * kEncFrames, 3, tail);
  return out;
}

// Self-test: run the encoder on a PRECOMPUTED [64,500] log-mel block (as written
// by prep_data.py), bypassing the C++ front end. Isolates encoder+decoder
// correctness from mic capture. Same return layout as nativeEncode.
JNIEXPORT jfloatArray JNICALL
Java_com_repository_glasses_capture_GigaAmNative_nativeEncodeFeats(
    JNIEnv* env, jclass, jlong handle, jfloatArray jFeats, jint frames) {
  auto* e = reinterpret_cast<EncEngine*>(handle);
  if (!e || e->idxAudio < 0) return nullptr;

  jsize n = env->GetArrayLength(jFeats);
  if (n != kMel * kFrames) { LOGE("feats %d != %d", (int)n, kMel * kFrames); return nullptr; }
  std::vector<float> feats((size_t)n);
  env->GetFloatArrayRegion(jFeats, 0, n, feats.data());

  const IoTensor& audioMeta = e->inMeta[e->idxAudio];
  for (size_t i = 0; i < feats.size(); ++i) writeQuant(audioMeta, i, feats[i]);

  int nFrames = frames > kFrames ? kFrames : (frames < 1 ? 1 : frames);
  writeQuant(e->inMeta[e->idxLength], 0, (float)(nFrames * kHop + kNFft - kHop));

  struct timespec t0, t1;
  clock_gettime(CLOCK_MONOTONIC, &t0);
  Qnn_ErrorHandle_t err = e->qnn.graphExecute(
      e->graph, e->inputs.data(), (uint32_t)e->inputs.size(),
      e->outputs.data(), (uint32_t)e->outputs.size(), nullptr, nullptr);
  clock_gettime(CLOCK_MONOTONIC, &t1);
  if (err != QNN_SUCCESS) { LOGE("graphExecute err=0x%llx", (unsigned long long)err); return nullptr; }
  float execMs = (t1.tv_sec - t0.tv_sec) * 1e3f + (t1.tv_nsec - t0.tv_nsec) / 1e6f;

  int encLen = nFrames / 4;
  if (encLen < 1) encLen = 1;
  if (encLen > kEncFrames) encLen = kEncFrames;

  const IoTensor& encMeta = e->outMeta[e->idxEncoded];
  std::vector<float> enc((size_t)kEncDim * kEncFrames);
  for (size_t i = 0; i < enc.size(); ++i) enc[i] = readQuant(encMeta, i);

  jfloatArray out = env->NewFloatArray(kEncDim * kEncFrames + 3);
  if (!out) return nullptr;
  env->SetFloatArrayRegion(out, 0, kEncDim * kEncFrames, enc.data());
  float tail[3] = {(float)encLen, 0.f, execMs};
  env->SetFloatArrayRegion(out, kEncDim * kEncFrames, 3, tail);
  return out;
}

JNIEXPORT void JNICALL
Java_com_repository_glasses_capture_GigaAmNative_nativeClose(JNIEnv*, jclass, jlong handle) {
  auto* e = reinterpret_cast<EncEngine*>(handle);
  if (e) delete e;
}

}  // extern "C"
