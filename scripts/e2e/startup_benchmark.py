"""测量多个 TabooLib 插件在完整缓存下的服务端启动耗时。"""

import argparse
import hashlib
import json
import os
import queue
import re
import shutil
import statistics
import struct
import subprocess
import sys
import threading
import time
import zipfile
from pathlib import Path

from run import ROOT_DIR, WORK_BASE_DIR, build_harness, prepare_server, setup_server_dir


BENCHMARK_DIR = ROOT_DIR / ".e2e" / "startup-benchmark"
TIMING_PATTERNS = {
    "primitive": re.compile(r"基础依赖加载完成，用时 ([\d,]+) 毫秒"),
    "runtime_env": re.compile(r"RuntimeEnv 加载完成，用时 ([\d,]+) 毫秒"),
    "dependencies": re.compile(r"所有依赖加载完成，用时 ([\d,]+) 毫秒"),
    "scanner": re.compile(r"ProjectScanner 扫描到 .*用时 ([\d,]+) 毫秒"),
    "visitor": re.compile(r"ClassVisitor 总用时 ([\d,]+) 毫秒"),
    "platform": re.compile(r"跨平台服务初始化完成，用时 ([\d,]+) 毫秒"),
    "const": re.compile(r'生命周期 "CONST" 用时 ([\d,]+) 毫秒'),
    "init": re.compile(r'生命周期 "INIT" 用时 ([\d,]+) 毫秒'),
    "load": re.compile(r'生命周期 "LOAD" 用时 ([\d,]+) 毫秒'),
    "enable": re.compile(r'生命周期 "ENABLE" 用时 ([\d,]+) 毫秒'),
    "bukkit_init": re.compile(r"Bukkit 插件初始化完成，用时 ([\d,]+) 毫秒"),
}


def publish_local():
    gradle = ROOT_DIR / ("gradlew.bat" if sys.platform == "win32" else "gradlew")
    result = subprocess.run([str(gradle), "publishToMavenLocal", "-x", "test"], cwd=ROOT_DIR)
    if result.returncode != 0:
        raise RuntimeError("无法发布热启动基准所需的正式版本模块")


def current_version() -> str:
    properties = (ROOT_DIR / "gradle.properties").read_text(encoding="utf-8")
    base_version = re.search(r"^version=(.+)$", properties, re.MULTILINE).group(1)
    commit_id = subprocess.check_output(
        ["git", "rev-parse", "--short=7", "HEAD"],
        cwd=ROOT_DIR,
        text=True,
        encoding="utf-8",
    ).strip()
    return f"{base_version}-{commit_id}"


def refresh_local_checksums(version: str):
    version_dirs = (Path.home() / ".m2" / "repository" / "io" / "izzel" / "taboolib").glob(f"*/{version}")
    for version_dir in version_dirs:
        for jar in version_dir.glob("*.jar"):
            jar.with_suffix(".jar.sha1").write_text(hashlib.sha1(jar.read_bytes()).hexdigest(), encoding="utf-8")


def minimal_class(internal_name: str, superclass: str = "java/lang/Object") -> bytes:
    def utf8(value: str) -> bytes:
        encoded = value.encode("utf-8")
        return b"\x01" + struct.pack(">H", len(encoded)) + encoded

    constant_pool = b"".join(
        (
            utf8(internal_name),
            b"\x07\x00\x01",
            utf8(superclass),
            b"\x07\x00\x03",
            utf8("<init>"),
            utf8("()V"),
            utf8("Code"),
            b"\x0c\x00\x05\x00\x06",
            b"\x0a\x00\x04\x00\x08",
        )
    )
    constructor_code = b"\x2a\xb7\x00\x09\xb1"
    code_attribute = struct.pack(">HIHHI", 7, 17, 1, 1, len(constructor_code)) + constructor_code + b"\x00\x00\x00\x00"
    constructor = struct.pack(">HHHH", 0x0001, 5, 6, 1) + code_attribute
    return (
        b"\xca\xfe\xba\xbe"
        + struct.pack(">HHH", 0, 52, 10)
        + constant_pool
        + struct.pack(">HHHHHH", 0x0031, 2, 4, 0, 0, 1)
        + constructor
        + b"\x00\x00"
    )


