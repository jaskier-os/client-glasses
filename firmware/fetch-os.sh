#!/usr/bin/env bash
#
# fetch-os.sh -- download a stock Rokid AR (glass15 / neo) OTA build and extract
# its raw partition images into a local cache, so the rooting builder
# (root-firmware.sh) and the stock flash scripts can run fully offline against
# files in THIS folder instead of pulling them from somewhere else.
#
# The cache lives at firmware/os-cache/<version>/ and is gitignored -- the images
# are ~1.5 GB and must never be committed.
#
# Usage:
#   bash fetch-os.sh                # fetch the default pinned version
#   bash fetch-os.sh <version>      # fetch a specific version (e.g. 1.18.100-20260426-150101)
#   bash fetch-os.sh --url <zipurl> # fetch an explicit OTA zip URL
#
# After it runs, os-cache/current -> os-cache/<version> symlink points at the
# extracted build dir. root-firmware.sh reads stock images from there by default
# (STOCK_DIR), and firmware/scripts/flash.sh flashes from there.
set -euo pipefail
cd "$(dirname "$0")"

# Default pinned build (glass15 / 1.18.100). Other versions can be discovered at:
#   https://rokid-glass-ota.oss-cn-hangzhou.aliyuncs.com/dailybuild/glass15/<version>/RVE01-<version>.zip
#   https://rokid.andersmadsen.dk/firmware   (community mirror / index of OS builds)
DEFAULT_VERSION="1.18.100-20260426-150101"
OTA_BASE="https://rokid-glass-ota.oss-cn-hangzhou.aliyuncs.com/dailybuild/glass15"

VERSION="$DEFAULT_VERSION"
URL=""
while [ $# -gt 0 ]; do
  case "$1" in
    --url) URL="$2"; shift 2 ;;
    -h|--help) sed -n '2,30p' "$0"; exit 0 ;;
    *) VERSION="$1"; shift ;;
  esac
done
[ -n "$URL" ] || URL="${OTA_BASE}/${VERSION}/RVE01-${VERSION}.zip"

CACHE_DIR="os-cache"
ZIP="${CACHE_DIR}/RVE01-${VERSION}.zip"
DEST="${CACHE_DIR}/${VERSION}"
mkdir -p "$CACHE_DIR"

if [ -d "$DEST" ] && [ -f "$DEST/super_4.img" ]; then
  echo "[fetch-os] already extracted: $DEST"
else
  echo "[fetch-os] downloading $URL"
  curl -fL --retry 3 -o "$ZIP" "$URL"
  echo "[fetch-os] extracting into $DEST"
  tmp="$(mktemp -d "${CACHE_DIR}/.extract.XXXXXX")"
  unzip -q "$ZIP" -d "$tmp"
  # OTA wraps everything in a single ar1-<version>-<variant>/ dir; flatten it.
  inner="$(find "$tmp" -mindepth 1 -maxdepth 1 -type d | head -1)"
  mkdir -p "$DEST"
  mv "$inner"/* "$DEST"/
  rm -rf "$tmp"
  # keep the zip out of the way but cached for re-extract; comment out to save space
  # rm -f "$ZIP"
fi

ln -sfn "$VERSION" "${CACHE_DIR}/current"
echo "[fetch-os] ready: ${CACHE_DIR}/current -> ${VERSION}"
echo "[fetch-os] stock images now available under $DEST (super_4.img, xbl_s_devprg_ns.melf, gpt_*, patch*.xml, rawprogram*.xml, abl.elf, ...)"
