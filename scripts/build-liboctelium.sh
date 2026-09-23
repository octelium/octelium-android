#!/usr/bin/env bash

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SOURCE_DIR="${OCTELIUM_SOURCE_DIR:-${ROOT}/../octelium}"
OUT_DIR="${OCTELIUM_LIB_DIR:-${ROOT}/liboctelium}"
ANDROID_API="${ANDROID_API:-29}"
ABIS="${ABIS:-arm64-v8a x86_64}"
LDFLAGS_PATH="github.com/octelium/octelium/pkg/utils/ldflags"

if [ ! -f "${SOURCE_DIR}/client/liboctelium/capi.go" ]; then
  echo "Could not find liboctelium at ${SOURCE_DIR}/client/liboctelium" >&2
  echo "Set OCTELIUM_SOURCE_DIR to the root of an Octelium repository revision that includes liboctelium" >&2
  exit 1
fi

if [ -z "${ANDROID_NDK_HOME:-}" ]; then
  if [ -n "${ANDROID_HOME:-}" ] && [ -d "${ANDROID_HOME}/ndk" ]; then
    ANDROID_NDK_HOME="$(find "${ANDROID_HOME}/ndk" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -n 1)"
  fi
fi

if [ -z "${ANDROID_NDK_HOME:-}" ] || [ ! -d "${ANDROID_NDK_HOME}" ]; then
  echo "Could not find the Android NDK. Set ANDROID_NDK_HOME" >&2
  exit 1
fi

TOOLCHAIN="${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/$(uname -s | tr '[:upper:]' '[:lower:]')-x86_64/bin"

COMMIT="$(git -C "${SOURCE_DIR}" rev-parse HEAD)"
TAG="${OCTELIUM_TAG:-$(git -C "${SOURCE_DIR}" describe --tags --exact-match --match 'v*.*.*' 2>/dev/null || true)}"
BRANCH="$(git -C "${SOURCE_DIR}" rev-parse --abbrev-ref HEAD)"

LDFLAGS="-s -w -X ${LDFLAGS_PATH}.GitCommit=${COMMIT} -X ${LDFLAGS_PATH}.GitBranch=${BRANCH} -X ${LDFLAGS_PATH}.Mode=production"
if [ -n "${TAG}" ]; then
  LDFLAGS="${LDFLAGS} -X ${LDFLAGS_PATH}.GitTag=${TAG} -X ${LDFLAGS_PATH}.SemVer=${TAG}"
fi

rm -rf "${OUT_DIR}"
mkdir -p "${OUT_DIR}"

for abi in ${ABIS}; do
  case "${abi}" in
    arm64-v8a)
      GOARCH="arm64"
      TRIPLE="aarch64-linux-android"
      ;;
    x86_64)
      GOARCH="amd64"
      TRIPLE="x86_64-linux-android"
      ;;
    *)
      echo "Unsupported ABI: ${abi}" >&2
      exit 1
      ;;
  esac

  echo "Building liboctelium ${TAG:-${COMMIT}} for ${abi}"

  (
    cd "${SOURCE_DIR}"
    CGO_ENABLED=1 GOOS=android GOARCH="${GOARCH}" \
      CC="${TOOLCHAIN}/${TRIPLE}${ANDROID_API}-clang" \
      CGO_LDFLAGS="-Wl,-z,max-page-size=16384 -Wl,-soname,liboctelium.so" \
      go build -trimpath -buildvcs=false -ldflags "${LDFLAGS}" -buildmode=c-shared \
      -o "${OUT_DIR}/${abi}/liboctelium.so" github.com/octelium/octelium/client/liboctelium
  )

  rm -f "${OUT_DIR}/${abi}/liboctelium.h"
done

printf '%s\n' "${COMMIT}" > "${OUT_DIR}/OCTELIUM_COMMIT"
printf '%s\n' "${TAG:-${BRANCH}}" > "${OUT_DIR}/OCTELIUM_REF"

"${ROOT}/scripts/check-elf-alignment.sh" "${OUT_DIR}"

echo "Built liboctelium into ${OUT_DIR}"