def prepare_relocator(java_command: str) -> str:
    cache = Path.home() / ".gradle" / "caches" / "modules-2" / "files-2.1"
    dependencies = (
        next((cache / "me.lucko" / "jar-relocator" / "1.7").glob("*/jar-relocator-1.7.jar")),
        next((cache / "org.ow2.asm" / "asm" / "9.2").glob("*/asm-9.2.jar")),
        next((cache / "org.ow2.asm" / "asm-commons" / "9.2").glob("*/asm-commons-9.2.jar")),
    )
    helper_dir = BENCHMARK_DIR / "relocator"
    helper_dir.mkdir(parents=True, exist_ok=True)
    source = helper_dir / "BenchmarkRelocator.java"
    source.write_text(
        """
import java.io.File;
import java.util.Collections;
import me.lucko.jarrelocator.JarRelocator;
import me.lucko.jarrelocator.Relocation;

public class BenchmarkRelocator {
    public static void main(String[] args) throws Exception {
        new JarRelocator(
            new File(args[0]),
            new File(args[1]),
            Collections.singletonList(new Relocation(args[2], args[3]))
        ).run();
    }
}
""".strip(),
        encoding="utf-8",
    )
    classpath = os.pathsep.join(str(path) for path in dependencies)
    javac = str(Path(java_command).with_name("javac.exe" if sys.platform == "win32" else "javac"))
    subprocess.run([javac, "-cp", classpath, "-d", str(helper_dir), str(source)], check=True)
    return os.pathsep.join((str(helper_dir), classpath))


def prepare_jfr_settings(java_command: str) -> Path:
    resolved_java = shutil.which(java_command) or java_command
    java_home = Path(resolved_java).resolve().parent.parent
    settings = BENCHMARK_DIR / "startup-profile.jfc"
    jfr = java_home / "bin" / ("jfr.exe" if sys.platform == "win32" else "jfr")
    subprocess.run(
        [
            str(jfr),
            "configure",
            "--input",
            str(java_home / "lib" / "jfr" / "profile.jfc"),
            "--output",
            str(settings),
            "class-loading=true",
            "file-threshold=0ms",
            "allocation-profiling=high",
        ],
        check=True,
    )
    return settings


def rewrite_plugin_main(plugin_jar: Path, main_class: str):
    rewritten = plugin_jar.with_suffix(".rewritten.jar")
    with zipfile.ZipFile(plugin_jar) as source_jar, zipfile.ZipFile(rewritten, "w") as target_jar:
        for entry in source_jar.infolist():
            data = source_jar.read(entry.filename)
            if entry.filename == "plugin.yml":
                plugin_yml = data.decode("utf-8")
                data = re.sub(r"(?m)^main: .+$", f"main: {main_class}", plugin_yml).encode("utf-8")
            target_jar.writestr(entry, data)
    rewritten.replace(plugin_jar)


def create_plugin(
    source: Path,
    target: Path,
    plugin_name: str,
    class_count: int,
    padding_mb: int,
    version: str,
    modules: str,
    java_command: str,
    relocator_classpath: str,
    shared_group: bool,
    isolated: bool,
    debug: bool,
):
    unrelocated = target.with_suffix(".unrelocated.jar")
    with zipfile.ZipFile(source) as source_jar, zipfile.ZipFile(unrelocated, "w") as target_jar:
        for entry in source_jar.infolist():
            if entry.filename in {"plugin.yml", "META-INF/taboolib/env.properties", "META-INF/taboolib/version.properties"}:
                continue
            if entry.filename.startswith("taboolib/e2e/"):
                continue
            target_jar.writestr(entry, source_jar.read(entry.filename))

        plugin_yml = source_jar.read("plugin.yml").decode("utf-8")
        plugin_yml = re.sub(r"(?m)^name: .+$", f"name: {plugin_name}", plugin_yml)
        target_jar.writestr("plugin.yml", plugin_yml)
        target_jar.writestr(
            "META-INF/taboolib/version.properties",
            f"taboolib={version}\nskip-kotlin-relocate=true\nskip-taboolib-relocate=false\n",
        )
        env_properties = source_jar.read("META-INF/taboolib/env.properties").decode("utf-8")
        env_properties = re.sub(r"(?m)^debug=.+$", f"debug={str(debug).lower()}", env_properties)
        env_properties = re.sub(r"(?m)^force-download-in-dev=.+$", "force-download-in-dev=false", env_properties)
        env_properties = re.sub(r"(?m)^enable-isolated-classloader=.+$", f"enable-isolated-classloader={str(isolated).lower()}", env_properties)
        if modules != "full":
            env_properties = re.sub(r"(?m)^module=.+$", f"module={modules}", env_properties)
        target_jar.writestr("META-INF/taboolib/env.properties", env_properties)

        for index in range(class_count):
            internal_name = f"taboolib/benchmark/generated/PluginClass{index:05d}"
            target_jar.writestr(f"{internal_name}.class", minimal_class(internal_name))
        if padding_mb > 0:
            padding = zipfile.ZipInfo("benchmark-padding.bin")
            padding.compress_type = zipfile.ZIP_STORED
            target_jar.writestr(padding, bytes(padding_mb * 1024 * 1024))
        target_jar.writestr(
            "taboolib/benchmark/StartupPlugin.class",
            minimal_class("taboolib/benchmark/StartupPlugin", "taboolib/common/platform/Plugin"),
        )

    plugin_id = f"p{re.search(r'[0-9]+$', plugin_name).group()}"
    package = f"benchmark.shared.taboolib.{plugin_id}" if shared_group else f"benchmark.{plugin_id}.taboolib"
    subprocess.run(
        [
            java_command,
            "-cp",
            relocator_classpath,
            "BenchmarkRelocator",
            str(unrelocated),
            str(target),
            "taboolib",
            package,
        ],
        check=True,
    )
    unrelocated.unlink()
    rewrite_plugin_main(target, f"{package}.platform.BukkitPlugin")


