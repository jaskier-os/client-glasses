#!/bin/bash
# Compile the four wake-word ONNX models into QNN HTP context binaries suitable
# for loading via QnnContext_createFromBinary() on the Rokid Neo aDSP (Hexagon v73).
#
# Pipeline per model:
#   (1) qnn-onnx-converter        : ONNX -> QNN model .cpp + .bin weights
#   (2) qnn-model-lib-generator   : .cpp/.bin -> libmodel.so (x86_64 backend model)
#   (3) qnn-context-binary-generator : libmodel.so -> model.bin (portable ctx binary)
#
# Outputs land at sthal/models/{silero_vad,melspectrogram,embedding_model,sireneviy}.bin
#
# Requires the QAIRT SDK + shim env configured via $HOME/qairt-env.sh.
# Non-interactive. Idempotent: re-runs overwrite prior output.
#
# Exit codes:
#   0  all four compiled
#   1  env / SDK missing
#   2  one or more models failed (see per-model log in build dir)
#
# Target: Rokid Neo (qcom,neo / LUNA-V2), aDSP Hexagon v73.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
STHAL_DIR="$(dirname "$SCRIPT_DIR")"
MODELS_OUT="${STHAL_DIR}/models"
ONNX_IN="${STHAL_DIR}/../app/src/main/assets"
# Two-file HTP backend config: main.json refs backend_extensions + an inner htp_ext.json
# with the per-graph knobs (dsp_arch, vtcm_mb, hvx_threads, O). The SDK only
# recognises a narrow schema in the top-level config.
MAIN_CFG="${SCRIPT_DIR}/qnn_htp_config.json"
HTP_EXT_CFG="${SCRIPT_DIR}/qnn_htp_ext_config.json"

# ---- env setup ----
QAIRT_ENV="${QAIRT_ENV:-$HOME/qairt-env.sh}"
if [[ ! -f "${QAIRT_ENV}" ]]; then
    echo "ERROR: QAIRT env bootstrap not found: ${QAIRT_ENV}" >&2
    echo "  Expected to find QNN_SDK_ROOT and Python venv wiring." >&2
    exit 1
fi
# shellcheck disable=SC1090
source "${QAIRT_ENV}"

# Make the clang++ shim (-> g++) visible to qnn-model-lib-generator.
SHIM_DIR="${QAIRT_BIN_SHIM:-$HOME/qairt-bin-shim}"
if [[ -d "${SHIM_DIR}" ]]; then
    export PATH="${SHIM_DIR}:${PATH}"
fi

if [[ -z "${QNN_SDK_ROOT:-}" || ! -d "${QNN_SDK_ROOT}" ]]; then
    echo "ERROR: QNN_SDK_ROOT not set or invalid: ${QNN_SDK_ROOT:-<unset>}" >&2
    exit 1
fi

for tool in qnn-onnx-converter qnn-model-lib-generator qnn-context-binary-generator; do
    if [[ ! -x "${QNN_SDK_ROOT}/bin/x86_64-linux-clang/${tool}" ]]; then
        echo "ERROR: ${tool} missing under ${QNN_SDK_ROOT}/bin/x86_64-linux-clang/" >&2
        exit 1
    fi
done

# ---- per-model config ----
# name|input_shape_csv
# Shapes chosen to match the wake-word pipeline tensors used on the phone side
# (openwakeword-style: 80 ms mel window, 76-frame embedding, 16-frame classifier).
MODELS=(
    "silero_vad|input:1,576;h:1,1,128;c:1,1,128"
    "melspectrogram|input:1,1280"
    "embedding_model|input_1:1,76,32,1"
    "sireneviy|input:1,16,96"
)

BUILD_ROOT="${STHAL_DIR}/build-qnn"
rm -rf "${BUILD_ROOT}"
mkdir -p "${BUILD_ROOT}" "${MODELS_OUT}"

# Co-locate the two HTP config files so the inner config_file_path resolves.
# Generator is invoked with cwd=${wk} and --config_file pointing at the main cfg.
# We patch the shared_library_path to an absolute path inside the copy.
if [[ -f "${MAIN_CFG}" && -f "${HTP_EXT_CFG}" ]]; then
    python3 - "${MAIN_CFG}" "${HTP_EXT_CFG}" "${BUILD_ROOT}" "${QNN_SDK_ROOT}" <<'PY'
import json, sys, os, shutil
main_src, ext_src, build, sdk = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
with open(main_src) as f: main = json.load(f)
main['backend_extensions']['shared_library_path'] = f"{sdk}/lib/x86_64-linux-clang/libQnnHtpNetRunExtensions.so"
main['backend_extensions']['config_file_path'] = os.path.join(build, 'qnn_htp_ext_config.json')
with open(os.path.join(build, 'qnn_htp_config.json'), 'w') as f: json.dump(main, f, indent=2)
shutil.copyfile(ext_src, os.path.join(build, 'qnn_htp_ext_config.json'))
PY
    STAGED_MAIN="${BUILD_ROOT}/qnn_htp_config.json"
