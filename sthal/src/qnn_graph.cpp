// sthal/src/qnn_graph.cpp
//
// Implementation of the QnnGraph wrapper. Uses QAIRT SDK 2.x APIs:
//   QnnContext_createFromBinary, QnnGraph_retrieve, QnnGraph_execute,
//   QnnSystemContext_create / _getBinaryInfo / _free.
//
// Tensor metadata extraction: for each stage binary, we create a one-shot
// QnnSystemContext handle, parse the binary via QnnSystemContext_getBinaryInfo
// (supports V1/V2/V3 BinaryInfo and V1/V2/V3 GraphInfo), pick the graph whose
// name matches the stage (or fall back to graph[0]), deep-copy the input and
// output Qnn_Tensor_t descriptors, allocate per-tensor raw-byte buffers, and
// patch each descriptor's clientBuf.data to point at our owned buffer.
//
// At execute time we rebuild the two Qnn_Tensor_t arrays from our slots (the
// cheap bit is that clientBuf pointers + dims pointers stay stable across
// calls) and pass them to QnnGraph_execute.

#include "qnn_graph.h"

#include <algorithm>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <vector>

#include <android/log.h>

#define LOG_TAG "sthal.qnn.graph"
#define ALOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define ALOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace sthal {

namespace {

// Read a binary file into a byte vector. Returns true on success.
bool readBinary(const std::string& path, std::vector<uint8_t>& out) {
    FILE* f = std::fopen(path.c_str(), "rb");
    if (!f) return false;
    std::fseek(f, 0, SEEK_END);
    long sz = std::ftell(f);
    std::fseek(f, 0, SEEK_SET);
    if (sz <= 0) { std::fclose(f); return false; }
    out.resize(static_cast<size_t>(sz));
    size_t got = std::fread(out.data(), 1, out.size(), f);
    std::fclose(f);
    return got == out.size();
}

// Accessor wrappers: pick the right union member off a Qnn_Tensor_t.
const char* tName(const Qnn_Tensor_t& t) {
    return (t.version == QNN_TENSOR_VERSION_2) ? t.v2.name : t.v1.name;
}
Qnn_DataType_t tDataType(const Qnn_Tensor_t& t) {
    return (t.version == QNN_TENSOR_VERSION_2) ? t.v2.dataType : t.v1.dataType;
}
uint32_t tRank(const Qnn_Tensor_t& t) {
    return (t.version == QNN_TENSOR_VERSION_2) ? t.v2.rank : t.v1.rank;
}
const uint32_t* tDims(const Qnn_Tensor_t& t) {
    return (t.version == QNN_TENSOR_VERSION_2) ? t.v2.dimensions : t.v1.dimensions;
}

// Extract the I/O tensor arrays from any GraphInfo version.
void pickGraphIO(const QnnSystemContext_GraphInfo_t& gi,
                 const char** outName,
                 uint32_t* numIn,  const Qnn_Tensor_t** inArr,
                 uint32_t* numOut, const Qnn_Tensor_t** outArr) {
    switch (gi.version) {
        case QNN_SYSTEM_CONTEXT_GRAPH_INFO_VERSION_1:
            *outName = gi.graphInfoV1.graphName;
            *numIn   = gi.graphInfoV1.numGraphInputs;
            *inArr   = gi.graphInfoV1.graphInputs;
            *numOut  = gi.graphInfoV1.numGraphOutputs;
            *outArr  = gi.graphInfoV1.graphOutputs;
            break;
        case QNN_SYSTEM_CONTEXT_GRAPH_INFO_VERSION_2:
            *outName = gi.graphInfoV2.graphName;
            *numIn   = gi.graphInfoV2.numGraphInputs;
            *inArr   = gi.graphInfoV2.graphInputs;
            *numOut  = gi.graphInfoV2.numGraphOutputs;
            *outArr  = gi.graphInfoV2.graphOutputs;
            break;
        case QNN_SYSTEM_CONTEXT_GRAPH_INFO_VERSION_3:
            *outName = gi.graphInfoV3.graphName;
            *numIn   = gi.graphInfoV3.numGraphInputs;
            *inArr   = gi.graphInfoV3.graphInputs;
            *numOut  = gi.graphInfoV3.numGraphOutputs;
            *outArr  = gi.graphInfoV3.graphOutputs;
            break;
        default:
            *outName = nullptr;
            *numIn = 0; *inArr = nullptr;
            *numOut = 0; *outArr = nullptr;
            break;
    }
}

// Extract graph-list fields from any BinaryInfo version.
void pickBinaryGraphs(const QnnSystemContext_BinaryInfo_t& bi,
                      uint32_t* numGraphs,
                      const QnnSystemContext_GraphInfo_t** graphs) {
    switch (bi.version) {
        case QNN_SYSTEM_CONTEXT_BINARY_INFO_VERSION_1:
            *numGraphs = bi.contextBinaryInfoV1.numGraphs;
            *graphs    = bi.contextBinaryInfoV1.graphs;
            break;
        case QNN_SYSTEM_CONTEXT_BINARY_INFO_VERSION_2:
            *numGraphs = bi.contextBinaryInfoV2.numGraphs;
            *graphs    = bi.contextBinaryInfoV2.graphs;
            break;
        case QNN_SYSTEM_CONTEXT_BINARY_INFO_VERSION_3:
            *numGraphs = bi.contextBinaryInfoV3.numGraphs;
            *graphs    = bi.contextBinaryInfoV3.graphs;
            break;
        default:
            *numGraphs = 0;
            *graphs    = nullptr;
            break;
    }
}

} // namespace