def prepare_scenario(
    work_dir: Path,
    server_jar: Path,
    harness_jar: Path,
    plugin_count: int,
    class_count: int,
    padding_mb: int,
    version: str,
    modules: str,
    java_command: str,
    shared_group: bool,
    isolated: bool,
    debug: bool,
):
    setup_server_dir(work_dir, server_jar, harness_jar)
    plugins_dir = work_dir / "plugins"
    shutil.rmtree(plugins_dir)
    plugins_dir.mkdir()
    relocator_classpath = prepare_relocator(java_command) if plugin_count > 0 else ""
    for index in range(plugin_count):
        create_plugin(
            source=harness_jar,
            target=plugins_dir / f"TabooLibBench{index + 1:02d}.jar",
            plugin_name=f"TabooLibBench{index + 1:02d}",
            class_count=class_count,
            padding_mb=padding_mb,
            version=version,
            modules=modules,
            java_command=java_command,
            relocator_classpath=relocator_classpath,
            shared_group=shared_group,
            isolated=isolated,
            debug=debug,
        )


def run_server(
    work_dir: Path,
    java_command: str,
    timeout: int,
    log_file: Path,
    expected_plugins: int,
    jfr_settings: Path | None,
    debug: bool,
):
    command = [
        java_command,
        "-Dfile.encoding=UTF-8",
    ]
    if debug:
        command.append("-Dtaboolib.debug=true")
    if jfr_settings:
        command.append(f"-XX:StartFlightRecording=filename={log_file.with_suffix('.jfr')},settings={jfr_settings},dumponexit=true")
    command.extend(("-jar", "server.jar", "nogui"))
    process = subprocess.Popen(
        command,
        cwd=work_dir,
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        encoding="utf-8",
        errors="replace",
        bufsize=1,
    )
    output_queue = queue.Queue()

    def read_output():
        for line in process.stdout:
            output_queue.put(line)
        output_queue.put(None)

    threading.Thread(target=read_output, daemon=True).start()
    started = time.perf_counter()
    done_seconds = None
    server_done_seconds = None
    output = []
    deadline = started + timeout
    while time.perf_counter() < deadline:
        try:
            line = output_queue.get(timeout=0.1)
        except queue.Empty:
            if process.poll() is not None:
                break
            continue
        if line is None:
            break
        output.append(line)
        if "Done (" in line and done_seconds is None:
            done_seconds = time.perf_counter() - started
            server_done_match = re.search(r"Done \(([\d.]+)s\)", line)
            if server_done_match:
                server_done_seconds = float(server_done_match.group(1))
            time.sleep(1)
            process.stdin.write("stop\n")
            process.stdin.flush()

    if process.poll() is None:
        process.kill()
    process.wait(timeout=30)
    log_file.write_text("".join(output), encoding="utf-8")
    if done_seconds is None:
        raise RuntimeError(f"服务端未在 {timeout} 秒内完成启动，日志: {log_file}")
    enabled_plugins = set(re.findall(r"Enabling (TabooLibBench\d+)", "".join(output)))
    if len(enabled_plugins) != expected_plugins:
        raise RuntimeError(f"仅有 {len(enabled_plugins)}/{expected_plugins} 个基准插件成功启用，日志: {log_file}")

    plugin_timings = {}
    for line in output:
        plugin_match = re.search(r"\[([^]]+)] \[DEBUG]", line)
        if plugin_match:
            timings = plugin_timings.setdefault(plugin_match.group(1), {})
            for name, pattern in TIMING_PATTERNS.items():
                timing_match = pattern.search(line)
                if timing_match:
                    timings[name] = int(timing_match.group(1).replace(",", ""))
    return {"doneSeconds": done_seconds, "serverDoneSeconds": server_done_seconds, "plugins": plugin_timings}


