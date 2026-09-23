#!/usr/bin/env bash

set -euo pipefail

TARGET="${1:?Usage: check-elf-alignment.sh <directory or APK/AAB>}"
MIN_ALIGN=16384

if [ -z "${ANDROID_NDK_HOME:-}" ] && [ -n "${ANDROID_HOME:-}" ] && [ -d "${ANDROID_HOME}/ndk" ]; then
  ANDROID_NDK_HOME="$(find "${ANDROID_HOME}/ndk" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -n 1)"
fi

READELF="${READELF:-}"
if [ -z "${READELF}" ] && [ -n "${ANDROID_NDK_HOME:-}" ]; then
  READELF="${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/$(uname -s | tr '[:upper:]' '[:lower:]')-x86_64/bin/llvm-readelf"
fi
READELF="${READELF:-readelf}"

WORK_DIR="${TARGET}"
if [ -f "${TARGET}" ]; then
  WORK_DIR="$(mktemp -d)"
  trap 'rm -rf "${WORK_DIR}"' EXIT
  unzip -q "${TARGET}" '*.so' -d "${WORK_DIR}"
fi

count=0
failed=0

while IFS= read -r -d '' lib; do
  count=$((count + 1))

  while read -r align; do
    if [ $((align)) -lt ${MIN_ALIGN} ]; then
      echo "FAIL: ${lib#"${WORK_DIR}"/} has a LOAD segment aligned to ${align}" >&2
      failed=1
    fi
  done < <("${READELF}" -lW "${lib}" | awk '$1 == "LOAD" { print $NF }')
done < <(find "${WORK_DIR}" -type f -name '*.so' -print0)

if [ "${count}" -eq 0 ]; then
  echo "No shared libraries found in ${TARGET}" >&2
  exit 1
fi

if [ "${failed}" -ne 0 ]; then
  exit 1
fi

echo "All ${count} shared libraries are aligned to at least ${MIN_ALIGN} bytes"
