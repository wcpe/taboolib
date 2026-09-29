#!/usr/bin/env bash
#
# Incision JVMTI native 交叉编译脚本（Linux / macOS 通用）
#
# 用 zig cc 单一工具链交叉编译 6 个平台目标，产物直接写入
# src/main/resources/native/{os}/{arch}/，由 JvmtiBackend 在运行期提取加载。
# Windows 请使用同目录下的 build-native.bat。
#
# 用法：
#   ./build-native.sh                 编译全部 6 个目标
#   ./build-native.sh linux/x64       只编译指定目标（可多个）
#   ./build-native.sh --list          列出全部目标
#
# 工具链查找顺序：$ZIG → PATH → <项目根>/.tmp/tools/zig-*/zig → 自动下载
#
set -euo pipefail

ZIG_VERSION="${ZIG_VERSION:-0.16.0}"

# ---------------------------------------------------------------- 目录定位

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
INCISION_DIR="$(cd "$SCRIPT_DIR/../../.." && pwd)"
PROJECT_ROOT="$(cd "$INCISION_DIR/../.." && pwd)"
SRC_FILE="$SCRIPT_DIR/incision_jvmti.c"
OUT_ROOT="$INCISION_DIR/src/main/resources/native"
CACHE_DIR="$PROJECT_ROOT/.tmp/tools"
# 记录本次编译所用的 C 源码哈希，供 Gradle 的 verifyNative 任务判断二进制是否需要重编
SOURCE_LOCK="$SCRIPT_DIR/native-source.sha256"

# ---------------------------------------------------------------- 目标定义
# 格式：输出子目录|zig 目标三元组|产物文件名|jni_md.h 所在平台目录
TARGETS=(
    "windows/x64|x86_64-windows-gnu|incision-jvmti.dll|windows"
    "windows/arm64|aarch64-windows-gnu|incision-jvmti.dll|windows"
    "linux/x64|x86_64-linux-gnu|libincision-jvmti.so|linux"
    "linux/arm64|aarch64-linux-gnu|libincision-jvmti.so|linux"
    "macos/x64|x86_64-macos-none|libincision-jvmti.dylib|darwin"
    "macos/arm64|aarch64-macos-none|libincision-jvmti.dylib|darwin"
)

info()  { printf '\033[36m[incision]\033[0m %s\n' "$*"; }
warn()  { printf '\033[33m[incision]\033[0m %s\n' "$*" >&2; }
fail()  { printf '\033[31m[incision]\033[0m %s\n' "$*" >&2; exit 1; }

# ---------------------------------------------------------------- 工具链定位

# 定位 JDK 的 JNI 头文件目录，结果写入 JNI_INCLUDE
locate_jni_headers() {
    if [ -z "${JAVA_HOME:-}" ] && [ "$(uname -s)" = "Darwin" ] && [ -x /usr/libexec/java_home ]; then
        JAVA_HOME="$(/usr/libexec/java_home 2>/dev/null || true)"
    fi
    [ -n "${JAVA_HOME:-}" ] || fail "未设置 JAVA_HOME，且无法自动探测 JDK。请先设置 JAVA_HOME 指向 JDK 根目录。"
    JNI_INCLUDE="$JAVA_HOME/include"
    [ -f "$JNI_INCLUDE/jni.h" ]   || fail "找不到 $JNI_INCLUDE/jni.h，请确认 JAVA_HOME 指向的是 JDK（不是 JRE）。"
    [ -f "$JNI_INCLUDE/jvmti.h" ] || fail "找不到 $JNI_INCLUDE/jvmti.h，JVMTI 需要完整 JDK。"
}

# 定位 zig，结果写入 ZIG
locate_zig() {
    if [ -n "${ZIG:-}" ] && [ -x "$ZIG" ]; then
        return
    fi
    if command -v zig >/dev/null 2>&1; then
        ZIG="$(command -v zig)"
        return
    fi
    # 项目内缓存（.tmp/ 已在 .gitignore 中，不会污染仓库）
    local cached
    cached="$(find "$CACHE_DIR" -maxdepth 2 -type f -name zig -perm -u+x 2>/dev/null | head -1 || true)"
    if [ -n "$cached" ]; then
        ZIG="$cached"
        return
    fi
    download_zig
}

# 自动下载 zig 到项目缓存目录
download_zig() {
    case "$(uname -s)" in
        Linux)  os=linux ;;
        Darwin) os=macos ;;
        *)      fail "不支持的系统 $(uname -s)，请手动安装 zig 并设置 ZIG 环境变量。" ;;
    esac
    case "$(uname -m)" in
        x86_64|amd64) arch=x86_64 ;;
        arm64|aarch64) arch=aarch64 ;;
        *) fail "不支持的架构 $(uname -m)，请手动安装 zig 并设置 ZIG 环境变量。" ;;
    esac

    local name="zig-$arch-$os-$ZIG_VERSION"
    local url="https://ziglang.org/download/$ZIG_VERSION/$name.tar.xz"

    info "未找到 zig，尝试下载 $ZIG_VERSION（约 50MB，仅首次）..."
    mkdir -p "$CACHE_DIR"
    local tarball="$CACHE_DIR/$name.tar.xz"

    if command -v curl >/dev/null 2>&1; then
        curl -fL --retry 3 --retry-all-errors --connect-timeout 20 -o "$tarball" "$url" \
            || fail "zig 下载失败。请手动下载 $url 解压后设置 ZIG 环境变量。"
    elif command -v wget >/dev/null 2>&1; then
        wget -q --tries=3 --timeout=30 -O "$tarball" "$url" \
            || fail "zig 下载失败。请手动下载 $url 解压后设置 ZIG 环境变量。"
    else
        fail "需要 curl 或 wget 才能自动下载 zig，请手动安装并设置 ZIG 环境变量。"
    fi

    info "解压中..."
    tar -xJf "$tarball" -C "$CACHE_DIR"
    rm -f "$tarball"
    ZIG="$CACHE_DIR/$name/zig"
    [ -x "$ZIG" ] || fail "解压后未找到可执行文件 $ZIG。"
}