else
    STAGED_MAIN=""
fi

# ---- compile each model ----
FAILED=()
for entry in "${MODELS[@]}"; do
    name="${entry%%|*}"
    shapes_csv="${entry#*|}"

    onnx="${ONNX_IN}/${name}.onnx"
    if [[ ! -f "${onnx}" ]]; then
        echo "[SKIP] ${name}: ONNX missing at ${onnx}" >&2
        FAILED+=("${name} (no-onnx)")
        continue
    fi

    wk="${BUILD_ROOT}/${name}"
    mkdir -p "${wk}"

    echo "========== ${name} =========="
    echo "[1/3] qnn-onnx-converter"
    # Build the --input_dim args from shapes_csv (semicolon-separated name:dims).
    conv_args=()
    IFS=';' read -ra pairs <<< "${shapes_csv}"
    for p in "${pairs[@]}"; do
        in_name="${p%%:*}"
        dims="${p#*:}"
        conv_args+=(--input_dim "${in_name}" "${dims}")
    done

    if ! "${QNN_SDK_ROOT}/bin/x86_64-linux-clang/qnn-onnx-converter" \
            --input_network "${onnx}" \
            "${conv_args[@]}" \
            --output_path "${wk}/${name}.cpp" \
            --float_bitwidth 32 \
            > "${wk}/convert.log" 2>&1; then
        echo "  FAIL converter -> ${wk}/convert.log" >&2
        tail -15 "${wk}/convert.log" >&2
        FAILED+=("${name} (convert)")
        continue
    fi
    if [[ ! -s "${wk}/${name}.cpp" ]]; then
        echo "  FAIL converter: no .cpp emitted" >&2
        FAILED+=("${name} (no-cpp)")
        continue
    fi

    echo "[2/3] qnn-model-lib-generator"
    if ! "${QNN_SDK_ROOT}/bin/x86_64-linux-clang/qnn-model-lib-generator" \
            -c "${wk}/${name}.cpp" \
            -b "${wk}/${name}.bin" \
            -o "${wk}/libs" \
            -t x86_64-linux-clang \
            > "${wk}/modellib.log" 2>&1; then
        echo "  FAIL model-lib -> ${wk}/modellib.log" >&2
        tail -15 "${wk}/modellib.log" >&2
        FAILED+=("${name} (modellib)")
        continue
    fi
    lib_so="${wk}/libs/x86_64-linux-clang/lib${name}.so"
    if [[ ! -f "${lib_so}" ]]; then
        echo "  FAIL model-lib: ${lib_so} missing" >&2
        FAILED+=("${name} (no-so)")
        continue
    fi

    echo "[3/3] qnn-context-binary-generator (v73)"
    ctxbin_cfg_args=()
    if [[ -n "${STAGED_MAIN}" ]]; then
        ctxbin_cfg_args+=(--config_file "${STAGED_MAIN}")
    fi
    if ! "${QNN_SDK_ROOT}/bin/x86_64-linux-clang/qnn-context-binary-generator" \
            --model "${lib_so}" \
            --backend "${QNN_SDK_ROOT}/lib/x86_64-linux-clang/libQnnHtp.so" \
            --binary_file "${name}" \
            --output_dir "${wk}" \
            "${ctxbin_cfg_args[@]}" \
            > "${wk}/ctxbin.log" 2>&1; then
        echo "  FAIL context-binary -> ${wk}/ctxbin.log" >&2
        tail -15 "${wk}/ctxbin.log" >&2
        FAILED+=("${name} (ctxbin)")
        continue
    fi
    out_bin="${wk}/${name}.bin"
    if [[ ! -s "${out_bin}" ]]; then
        echo "  FAIL context-binary: ${out_bin} empty" >&2
        FAILED+=("${name} (empty-bin)")
        continue
    fi

    cp -f "${out_bin}" "${MODELS_OUT}/${name}.bin"
    sz=$(stat -c%s "${MODELS_OUT}/${name}.bin")
    echo "  OK -> ${MODELS_OUT}/${name}.bin (${sz} bytes)"
done

echo
echo "========== summary =========="
for entry in "${MODELS[@]}"; do
    name="${entry%%|*}"
    if [[ -s "${MODELS_OUT}/${name}.bin" ]]; then
        sz=$(stat -c%s "${MODELS_OUT}/${name}.bin")
        printf "  %-20s  %10d bytes\n" "${name}" "${sz}"
    else
        printf "  %-20s  MISSING\n" "${name}"
    fi
done

if (( ${#FAILED[@]} > 0 )); then
    echo
    echo "FAILED: ${FAILED[*]}"
    exit 2
fi

echo "All four QNN context binaries written to ${MODELS_OUT}/"
exit 0
