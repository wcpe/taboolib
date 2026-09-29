@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion

rem ============================================================
rem Incision JVMTI native 交叉编译脚本（Windows）
rem
rem 用 zig cc 单一工具链交叉编译 6 个平台目标，产物直接写入
rem src\main\resources\native\{os}\{arch}\，由 JvmtiBackend 在运行期提取加载。
rem Linux / macOS 请使用同目录下的 build-native.sh。
rem
rem 用法：
rem   build-native.bat                   编译全部 6 个目标
rem   build-native.bat linux\x64         只编译指定目标
rem   build-native.bat --list            列出全部目标
rem
rem 工具链查找顺序：%%ZIG%% → PATH → <项目根>\.tmp\tools\zig-*\zig.exe → 自动下载
rem ============================================================

if "%~1"=="--list" goto :list
if "%~1"=="-h"     goto :usage
if "%~1"=="--help" goto :usage

if not defined ZIG_VERSION set "ZIG_VERSION=0.16.0"

rem ---------------------------------------------------------- 目录定位

set "C_DIR=%~dp0"
for %%I in ("%C_DIR%..\..\..") do set "INCISION_DIR=%%~fI"
for %%I in ("%C_DIR%..\..\..\..\..") do set "PROJECT_ROOT=%%~fI"

set "SRC_FILE=%C_DIR%incision_jvmti.c"
set "OUT_ROOT=%INCISION_DIR%\src\main\resources\native"
set "HDR_ROOT=%C_DIR%include"
set "CACHE_DIR=%PROJECT_ROOT%\.tmp\tools"
rem 记录本次编译所用的 C 源码哈希，供 Gradle 的 verifyNative 任务判断二进制是否需要重编
set "SOURCE_LOCK=%C_DIR%native-source.sha256"

if not exist "%SRC_FILE%" (
    echo [incision] 找不到源码：%SRC_FILE%
    exit /b 1
)

rem ---------------------------------------------------------- JDK 定位

if not defined JAVA_HOME (
    echo [incision] 未设置 JAVA_HOME，请先指向 JDK 根目录（不是 JRE）。
    exit /b 1
)
set "JNI=%JAVA_HOME%\include"
if not exist "%JNI%\jni.h" (
    echo [incision] 找不到 "%JNI%\jni.h"，请确认 JAVA_HOME 指向的是 JDK。
    exit /b 1
)

rem ---------------------------------------------------------- zig 定位

set "ZIG_EXE="
if defined ZIG if exist "%ZIG%" set "ZIG_EXE=%ZIG%"

if not defined ZIG_EXE (
    for /f "delims=" %%P in ('where zig 2^>nul') do (
        if not defined ZIG_EXE set "ZIG_EXE=%%P"
    )
)

if not defined ZIG_EXE (
    for /d %%D in ("%CACHE_DIR%\zig-*-windows-*") do (
        if exist "%%D\zig.exe" if not defined ZIG_EXE set "ZIG_EXE=%%D\zig.exe"
    )
)

if not defined ZIG_EXE (
    echo [incision] 未找到 zig，尝试下载 %ZIG_VERSION% ...
    if not exist "%CACHE_DIR%" mkdir "%CACHE_DIR%"
    set "ZIP=%CACHE_DIR%\zig-x86_64-windows-%ZIG_VERSION%.zip"
    curl -fL --retry 3 --retry-all-errors -o "!ZIP!" "https://ziglang.org/download/%ZIG_VERSION%/zig-x86_64-windows-%ZIG_VERSION%.zip"
    if errorlevel 1 (
        echo [incision] zig 下载失败。请手动下载解压后设置 ZIG 环境变量。
        exit /b 1
    )
    powershell -NoProfile -Command "Expand-Archive -Path '!ZIP!' -DestinationPath '%CACHE_DIR%' -Force"
    del "!ZIP!" >nul 2>&1
    set "ZIG_EXE=%CACHE_DIR%\zig-x86_64-windows-%ZIG_VERSION%\zig.exe"
)

if not exist "%ZIG_EXE%" (
    echo [incision] zig 不可用：%ZIG_EXE%
    exit /b 1
)

echo [incision] zig:        %ZIG_EXE%
echo [incision] JNI 头文件: %JNI%
echo [incision] 源码:       incision_jvmti.c
echo.
echo [incision] 编译中...