# ---------------------------------------------------------------- 编译

# 默认剥离调试符号：产物体积可降 5~8 倍，且不影响 JNI_OnLoad 的动态导出
# （strip 只移除静态符号表与 .debug_* 段，运行时查找走的是动态符号表）。
# 若需要在 native 崩溃栈里看到行号：NATIVE_DEBUG=1 ./build-native.sh
if [ "${NATIVE_DEBUG:-0}" = "1" ]; then
    STRIP_FLAGS=()
else
    STRIP_FLAGS=(-s)
fi

build_one() {
    local entry="$1"
    IFS='|' read -r subdir triple outfile hdr_platform <<<"$entry"

    local out_dir="$OUT_ROOT/$subdir"
    local out_file="$out_dir/$outfile"
    local hdr_dir="$SCRIPT_DIR/include/$hdr_platform"

    [ -d "$hdr_dir" ] || fail "缺少 JNI 平台头文件目录：$hdr_dir"
    mkdir -p "$out_dir"

    # -fPIC 仅对 ELF/Mach-O 有意义；PE 目标传了也无害，这里统一按目标区分
    local pic_flag=()
    case "$triple" in
        *windows*) ;;
        *) pic_flag=(-fPIC) ;;
    esac

    printf '  %-16s -> %s\n' "$subdir" "$outfile"
    "$ZIG" cc -shared "${pic_flag[@]}" -O2 "${STRIP_FLAGS[@]}" \
        -I"$JNI_INCLUDE" -I"$hdr_dir" \
        -target "$triple" \
        "$SRC_FILE" -o "$out_file" \
        || fail "编译失败：$subdir"

    # zig 编译 Windows 目标时会在输出目录顺带生成 .pdb（调试符号）与 .lib（导入库）。
    # 运行期只提取 .dll/.so/.dylib，这两个副产物既不参与加载，又会让单次提交增重数倍，直接清掉。
    rm -f "$out_dir"/*.pdb "$out_dir"/*.lib

    # 统一权限：zig 会给 ELF 与 PE 目标加上可执行位而 Mach-O 不加，这些库只是被提取加载的
    # 资源，不需要可执行位，固定为 644 以免每次重编都产生无意义的 mode 变更
    chmod 644 "$out_file"

    # 基本产物校验
    [ -s "$out_file" ] || fail "产物为空：$out_file"
}

# 校验产物是否为目标平台的可执行格式
verify_one() {
    local entry="$1"
    IFS='|' read -r subdir _ outfile _ <<<"$entry"
    local out_file="$OUT_ROOT/$subdir/$outfile"
    local expect
    case "$subdir" in
        windows/*) expect="PE32+" ;;
        linux/*)   expect="ELF" ;;
        macos/*)   expect="Mach-O" ;;
    esac
    if command -v file >/dev/null 2>&1; then
        local actual
        actual="$(file -b "$out_file")"
        case "$actual" in
            *"$expect"*) ;;
            *) fail "产物格式异常（期望 $expect）：$out_file — $actual" ;;
        esac
    fi
    printf '  \033[32m✓\033[0m %-16s %8s 字节\n' "$subdir" "$(wc -c <"$out_file" | tr -d ' ')"
}

# ---------------------------------------------------------------- 主流程

main() {
    local selected=()
    case "${1:-}" in
        --list)
            for entry in "${TARGETS[@]}"; do
                IFS='|' read -r subdir triple outfile _ <<<"$entry"
                printf '  %-16s %-24s %s\n' "$subdir" "$triple" "$outfile"
            done
            exit 0
            ;;
        -h|--help) sed -n '2,20p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
        "") selected=("${TARGETS[@]}") ;;
        *)
            for want in "$@"; do
                local found=""
                for entry in "${TARGETS[@]}"; do
                    case "$entry" in "$want|"*) found="$entry" ;; esac
                done
                [ -n "$found" ] || fail "未知目标：$want（用 --list 查看可用目标）"
                selected+=("$found")
            done
            ;;
    esac

    locate_jni_headers
    locate_zig
    info "zig:       $("$ZIG" version)  ($ZIG)"
    info "JNI 头文件: $JNI_INCLUDE"
    info "源码:      $(basename "$SRC_FILE")"
    echo

    info "编译中..."
    for entry in "${selected[@]}"; do
        build_one "$entry"
    done

    echo
    info "校验产物..."
    for entry in "${selected[@]}"; do
        verify_one "$entry"
    done

    # 记录源码哈希：产物是预编译入库的，改了 C 源码若忘记重跑本脚本不会有任何编译错误，
    # 产物会一直停留在旧版本（历史上发生过源码修复从未进入产物的情况），verifyNative 会据此拦截。
    ( cd "$SCRIPT_DIR" && sha256sum "$(basename "$SRC_FILE")" ) > "$SOURCE_LOCK"
    info "已记录源码哈希 -> $(basename "$SOURCE_LOCK")"

    echo
    info "完成。产物位于 $OUT_ROOT"
}

main "$@"
