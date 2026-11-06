# Build and test environment for the Pumpkin port, so nothing needs installing on the host:
#   docker build -t friends-pumpkin-dev -f PumpkinMC/tools/dev.Dockerfile PumpkinMC/tools
#   docker run --rm -v "$PWD:/repo" -w /repo/PumpkinMC friends-pumpkin-dev cargo plugin   (from the repository root)
# Rust (same version as rust-toolchain.toml) with the wasm32-wasip2 target, wasi-sdk (clang + WASI sysroot, to
# compile SQLite's C sources to WebAssembly) and the wasmtime CLI (runs the core's tests as WASI programs).
FROM rust:1.99.1-slim-trixie

ARG WASI_SDK=34
ARG WASMTIME=v50.0.0

RUN apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends curl ca-certificates xz-utils \
    && rm -rf /var/lib/apt/lists/* \
    && rustup target add wasm32-wasip2 \
    && rustup component add clippy rustfmt \
    && curl -fsSL "https://github.com/WebAssembly/wasi-sdk/releases/download/wasi-sdk-${WASI_SDK}/wasi-sdk-${WASI_SDK}.0-x86_64-linux.tar.gz" \
        | tar -xz -C /opt \
    && mv "/opt/wasi-sdk-${WASI_SDK}.0-x86_64-linux" /opt/wasi-sdk \
    && curl -fsSL "https://github.com/bytecodealliance/wasmtime/releases/download/${WASMTIME}/wasmtime-${WASMTIME}-x86_64-linux.tar.xz" \
        | tar -xJ -C /opt \
    && mv "/opt/wasmtime-${WASMTIME}-x86_64-linux/wasmtime" /usr/local/bin/wasmtime \
    && rm -rf "/opt/wasmtime-${WASMTIME}-x86_64-linux"

# libsqlite3-sys builds SQLite with this toolchain for WASI targets; the cc crate picks up the per-target variables.
ENV WASI_SDK_PATH=/opt/wasi-sdk \
    CC_wasm32_wasip2=/opt/wasi-sdk/bin/clang \
    AR_wasm32_wasip2=/opt/wasi-sdk/bin/llvm-ar \
    CFLAGS_wasm32_wasip2="--sysroot=/opt/wasi-sdk/share/wasi-sysroot" \
    CARGO_TARGET_WASM32_WASIP2_RUNNER="wasmtime run -S inherit-network -S allow-ip-name-lookup --dir /tmp"
