#!/usr/bin/env bash

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SOURCE_DIR="${OCTELIUM_SOURCE_DIR:-${ROOT}/../octelium}"
OUT_DIR="${OCTELIUM_HOST_LIB_DIR:-${ROOT}/build/host-libs}"

if [ ! -f "${SOURCE_DIR}/client/liboctelium/capi.go" ]; then
  echo "Could not find liboctelium at ${SOURCE_DIR}/client/liboctelium" >&2
  echo "Set OCTELIUM_SOURCE_DIR to the root of the Octelium repository" >&2
  exit 1
fi

if [ -z "${JAVA_HOME:-}" ]; then
  echo "JAVA_HOME must be set in order to build the JNI shim" >&2
  exit 1
fi

case "$(uname -s)" in
  Linux)
    JNI_PLATFORM="linux"
    ;;
  Darwin)
    JNI_PLATFORM="darwin"
    ;;
  *)
    echo "Unsupported host: $(uname -s)" >&2
    exit 1
    ;;
esac

mkdir -p "${OUT_DIR}"

(
  cd "${SOURCE_DIR}"
  CGO_ENABLED=1 go build -trimpath -buildvcs=false -buildmode=c-shared \
    -o "${OUT_DIR}/liboctelium.so" github.com/octelium/octelium/client/liboctelium
)

"${CC:-cc}" -shared -fPIC -O2 -Wall -Wextra -Werror \
  -I"${JAVA_HOME}/include" -I"${JAVA_HOME}/include/${JNI_PLATFORM}" \
  -o "${OUT_DIR}/liboctelium_jni.so" "${ROOT}/app/src/main/cpp/octelium_jni.c" -ldl -lpthread

rm -f "${OUT_DIR}/liboctelium.h"

echo "Built the host libraries into ${OUT_DIR}"