// -----------------------------------------------------------------------------
// Public helpers
// -----------------------------------------------------------------------------

size_t qnnDataTypeBytes(Qnn_DataType_t dt) {
    switch (dt) {
        // Float
        case QNN_DATATYPE_FLOAT_16: return 2;
        case QNN_DATATYPE_FLOAT_32: return 4;
        case QNN_DATATYPE_FLOAT_64: return 8;
        // Int
        case QNN_DATATYPE_INT_8:    return 1;
        case QNN_DATATYPE_INT_16:   return 2;
        case QNN_DATATYPE_INT_32:   return 4;
        case QNN_DATATYPE_INT_64:   return 8;
        case QNN_DATATYPE_UINT_8:   return 1;
        case QNN_DATATYPE_UINT_16:  return 2;
        case QNN_DATATYPE_UINT_32:  return 4;
        case QNN_DATATYPE_UINT_64:  return 8;
        case QNN_DATATYPE_BOOL_8:   return 1;
        // Symmetric / unsigned fixed-point
        case QNN_DATATYPE_SFIXED_POINT_8:  return 1;
        case QNN_DATATYPE_SFIXED_POINT_16: return 2;
        case QNN_DATATYPE_SFIXED_POINT_32: return 4;
        case QNN_DATATYPE_UFIXED_POINT_8:  return 1;
        case QNN_DATATYPE_UFIXED_POINT_16: return 2;
        case QNN_DATATYPE_UFIXED_POINT_32: return 4;
        default:
            return 0;
    }
}

// -----------------------------------------------------------------------------
// QnnGraph
// -----------------------------------------------------------------------------

QnnGraph::~QnnGraph() {
    if (api_ && context_ && api_->core && api_->core->contextFree) {
        api_->core->contextFree(context_, /*profile=*/nullptr);
    }
    context_ = nullptr;
    graph_   = nullptr;
}

bool QnnGraph::cloneTensor(const Qnn_Tensor_t& src, Slot& dst, bool isInput) {
    const char* srcName = tName(src);
    Qnn_DataType_t  dt  = tDataType(src);
    uint32_t        rnk = tRank(src);
    const uint32_t* ds  = tDims(src);
    if (rnk == 0 || !ds) {
        ALOGE("cloneTensor: rank=%u or dims=null not supported for %s",
              rnk, srcName ? srcName : "<null>");
        return false;
    }
    size_t elemBytes = qnnDataTypeBytes(dt);
    if (elemBytes == 0) {
        ALOGE("cloneTensor: unsupported dataType=%d for %s",
              static_cast<int>(dt), srcName ? srcName : "<null>");
        return false;
    }
    size_t elemCount = 1;
    dst.dims.assign(ds, ds + rnk);
    for (uint32_t i = 0; i < rnk; ++i) {
        if (dst.dims[i] == 0) {
            ALOGE("cloneTensor: zero dim[%u] for %s", i, srcName ? srcName : "<null>");
            return false;
        }
        elemCount *= static_cast<size_t>(dst.dims[i]);
    }
    dst.elemCount = elemCount;
    dst.elemBytes = elemBytes;
    dst.buf.assign(elemCount * elemBytes, 0);
    dst.name = srcName ? srcName : "";

    // Build the V2 descriptor we'll hand back to QnnGraph_execute. Id comes
    // from the backend at QnnContext_createFromBinary time via v2.id -- we
    // preserve it for v2 inputs/outputs (required to match the retrieved
    // graph). For V1 descriptors we carry the id over as well.
    std::memset(&dst.tensor, 0, sizeof(dst.tensor));
    dst.tensor.version = QNN_TENSOR_VERSION_2;
    Qnn_TensorV2_t& t  = dst.tensor.v2;
    if (src.version == QNN_TENSOR_VERSION_2) {
        t = src.v2; // shallow copy; we overwrite ptr fields below
    } else {
        t.id         = src.v1.id;
        t.type       = src.v1.type;
        t.dataFormat = src.v1.dataFormat;
        t.dataType   = src.v1.dataType;
        t.rank       = src.v1.rank;
    }
    t.name        = dst.name.c_str();
    t.rank        = rnk;
    t.dimensions  = dst.dims.data();
    t.memType     = QNN_TENSORMEMTYPE_RAW;
    t.clientBuf.data     = dst.buf.data();
    t.clientBuf.dataSize = static_cast<uint32_t>(dst.buf.size());
    // APP_WRITE/APP_READ is preserved from the source; fall back if undefined.
    if (t.type != QNN_TENSOR_TYPE_APP_WRITE &&
        t.type != QNN_TENSOR_TYPE_APP_READ  &&
        t.type != QNN_TENSOR_TYPE_APP_READWRITE) {
        t.type = isInput ? QNN_TENSOR_TYPE_APP_WRITE : QNN_TENSOR_TYPE_APP_READ;
    }
    // We don't use dynamic dims / sparse / retrieveRaw.
    t.isDynamicDimensions = nullptr;
    // Zero-initialised sparseParams from src copy is fine.
    return true;
}