rem 默认剥离调试符号；需要在 native 崩溃栈里看到行号时设置 NATIVE_DEBUG=1
set "STRIPFLAG=-s"
if defined NATIVE_DEBUG set "STRIPFLAG="

rem ---------------------------------------------------------- 编译全部目标

call :build windows\x64   x86_64-windows-gnu  incision-jvmti.dll   windows
call :build windows\arm64 aarch64-windows-gnu incision-jvmti.dll   windows
call :build linux\x64    x86_64-linux-gnu    libincision-jvmti.so linux
call :build linux\arm64  aarch64-linux-gnu   libincision-jvmti.so linux
call :build macos\x64    x86_64-macos-none   libincision-jvmti.dylib darwin
call :build macos\arm64  aarch64-macos-none  libincision-jvmti.dylib darwin

rem 记录源码哈希：产物是预编译入库的，改了 C 源码若忘记重跑本脚本不会有任何编译错误，
rem 产物会一直停留在旧版本，verifyNative 会据此拦截。
set "SRCHASH="
for /f "delims=" %%H in ('powershell -NoProfile -Command "(Get-FileHash -Algorithm SHA256 '%SRC_FILE%').Hash.ToLower()"') do set "SRCHASH=%%H"
if defined SRCHASH (
    > "%SOURCE_LOCK%" echo !SRCHASH!  incision_jvmti.c
    echo [incision] 已记录源码哈希 -^> native-source.sha256
) else (
    echo [incision] 警告：无法计算源码哈希，verifyNative 将会失败
)

echo.
echo [incision] 完成。产物位于 %OUT_ROOT%
exit /b 0

rem ---------------------------------------------------------- 子过程
rem %1=输出子目录  %2=zig 目标三元组  %3=产物文件名  %4=jni_md.h 平台目录

:build
setlocal
set "SUB=%~1"
set "TRIPLE=%~2"
set "OUTFILE=%~3"
set "HDRPLAT=%~4"

set "OUT_DIR=%OUT_ROOT%\%SUB%"
set "HDR_DIR=%HDR_ROOT%\%HDRPLAT%"

if not exist "%HDR_DIR%" (
    echo [incision] 缺少 JNI 平台头文件目录：%HDR_DIR%
    exit /b 1
)
if not exist "%OUT_DIR%" mkdir "%OUT_DIR%"

rem PE 目标不需要 -fPIC，ELF / Mach-O 需要
set "PICFLAG=-fPIC"
echo %TRIPLE% | findstr /C:"windows" >nul && set "PICFLAG="

echo   %SUB%  -^>  %OUTFILE%
"%ZIG_EXE%" cc -shared %PICFLAG% -O2 %STRIPFLAG% -I"%JNI%" -I"%HDR_DIR%" -target %TRIPLE% "%SRC_FILE%" -o "%OUT_DIR%\%OUTFILE%"
if errorlevel 1 (
    echo [incision] 编译失败：%SUB%
    exit /b 1
)
if not exist "%OUT_DIR%\%OUTFILE%" (
    echo [incision] 产物为空：%OUT_DIR%\%OUTFILE%
    exit /b 1
)

rem zig 编译 Windows 目标时会顺带生成 .pdb（调试符号）与 .lib（导入库）。
rem 运行期只提取 .dll/.so/.dylib，这两个副产物既不参与加载，又会让单次提交增重数倍，直接清掉。
del /q "%OUT_DIR%\*.pdb" "%OUT_DIR%\*.lib" >nul 2>&1

endlocal
exit /b 0

:list
echo   windows/x64      x86_64-windows-gnu       incision-jvmti.dll
echo   windows/arm64    aarch64-windows-gnu      incision-jvmti.dll
echo   linux/x64        x86_64-linux-gnu         libincision-jvmti.so
echo   linux/arm64      aarch64-linux-gnu        libincision-jvmti.so
echo   macos/x64        x86_64-macos-none        libincision-jvmti.dylib
echo   macos/arm64      aarch64-macos-none       libincision-jvmti.dylib
exit /b 0

:usage
echo 用法：
echo   build-native.bat                编译全部 6 个目标
echo   build-native.bat --list         列出可用目标
echo.
echo 工具链查找顺序：%%ZIG%% -^> PATH -^> 项目 .tmp\tools\zig-* -^> 自动下载
exit /b 0
