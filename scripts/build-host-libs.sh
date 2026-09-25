#!/usr/bin/env bash

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SOURCE_DIR="${LIBOCTELIUM_SOURCE_DIR:-${ROOT}/../liboctelium}"
OUT_DIR="${OCTELIUM_HOST_LIB_DIR:-${ROOT}/build/host-libs}"

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

if [ -z "${JAVA_HOME:-}" ]; then
  echo "JAVA_HOME must be set in order to build the JNI shim" >&2
  exit 1
fi

case "$(uname -s)" in
  Linux)
    JNI_PLATFORM="linux"
    LIB_NAME="liboctelium.so"
    ;;
  Darwin)
    JNI_PLATFORM="darwin"
    LIB_NAME="liboctelium.dylib"
    ;;
  *)
    echo "Unsupported host: $(uname -s)" >&2
    exit 1
    ;;
esac

mkdir -p "${OUT_DIR}"

cargo build --release --lib --manifest-path "${SOURCE_DIR}/Cargo.toml"

cp "${SOURCE_DIR}/target/release/${LIB_NAME}" "${OUT_DIR}/liboctelium.so"

"${CC:-cc}" -shared -fPIC -O2 -Wall -Wextra -Werror \
  -I"${JAVA_HOME}/include" -I"${JAVA_HOME}/include/${JNI_PLATFORM}" \
  -o "${OUT_DIR}/liboctelium_jni.so" "${ROOT}/app/src/main/cpp/octelium_jni.c" -ldl -lpthread

echo "Built the host libraries into ${OUT_DIR}"