bool QnnGraph::load(const QnnApi& api,
                    Qnn_BackendHandle_t backend,
                    Qnn_DeviceHandle_t  device,
                    const std::string&  path,
                    const std::string&  preferredGraphName) {
    api_     = &api;
    backend_ = backend;
    device_  = device;

    if (!api.core || !api.system) {
        ALOGE("load(%s): QNN api not resolved", preferredGraphName.c_str());
        return false;
    }

    std::vector<uint8_t> blob;
    if (!readBinary(path, blob)) {
        ALOGE("load: cannot read %s", path.c_str());
        return false;
    }

    // --- 1. Parse metadata via a throwaway SystemContext handle. ------------
    QnnSystemContext_Handle_t sysCtx = nullptr;
    if (api.system->systemContextCreate(&sysCtx) != QNN_SUCCESS || !sysCtx) {
        ALOGE("load(%s): QnnSystemContext_create failed", preferredGraphName.c_str());
        return false;
    }

    const QnnSystemContext_BinaryInfo_t* binInfo = nullptr;
    Qnn_ContextBinarySize_t               binInfoSize = 0;
    Qnn_ErrorHandle_t eRc = api.system->systemContextGetBinaryInfo(
        sysCtx, blob.data(), blob.size(), &binInfo, &binInfoSize);
    if (eRc != QNN_SUCCESS || !binInfo) {
        ALOGE("load(%s): QnnSystemContext_getBinaryInfo failed rc=0x%lx",
              preferredGraphName.c_str(), (unsigned long)eRc);
        api.system->systemContextFree(sysCtx);
        return false;
    }

    uint32_t numGraphs = 0;
    const QnnSystemContext_GraphInfo_t* graphs = nullptr;
    pickBinaryGraphs(*binInfo, &numGraphs, &graphs);
    if (numGraphs == 0 || !graphs) {
        ALOGE("load(%s): binary has no graphs", preferredGraphName.c_str());
        api.system->systemContextFree(sysCtx);
        return false;
    }

    // Pick the graph matching preferredGraphName; fall back to index 0.
    uint32_t pick = 0;
    bool     named = false;
    for (uint32_t i = 0; i < numGraphs; ++i) {
        const char* gn = nullptr;
        uint32_t ni=0, no=0;
        const Qnn_Tensor_t* ii=nullptr; const Qnn_Tensor_t* oo=nullptr;
        pickGraphIO(graphs[i], &gn, &ni, &ii, &no, &oo);
        if (gn && preferredGraphName == gn) {
            pick = i;
            named = true;
            break;
        }
    }

    const char* chosenName = nullptr;
    uint32_t numIn = 0, numOut = 0;
    const Qnn_Tensor_t* inArr  = nullptr;
    const Qnn_Tensor_t* outArr = nullptr;
    pickGraphIO(graphs[pick], &chosenName, &numIn, &inArr, &numOut, &outArr);
    graphName_ = chosenName ? chosenName : preferredGraphName;
    if (!named) {
        ALOGW("load: stage binary %s advertises graph '%s' (expected '%s'); using graph[0]",
              path.c_str(), graphName_.c_str(), preferredGraphName.c_str());
    }
    ALOGI("load: stage='%s' graph='%s' numIn=%u numOut=%u size=%zu",
          preferredGraphName.c_str(), graphName_.c_str(), numIn, numOut, blob.size());

    // --- 2. Clone the tensor metadata into owned Slots. ---------------------
    inputs_.resize(numIn);
    outputs_.resize(numOut);
    for (uint32_t i = 0; i < numIn; ++i) {
        if (!cloneTensor(inArr[i], inputs_[i], /*isInput=*/true)) {
            ALOGE("load(%s): cloneTensor[in %u] failed", graphName_.c_str(), i);
            api.system->systemContextFree(sysCtx);
            return false;
        }
        const auto& s = inputs_[i];
        ALOGI("  in [%u] name='%s' rank=%zu bytes=%zu",
              i, s.name.c_str(), s.dims.size(), s.buf.size());
    }
    for (uint32_t i = 0; i < numOut; ++i) {
        if (!cloneTensor(outArr[i], outputs_[i], /*isInput=*/false)) {
            ALOGE("load(%s): cloneTensor[out %u] failed", graphName_.c_str(), i);
            api.system->systemContextFree(sysCtx);
            return false;
        }
        const auto& s = outputs_[i];
        ALOGI("  out[%u] name='%s' rank=%zu bytes=%zu",
              i, s.name.c_str(), s.dims.size(), s.buf.size());
    }

    // Free the system-context scratch handle; we no longer need binInfo.
    api.system->systemContextFree(sysCtx);
    sysCtx  = nullptr;
    binInfo = nullptr;

    // --- 3. Create real context from binary, retrieve the graph handle. -----
    Qnn_ContextHandle_t ctx = nullptr;
    Qnn_ErrorHandle_t cRc = api.core->contextCreateFromBinary(
        backend_, device_,
        /*config=*/nullptr,
        blob.data(), static_cast<Qnn_ContextBinarySize_t>(blob.size()),
        &ctx,
        /*profile=*/nullptr);
    if (cRc != QNN_SUCCESS || !ctx) {
        ALOGE("load(%s): QnnContext_createFromBinary failed rc=0x%lx",
              graphName_.c_str(), (unsigned long)cRc);
        return false;
    }
    context_ = ctx;

    Qnn_GraphHandle_t g = nullptr;
    Qnn_ErrorHandle_t gRc = api.core->graphRetrieve(context_, graphName_.c_str(), &g);
    if (gRc != QNN_SUCCESS || !g) {
        ALOGE("load(%s): QnnGraph_retrieve failed rc=0x%lx",
              graphName_.c_str(), (unsigned long)gRc);
        return false;
    }
    graph_ = g;

    // --- 4. Snapshot the execute() I/O arrays. Pointers inside each Slot's
    //        tensor stay stable until destruction, so these arrays remain
    //        valid across every execute().
    inputTensors_.resize(inputs_.size());
    outputTensors_.resize(outputs_.size());
    for (size_t i = 0; i < inputs_.size();  ++i) inputTensors_[i]  = inputs_[i].tensor;
    for (size_t i = 0; i < outputs_.size(); ++i) outputTensors_[i] = outputs_[i].tensor;

    return true;
}

