#!/usr/bin/env bash

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SOURCE_DIR="${LIBOCTELIUM_SOURCE_DIR:-${ROOT}/../liboctelium}"
OUT_DIR="${OCTELIUM_LIB_DIR:-${ROOT}/liboctelium}"
ANDROID_API="${ANDROID_API:-29}"
ABIS="${ABIS:-arm64-v8a x86_64}"

if [ ! -f "${SOURCE_DIR}/include/octelium.h" ]; then
  echo "Could not find liboctelium at ${SOURCE_DIR}" >&2
  echo "Set LIBOCTELIUM_SOURCE_DIR to the root of the liboctelium repository" >&2
  exit 1
fi

if ! cmp -s "${SOURCE_DIR}/include/octelium.h" "${ROOT}/app/src/main/cpp/octelium.h"; then
  echo "app/src/main/cpp/octelium.h differs from ${SOURCE_DIR}/include/octelium.h" >&2
  echo "Copy the C header of the liboctelium revision that is built" >&2
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
TAG="${LIBOCTELIUM_TAG:-$(git -C "${SOURCE_DIR}" describe --tags --exact-match --match 'v*.*.*' 2>/dev/null || true)}"
BRANCH="$(git -C "${SOURCE_DIR}" rev-parse --abbrev-ref HEAD)"

rm -rf "${OUT_DIR}"
mkdir -p "${OUT_DIR}"

for abi in ${ABIS}; do
  case "${abi}" in
    arm64-v8a)
      TRIPLE="aarch64-linux-android"
      ;;
    x86_64)
      TRIPLE="x86_64-linux-android"
      ;;
    *)
      echo "Unsupported ABI: ${abi}" >&2
      exit 1
      ;;
  esac

  echo "Building liboctelium ${TAG:-${COMMIT}} for ${abi}"

  TRIPLE_ENV="$(echo "${TRIPLE}" | tr '[:lower:]-' '[:upper:]_')"
  CLANG="${TOOLCHAIN}/${TRIPLE}${ANDROID_API}-clang"

  env \
    "CARGO_TARGET_${TRIPLE_ENV}_LINKER=${CLANG}" \
    "CARGO_TARGET_${TRIPLE_ENV}_RUSTFLAGS=-C link-arg=-Wl,-z,max-page-size=16384 -C link-arg=-Wl,-soname,liboctelium.so" \
    "CC_${TRIPLE//-/_}=${CLANG}" \
    "AR_${TRIPLE//-/_}=${TOOLCHAIN}/llvm-ar" \
    cargo build --release --lib --target "${TRIPLE}" --manifest-path "${SOURCE_DIR}/Cargo.toml"

  mkdir -p "${OUT_DIR}/${abi}"
  cp "${SOURCE_DIR}/target/${TRIPLE}/release/liboctelium.so" "${OUT_DIR}/${abi}/liboctelium.so"
done

printf '%s\n' "${COMMIT}" > "${OUT_DIR}/LIBOCTELIUM_COMMIT"
printf '%s\n' "${TAG:-${BRANCH}}" > "${OUT_DIR}/LIBOCTELIUM_REF"

"${ROOT}/scripts/check-elf-alignment.sh" "${OUT_DIR}"

echo "Built liboctelium into ${OUT_DIR}"
