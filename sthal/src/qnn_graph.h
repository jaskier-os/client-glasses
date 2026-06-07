// sthal/src/qnn_graph.h
//
// Thin C++ wrapper around a single QNN pre-compiled context binary:
//  - Loads the .bin via QnnContext_createFromBinary
//  - Parses binary metadata via QnnSystemContext_getBinaryInfo to discover
//    the graph name and I/O tensor layouts
//  - Retrieves the graph handle
//  - Allocates APP-RAW client buffers matching each input/output tensor
//  - Exposes execute(): caller writes into inputData()/inputBytes() of the
//    right index, calls execute(), reads outputData()/outputBytes().
//
// This header intentionally includes the QNN SDK headers -- it is an
// implementation-private translation unit (src/, not include/). Public HAL
// headers (include/sound_trigger_hw.h, src/qnn_runtime.h) stay free of any
// QNN type references.
//
// Thread model: not thread-safe. QnnHtpRuntimeImpl serializes all calls on
// the mic thread under its mutex.

#pragma once

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

// QNN SDK headers. These require QNN_SDK_ROOT/include/QNN on the include path
// (wired up by CMake via target_include_directories + $ENV{QNN_SDK_ROOT}).
#include <QnnCommon.h>
#include <QnnContext.h>
#include <QnnGraph.h>
#include <QnnInterface.h>
#include <QnnTensor.h>
#include <QnnTypes.h>
#include <System/QnnSystemContext.h>
#include <System/QnnSystemInterface.h>

namespace sthal {

// Resolved function-pointer table for the QNN core + system providers, after
// dlopen/dlsym on libQnnHtp.so + libQnnSystem.so. Shared across all loaded
// QnnGraph instances within one QnnHtpRuntime.
struct QnnApi {
    // Core backend function table. Pointer into the provider library's
    // static storage -- do not free.
    const QNN_INTERFACE_VER_TYPE*        core   = nullptr;
    // System function table.
    const QNN_SYSTEM_INTERFACE_VER_TYPE* system = nullptr;
};

// One loaded pre-compiled QNN context binary + its primary graph.
//
// Per-graph lifetime: load() allocates QnnContext + QnnGraph + a
// QnnSystemContext scratch handle for binary parsing; destructor frees all.
// Tensor buffers are sized once at load and reused per execute().
class QnnGraph {
public:
    QnnGraph() = default;
    ~QnnGraph();

    QnnGraph(const QnnGraph&)            = delete;
    QnnGraph& operator=(const QnnGraph&) = delete;
    QnnGraph(QnnGraph&&)                 = delete;
    QnnGraph& operator=(QnnGraph&&)      = delete;

    // Load a pre-compiled context binary from disk. `preferredGraphName` is
    // the expected stage graph name (e.g. "melspectrogram"). If the binary
    // advertises a different name, we fall back to the first graph in the
    // binary and log a warning. Returns true on success.
    bool load(const QnnApi& api,
              Qnn_BackendHandle_t backend,
              Qnn_DeviceHandle_t  device,
              const std::string&  path,
              const std::string&  preferredGraphName);

    // Execute the graph with the currently-populated input buffers.
    // Returns true on QNN_SUCCESS.
    bool execute();

    // Accessors for tensor buffers. All indices refer to the order reported
    // by QnnSystemContext_getBinaryInfo, which matches the order baked into
    // the pre-compiled binary.
    size_t numInputs()  const { return inputs_.size();  }
    size_t numOutputs() const { return outputs_.size(); }

    float*       inputData (size_t i);
    const float* outputData(size_t i) const;
    size_t       inputBytes (size_t i) const;
    size_t       outputBytes(size_t i) const;
    size_t       inputElements (size_t i) const;
    size_t       outputElements(size_t i) const;

    const std::string& name() const { return graphName_; }
    bool valid() const { return graph_ != nullptr; }

private:
    // One tensor's mutable state: a copy of the V2 descriptor (so we own the
    // .dimensions array + name C-string lifetime), plus a heap-allocated
    // data buffer referenced by clientBuf.data.
    struct Slot {
        Qnn_Tensor_t          tensor;    // version = V2; clientBuf.data = buf.data()
        std::vector<uint32_t> dims;      // backs tensor.v2.dimensions
        std::string           name;      // backs tensor.v2.name
        std::vector<uint8_t>  buf;       // raw bytes; size = dtypeBytes * prod(dims)
        size_t                elemCount = 0;
        size_t                elemBytes = 0;
    };

    // Deep-copy a Qnn_Tensor_t descriptor from QnnSystemContext into a Slot,
    // allocate its buffer, and patch the clientBuf pointer. Returns false if
    // the tensor's rank / dtype aren't something we can handle.
    bool cloneTensor(const Qnn_Tensor_t& src, Slot& dst, bool isInput);

    const QnnApi*             api_     = nullptr;
    Qnn_BackendHandle_t       backend_ = nullptr;
    Qnn_DeviceHandle_t        device_  = nullptr;
    Qnn_ContextHandle_t       context_ = nullptr;
    Qnn_GraphHandle_t         graph_   = nullptr;
    std::string               graphName_;
    std::vector<Slot>         inputs_;
    std::vector<Slot>         outputs_;
    // Parallel arrays of bare Qnn_Tensor_t values passed to QnnGraph_execute;
    // must remain valid across executions, rebuilt from inputs_/outputs_ on
    // finishLoad().
    std::vector<Qnn_Tensor_t> inputTensors_;
    std::vector<Qnn_Tensor_t> outputTensors_;
};

// Return number of bytes per element for a QNN_DATATYPE_* enum value.
// Returns 0 for unsupported types (SDK enums we don't plan to parse here,
// e.g. QNN_DATATYPE_UNDEFINED, microscaling).
size_t qnnDataTypeBytes(Qnn_DataType_t dt);

} // namespace sthal