bool QnnGraph::execute() {
    if (!graph_ || !api_ || !api_->core || !api_->core->graphExecute) return false;
    // Re-sync buffer pointers from slots in case anyone resized (we don't,
    // but cheap to be safe).
    for (size_t i = 0; i < inputs_.size();  ++i) inputTensors_[i]  = inputs_[i].tensor;
    for (size_t i = 0; i < outputs_.size(); ++i) outputTensors_[i] = outputs_[i].tensor;
    Qnn_ErrorHandle_t rc = api_->core->graphExecute(
        graph_,
        inputTensors_.data(),  static_cast<uint32_t>(inputTensors_.size()),
        outputTensors_.data(), static_cast<uint32_t>(outputTensors_.size()),
        /*profile=*/nullptr, /*signal=*/nullptr);
    if (rc != QNN_SUCCESS) {
        // Caller rate-limits the log; keep this verbose once.
        return false;
    }
    return true;
}

float* QnnGraph::inputData(size_t i) {
    if (i >= inputs_.size()) return nullptr;
    return reinterpret_cast<float*>(inputs_[i].buf.data());
}

const float* QnnGraph::outputData(size_t i) const {
    if (i >= outputs_.size()) return nullptr;
    return reinterpret_cast<const float*>(outputs_[i].buf.data());
}

size_t QnnGraph::inputBytes(size_t i) const {
    return (i < inputs_.size()) ? inputs_[i].buf.size() : 0;
}
size_t QnnGraph::outputBytes(size_t i) const {
    return (i < outputs_.size()) ? outputs_[i].buf.size() : 0;
}
size_t QnnGraph::inputElements(size_t i) const {
    return (i < inputs_.size()) ? inputs_[i].elemCount : 0;
}
size_t QnnGraph::outputElements(size_t i) const {
    return (i < outputs_.size()) ? outputs_[i].elemCount : 0;
}

} // namespace sthal
