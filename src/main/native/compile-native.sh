#!/bin/bash
# compile-native.sh — Multi-architecture native library build script for MPTCP JNI
#
# Usage:
#   ./compile-native.sh <output-base-dir> <project-base-dir>
#
# Arguments:
#   output-base-dir   — Maven target/classes directory (the .so is placed under
#                        native/linux/<arch>/ so it's automatically included in the JAR)
#   project-base-dir  — Project root directory (where src/main/native/mptcp_jni.c lives)
#
# Environment:
#   JAVA_HOME  — JDK installation path (required for JNI headers)
#   CC         — C compiler (default: gcc)
#   CROSS_ARCH — Force target architecture: x86_64 | aarch64 (default: auto-detect)
#
# Examples:
#   # Auto-detect architecture (compile for current platform)
#   ./compile-native.sh target/classes .
#
#   # Cross-compile for aarch64 on an x86_64 host
#   CC=aarch64-linux-gnu-gcc CROSS_ARCH=aarch64 ./compile-native.sh target/classes .

set -euo pipefail

OUTPUT_BASE="${1:?Usage: $0 <output-base-dir> <project-base-dir>}"
PROJECT_BASE="${2:?Usage: $0 <output-base-dir> <project-base-dir>}"

# --- Validate JAVA_HOME ---
if [ -z "${JAVA_HOME:-}" ]; then
    # Try to detect JAVA_HOME automatically
    if command -v java >/dev/null 2>&1; then
        JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")"
    else
        echo "ERROR: JAVA_HOME is not set and java is not in PATH" >&2
        exit 1
    fi
fi

if [ ! -f "${JAVA_HOME}/include/jni.h" ]; then
    echo "ERROR: JNI headers not found at ${JAVA_HOME}/include/jni.h" >&2
    echo "       Please set JAVA_HOME to a JDK (not JRE) installation" >&2
    exit 1
fi

# --- Detect or validate target architecture ---
if [ -n "${CROSS_ARCH:-}" ]; then
    ARCH="${CROSS_ARCH}"
else
    ARCH="$(uname -m)"
fi

case "${ARCH}" in
    x86_64|amd64)   ARCH_DIR="x86_64"  ;;
    aarch64|arm64)   ARCH_DIR="aarch64" ;;
    *)
        echo "ERROR: Unsupported architecture: ${ARCH}" >&2
        echo "       Supported: x86_64, aarch64" >&2
        exit 1
        ;;
esac

# --- Compiler ---
CC="${CC:-gcc}"

# --- Source and output paths ---
SRC="${PROJECT_BASE}/src/main/native/mptcp_jni.c"
OUT_DIR="${OUTPUT_BASE}/native/linux/${ARCH_DIR}"
OUT_LIB="${OUT_DIR}/libmptcpenabler.so"

if [ ! -f "${SRC}" ]; then
    echo "ERROR: Source file not found: ${SRC}" >&2
    exit 1
fi

mkdir -p "${OUT_DIR}"

echo "Compiling MPTCP native library:"
echo "  Arch:     ${ARCH_DIR}"
echo "  Compiler: ${CC}"
echo "  Source:   ${SRC}"
echo "  Output:   ${OUT_LIB}"

${CC} -shared -fPIC -O2 \
    -o "${OUT_LIB}" \
    -I"${JAVA_HOME}/include" \
    -I"${JAVA_HOME}/include/linux" \
    "${SRC}"

echo "Done: ${OUT_LIB} ($(stat -c%s "${OUT_LIB}") bytes)"

