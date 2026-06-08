#!/usr/bin/env bash
#
# flash.sh -- flash a STOCK Rokid AR build over EDL (Qualcomm 9008) with qdl.
# Writes the full stock image set (all partitions) from the OS cache.
# For the ROOTED super_4 flow use ../root-firmware.sh instead -- it emits its own
# minimal rawprogram_super4.xml (GPT + abl_old + super_4 only). See ./CLAUDE.md.
#
# The device must already be in EDL mode. See ./CLAUDE.md for the non-interactive
# enter-edl -> qdl sequence.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
STOCK_DIR="${STOCK_DIR:-$HERE/../os-cache/current}"
[ -f "$STOCK_DIR/xbl_s_devprg_ns.melf" ] || {
  echo "stock images not found in $STOCK_DIR -- run: bash ../fetch-os.sh" >&2; exit 1; }
cd "$STOCK_DIR"
# qdl ships inside the OS cache (extracted from the OTA zip); fall back to PATH.
QDL="$STOCK_DIR/qdl"; [ -x "$QDL" ] || QDL="qdl"
exec sudo "$QDL" --storage emmc xbl_s_devprg_ns.melf rawprogram*.xml patch*.xml