def main():
    parser = argparse.ArgumentParser(description="TabooLib multi-plugin warm startup benchmark")
    parser.add_argument("--server", choices=("paper", "spigot"), default="paper")
    parser.add_argument("--version", default="1.20.4")
    parser.add_argument("--java", default="java")
    parser.add_argument("--plugins", type=int, default=1)
    parser.add_argument("--classes", type=int, default=0)
    parser.add_argument("--padding-mb", type=int, default=0)
    parser.add_argument("--modules", default="platform-bukkit-impl", help="逗号分隔的模块，full 保留 Harness 全模块")
    parser.add_argument("--shared-group", action="store_true", help="让所有插件共用 groupId，验证缓存隔离")
    parser.add_argument("--isolated", action="store_true", help="启用完全隔离类加载器")
    parser.add_argument("--debug", action="store_true", help="启用 TabooLib 调试日志与阶段计时")
    parser.add_argument("--jfr", action="store_true", help="为每次启动记录 JFR profile")
    parser.add_argument("--iterations", type=int, default=3)
    parser.add_argument("--timeout", type=int, default=180)
    parser.add_argument("--skip-build", action="store_true")
    args = parser.parse_args()

    if not args.skip_build:
        publish_local()
    version = current_version()
    refresh_local_checksums(version)
    harness_jar = build_harness()
    server_jar = prepare_server(args.version, args.server, args.java)
    module_set = "full" if args.modules == "full" else "minimal"
    group_set = "shared" if args.shared_group else "unique"
    isolation = "isolated" if args.isolated else "shared-loader"
    log_mode = "debug" if args.debug else "release"
    scenario = f"{args.server}-{args.version}-{module_set}-{group_set}-{isolation}-{log_mode}-p{args.plugins}-c{args.classes}-m{args.padding_mb}"
    work_dir = WORK_BASE_DIR / f"startup-{scenario}"
    BENCHMARK_DIR.mkdir(parents=True, exist_ok=True)
    prepare_scenario(
        work_dir=work_dir,
        server_jar=server_jar,
        harness_jar=harness_jar,
        plugin_count=args.plugins,
        class_count=args.classes,
        padding_mb=args.padding_mb,
        version=version,
        modules=args.modules,
        java_command=args.java,
        shared_group=args.shared_group,
        isolated=args.isolated,
        debug=args.debug,
    )

    jfr_settings = prepare_jfr_settings(args.java) if args.jfr else None
    run_server(work_dir, args.java, args.timeout, BENCHMARK_DIR / f"{scenario}-warmup.log", args.plugins, jfr_settings, args.debug)
    samples = []
    for iteration in range(args.iterations):
        samples.append(
            run_server(
                work_dir,
                args.java,
                args.timeout,
                BENCHMARK_DIR / f"{scenario}-{iteration + 1}.log",
                args.plugins,
                jfr_settings,
                args.debug,
            )
        )

    done_times = [sample["doneSeconds"] for sample in samples]
    server_done_times = [sample["serverDoneSeconds"] for sample in samples if sample["serverDoneSeconds"] is not None]
    result = {
        "scenario": scenario,
        "server": args.server,
        "minecraftVersion": args.version,
        "pluginCount": args.plugins,
        "classCountPerPlugin": args.classes,
        "paddingMbPerPlugin": args.padding_mb,
        "modules": args.modules,
        "sharedGroup": args.shared_group,
        "isolated": args.isolated,
        "debug": args.debug,
        "iterations": args.iterations,
        "doneSeconds": done_times,
        "medianDoneSeconds": statistics.median(done_times),
        "serverDoneSeconds": server_done_times,
        "medianServerDoneSeconds": statistics.median(server_done_times) if server_done_times else None,
        "samples": samples,
    }
    result_file = BENCHMARK_DIR / f"{scenario}.json"
    result_file.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False, indent=2))
    print(f"结果已写入 {result_file}")


if __name__ == "__main__":
    main()
