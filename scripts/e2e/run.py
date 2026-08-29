"""
TabooLib 端到端 (E2E) 自动化兼容性测试运行器

支持工作流程：
1. 本地发布 TabooLib 模块到 ~/.m2/repository (-PdevLocal)
2. 编译 userspace:e2e-harness 测试插件
3. 准备目标 Minecraft 版本服务端（Paper / Spigot），自动下载核心并生成无头配置（EULA、离线模式、禁用无用世界）
4. 启动服务端，加载 TabooLib E2E Harness
5. 通过 Mineflayer 真实协议客户端进服，触发需要 Player 上下文的测试
6. 自动执行 Test.check() 扫描全量测试用例并输出 plugins/TabooLibE2E/result.json
7. 自动关闭服务端，输出格式化汇总报告
"""

import argparse
import json
import queue
import re
import shutil
import subprocess
import sys
import threading
import time
import urllib.request
import zipfile
from pathlib import Path

ROOT_DIR = Path(__file__).resolve().parent.parent.parent
M2_REPO = Path.home() / ".m2" / "repository"
CACHE_DIR = ROOT_DIR / ".e2e" / "cache"
WORK_BASE_DIR = ROOT_DIR / ".e2e" / "run"
BOT_DIR = ROOT_DIR / "scripts" / "e2e" / "bot"
EXPECTED_CLIENT_PROBES = {
    "ACTION_BAR",
    "AI_LIFECYCLE",
    "AI_NAVIGATION_ENTITY",
    "AI_NAVIGATION_LOCATION",
    "SCOREBOARD_REMOVED",
    "SCOREBOARD_TITLE",
    "SIGN_CALLBACK",
    "TEAM",
    "TITLE",
}

PAPER_API_URL = "https://fill.papermc.io/v3/projects/paper"
BUILDTOOLS_URL = "https://hub.spigotmc.org/jenkins/job/BuildTools/lastSuccessfulBuild/artifact/target/BuildTools.jar"


def print_step(title: str):
    print(f"\n\033[1;34m[E2E] >>> {title}\033[0m")


def print_success(msg: str):
    print(f"\033[1;32m[E2E] [OK] {msg}\033[0m")


def print_warn(msg: str):
    print(f"\033[1;33m[E2E] [WARN] {msg}\033[0m")


def print_error(msg: str):
    print(f"\033[1;31m[E2E] [FAIL] {msg}\033[0m")


def version_key(version: str) -> tuple:
    parts = []
    for part in version.split("."):
        match = re.match(r"\d+", part)
        if match is None:
            break
        parts.append(int(match.group()))
    return tuple(parts)


def select_bot_version(server_version: str, requested_version: str = None) -> str:
    if requested_version:
        return requested_version
    try:
        latest_version = subprocess.check_output(
            ["node", "-e", "console.log(require('./node_modules/mineflayer/lib/version').latestSupportedVersion)"],
            cwd=BOT_DIR,
            text=True,
            encoding="utf-8",
        ).strip()
    except (OSError, subprocess.SubprocessError):
        latest_version = "1.21.4"
        print_warn(f"无法读取 Mineflayer 协议上限，按内置上限 {latest_version} 选择客户端协议。")
    if version_key(server_version) > version_key(latest_version):
        print_warn(f"Mineflayer 最高支持 {latest_version}，将自动安装 Via 并桥接到 {server_version}。")
        return latest_version
    return server_version


def publish_local():
    print_step("正在发布 TabooLib 模块到本地 Maven 仓库 (publishToMavenLocal -PdevLocal)...")
    gradle_cmd = str(ROOT_DIR / ("gradlew.bat" if sys.platform == "win32" else "gradlew"))
    res = subprocess.run([gradle_cmd, "publishToMavenLocal", "-PdevLocal", "-x", "test"], cwd=ROOT_DIR)
    if res.returncode != 0:
        print_error("publishToMavenLocal 执行失败")
        sys.exit(1)
    print_success("本地 Maven 仓库发布完成")


def build_harness() -> Path:
    print_step("正在编译 userspace:e2e-harness 测试插件...")
    gradle_cmd = str(ROOT_DIR / ("gradlew.bat" if sys.platform == "win32" else "gradlew"))
    libs_dir = ROOT_DIR / "userspace" / "e2e-harness" / "build" / "libs"
    if libs_dir.exists():
        shutil.rmtree(libs_dir)
    res = subprocess.run([gradle_cmd, ":userspace:e2e-harness:shadowJar", "-x", "test"], cwd=ROOT_DIR)
    if res.returncode != 0:
        print_error("e2e-harness 编译失败")
        sys.exit(1)

    jars = [f for f in libs_dir.glob("*.jar") if not f.name.endswith("-sources.jar")]
    if not jars:
        print_error(f"未找到生成的 harness jar 包 (在 {libs_dir})")
        sys.exit(1)

    target_jar = sorted(jars, key=lambda x: x.stat().st_size, reverse=True)[0]
    print_success(f"已生成 Harness 插件: {target_jar.name} ({target_jar.stat().st_size // 1024} KB)")
    return target_jar


def download_paper(mc_version: str) -> Path:
    CACHE_DIR.mkdir(parents=True, exist_ok=True)
    cache_jar = CACHE_DIR / f"paper-{mc_version}.jar"
    if cache_jar.exists() and cache_jar.stat().st_size > 10 * 1024 * 1024:
        print_success(f"复用已缓存的 Paper 核心: {cache_jar.name}")
        return cache_jar

    print_step(f"正在从 Paper API 查询并下载 {mc_version} 服务端核心...")
    try:
        req = urllib.request.Request(f"{PAPER_API_URL}/versions/{mc_version}/builds", headers={"User-Agent": "TabooLib-E2E"})
        with urllib.request.urlopen(req) as resp:
            builds = json.loads(resp.read().decode())
        if not builds:
            raise RuntimeError(f"版本 {mc_version} 未找到任何可用构建")
        latest_build = next((build for build in builds if build.get("channel") == "STABLE"), builds[0])
        download = latest_build["downloads"]["server:default"]
        download_url = download["url"]
        print(f"下载链接: {download_url}")
        req_dl = urllib.request.Request(download_url, headers={"User-Agent": "TabooLib-E2E"})
        with urllib.request.urlopen(req_dl) as response, open(cache_jar, 'wb') as out_file:
            shutil.copyfileobj(response, out_file)
        print_success(f"Paper {mc_version} (Build #{latest_build['id']}) 下载成功: {cache_jar.stat().st_size // 1024 // 1024} MB")
        return cache_jar
    except Exception as e:
        print_error(f"下载 Paper {mc_version} 失败: {e}")
        print_warn("如果目标版本尚无 Paper 构建，可通过 --local-jar 指定核心")
        sys.exit(1)


def build_spigot(mc_version: str, java_command: str = "java") -> Path:
    CACHE_DIR.mkdir(parents=True, exist_ok=True)
    cache_jar = CACHE_DIR / f"spigot-{mc_version}.jar"
    if cache_jar.exists() and cache_jar.stat().st_size > 10 * 1024 * 1024:
        print_success(f"复用已缓存的 Spigot 核心: {cache_jar.name}")
        return cache_jar

    build_tools = CACHE_DIR / "BuildTools.jar"
    if not build_tools.exists() or build_tools.stat().st_size < 1024 * 1024:
        print_step("正在下载 Spigot BuildTools...")
        req = urllib.request.Request(BUILDTOOLS_URL, headers={"User-Agent": "TabooLib-E2E"})
        with urllib.request.urlopen(req) as response, open(build_tools, "wb") as out_file:
            shutil.copyfileobj(response, out_file)

    build_dir = CACHE_DIR / "buildtools" / mc_version
    build_dir.mkdir(parents=True, exist_ok=True)
    print_step(f"正在通过 BuildTools 构建 Spigot {mc_version}...")
    res = subprocess.run(
        [
            java_command,
            "-jar",
            str(build_tools),
            "--rev",
            mc_version,
            "--compile",
            "SPIGOT",
            "--nogui",
            "--output-dir",
            str(CACHE_DIR),
            "--final-name",
            cache_jar.name,
        ],
        cwd=build_dir,
    )
    if res.returncode != 0:
        print_error(f"Spigot {mc_version} 构建失败")
        sys.exit(1)
    if not cache_jar.exists():
        print_error(f"BuildTools 未生成 Spigot {mc_version} 核心")
        sys.exit(1)
    print_success(f"Spigot {mc_version} 构建成功: {cache_jar.stat().st_size // 1024 // 1024} MB")
    return cache_jar


def prepare_server(mc_version: str, distribution: str, java_command: str = "java") -> Path:
    if distribution == "paper":
        return download_paper(mc_version)
    return build_spigot(mc_version, java_command)


def download_viaversion() -> Path:
    CACHE_DIR.mkdir(parents=True, exist_ok=True)
    via_jar = CACHE_DIR / "ViaVersion.jar"
    if via_jar.exists() and via_jar.stat().st_size > 1024 * 1024:
        return via_jar
    print_step("正在下载 ViaVersion (用于桥接协议版本保证 Mineflayer 成功进服)...")
    try:
        req = urllib.request.Request("https://api.modrinth.com/v2/project/viaversion/version", headers={"User-Agent": "TabooLib-E2E"})
        with urllib.request.urlopen(req, timeout=10) as resp:
            data = json.loads(resp.read().decode())
        dl_url = data[0]["files"][0]["url"]
        print(f"ViaVersion 下载链接: {dl_url}")
        req_dl = urllib.request.Request(dl_url, headers={"User-Agent": "Mozilla/5.0"})
        with urllib.request.urlopen(req_dl) as response, open(via_jar, 'wb') as out_file:
            shutil.copyfileobj(response, out_file)
        print_success(f"ViaVersion 下载成功: {via_jar.stat().st_size // 1024} KB")
        return via_jar
    except Exception as e:
        print_warn(f"ViaVersion 下载失败 ({e})，将尝试直连")
        return None


def download_viabackwards() -> Path:
    CACHE_DIR.mkdir(parents=True, exist_ok=True)
    via_jar = CACHE_DIR / "ViaBackwards.jar"
    if via_jar.exists() and via_jar.stat().st_size > 1024 * 1024:
        return via_jar
    print_step("正在下载 ViaBackwards (用于支持旧版客户端/Mineflayer 跨版本进服)...")
    try:
        req = urllib.request.Request("https://api.modrinth.com/v2/project/viabackwards/version", headers={"User-Agent": "TabooLib-E2E"})
        with urllib.request.urlopen(req, timeout=10) as resp:
            data = json.loads(resp.read().decode())
        dl_url = data[0]["files"][0]["url"]
        print(f"ViaBackwards 下载链接: {dl_url}")
        req_dl = urllib.request.Request(dl_url, headers={"User-Agent": "Mozilla/5.0"})
        with urllib.request.urlopen(req_dl) as response, open(via_jar, 'wb') as out_file:
            shutil.copyfileobj(response, out_file)
        print_success(f"ViaBackwards 下载成功: {via_jar.stat().st_size // 1024} KB")
        return via_jar
    except Exception as e:
        print_warn(f"ViaBackwards 下载失败 ({e})")
        return None


def setup_server_dir(work_dir: Path, server_jar: Path, harness_jar: Path, port: int = 25565, install_via: bool = False):
    if work_dir.exists():
        # Paper 的服务端与 Mojang 依赖下载成本较高，仅清理会影响测试隔离的运行数据。
        persistent_dirs = {"cache", "libraries", "versions"}
        for path in work_dir.iterdir():
            if path.name in persistent_dirs:
                continue
            if path.is_dir():
                shutil.rmtree(path)
            else:
                path.unlink()
    work_dir.mkdir(parents=True, exist_ok=True)

    # 上一轮被外部超时终止时可能留下未写完的 Mojang JAR，启动前主动丢弃。
    runtime_cache = work_dir / "cache"
    if runtime_cache.exists():
        for mojang_jar in runtime_cache.glob("mojang_*.jar"):
            if not zipfile.is_zipfile(mojang_jar):
                mojang_jar.unlink()

    # 本地开发版坐标不变，每轮必须丢弃 TabooLib 模块缓存，避免新 API 搭配旧实现。
    taboolib_cache = runtime_cache / "taboolib"
    if taboolib_cache.exists():
        shutil.rmtree(taboolib_cache)
    taboolib_libraries = work_dir / "libraries" / "io" / "izzel" / "taboolib"
    if taboolib_libraries.exists():
        shutil.rmtree(taboolib_libraries)

    # 拷贝服务端 jar
    shutil.copy2(server_jar, work_dir / "server.jar")

    # 插件目录
    plugins_dir = work_dir / "plugins"
    plugins_dir.mkdir(parents=True, exist_ok=True)
    shutil.copy2(harness_jar, plugins_dir / "TabooLibE2E.jar")

    # Mineflayer 无目标协议时才桥接，旧版服务端可继续使用其原生 Java 运行时。
    if install_via:
        via_jar = download_viaversion()
        if via_jar and via_jar.exists():
            shutil.copy2(via_jar, plugins_dir / "ViaVersion.jar")
        viaback_jar = download_viabackwards()
        if viaback_jar and viaback_jar.exists():
            shutil.copy2(viaback_jar, plugins_dir / "ViaBackwards.jar")

    # eula.txt
    (work_dir / "eula.txt").write_text("eula=true\n", encoding="utf-8")

    # server.properties
    server_props = f"""server-port={port}
online-mode=false
enable-rcon=false
enable-query=false
sync-chunk-writes=false
view-distance=4
simulation-distance=4
spawn-protection=0
generate-structures=false
allow-nether=false
gamemode=survival
difficulty=peaceful
max-players=20
motd=TabooLib E2E Test Server
"""
    (work_dir / "server.properties").write_text(server_props, encoding="utf-8")

    # bukkit.yml 关掉不需要的输出和 update 检查
    bukkit_yml = """settings:
  allow-end: false
  warn-on-overload: false
  update-folder: update
  ping-packet-limit: 100
"""
    (work_dir / "bukkit.yml").write_text(bukkit_yml, encoding="utf-8")


def write_varint(val: int) -> bytes:
    total = b""
    while True:
        byte = val & 0x7F
        val >>= 7
        if val != 0:
            total += bytes([byte | 0x80])
        else:
            total += bytes([byte])
            break
    return total


def write_string(s: str) -> bytes:
    encoded = s.encode("utf-8")
    return write_varint(len(encoded)) + encoded


def launch_mineflayer_bot(host: str = "127.0.0.1", port: int = 25565, bot_name: str = "E2EBot", version: str = "1.21.4") -> subprocess.Popen:
    bot_script = BOT_DIR / "bot.js"
    cmd = ["node", str(bot_script), host, str(port), bot_name]
    if version:
        cmd.append(version)
    print_step(f"启动 Mineflayer 真实客户端进程 ({bot_name} -> {host}:{port}, 协议版本: {version})...")
    proc = subprocess.Popen(cmd, cwd=BOT_DIR, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace")
    return proc


def pump_process_output(process: subprocess.Popen, output: queue.Queue):
    """持续读取子进程输出，避免主线程在 readline 上阻塞而使超时失效。"""
    for line in process.stdout:
        output.put(line)


def run_e2e_server(
    work_dir: Path,
    mc_version: str,
    java_command: str = "java",
    timeout: int = 180,
    wait_player: bool = False,
    bot_version: str = None,
) -> bool:
    print_step(f"正在启动 Minecraft 服务端 (版本: {mc_version}, 工作目录: {work_dir})...")

    # JVM 参数：开启 dev 模式、自动测试、测试完成后自动关闭
    jvm_args = [
        java_command,
        "-Xmx2G",
        "-Xms1G",
        "-Dtaboolib.dev=true",
        "-Dtaboolib.e2e.auto=true",
        "-Dtaboolib.e2e.exit=true",
        f"-Dtaboolib.e2e.wait-player={'true' if wait_player else 'false'}",
        "-Dfile.encoding=UTF-8",
        "-jar",
        "server.jar",
        "nogui"
    ]

    process = subprocess.Popen(
        jvm_args,
        cwd=work_dir,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        encoding="utf-8",
        errors="replace"
    )

    start_time = time.time()
    server_ready = False
    client_probes = set()
    bot_proc = None
    bot_output_thread = None
    result_json = work_dir / "plugins" / "TabooLibE2E" / "result.json"
    output = queue.Queue()
    output_thread = threading.Thread(target=pump_process_output, args=(process, output), daemon=True)
    output_thread.start()

    print("[服务端控制台实时日志]")
    while True:
        if process.poll() is not None:
            break

        try:
            line = output.get(timeout=0.1)
            line_str = line.strip()
            if server_ready or any(k in line_str for k in ["[E2E]", "Done (", "TabooLib", "ERROR", "Exception", "WARN"]):
                print(f"  | {line_str}")
            if "[E2E-PROBE] " in line_str:
                client_probes.add(line_str.split("[E2E-PROBE] ", 1)[1].strip())

            if "Done (" in line_str and not server_ready:
                server_ready = True
                print_success("服务端启动就绪 (Done)")
                if wait_player:
                    print_step("正在通过 Mineflayer 派送真实协议客户端进服...")
                    bot_proc = launch_mineflayer_bot(port=25565, version=bot_version or mc_version)
                    bot_output_thread = threading.Thread(target=pump_process_output, args=(bot_proc, output), daemon=True)
                    bot_output_thread.start()
        except queue.Empty:
            pass

        if time.time() - start_time > timeout:
            print_error(f"测试超时 ({timeout} 秒)，强行终止服务端进程")
            process.kill()
            break

    try:
        process.wait(timeout=10)
    except subprocess.TimeoutExpired:
        process.kill()
    output_thread.join(timeout=5)

    if bot_proc and bot_proc.poll() is None:
        bot_proc.kill()
        bot_proc.wait(timeout=5)
    if bot_output_thread:
        bot_output_thread.join(timeout=1)

    while True:
        try:
            line_str = output.get_nowait().strip()
            if server_ready or any(k in line_str for k in ["[E2E]", "Done (", "TabooLib", "ERROR", "Exception", "WARN"]):
                print(f"  | {line_str}")
            if "[E2E-PROBE] " in line_str:
                client_probes.add(line_str.split("[E2E-PROBE] ", 1)[1].strip())
        except queue.Empty:
            break

    # 服务端关闭时 stdout 线程可能晚于进程状态结束，最终日志用于补齐服务端异步探针。
    latest_log = work_dir / "logs" / "latest.log"
    if latest_log.exists():
        for line in latest_log.read_text(encoding="utf-8", errors="replace").splitlines():
            if "[E2E-PROBE] " in line:
                client_probes.add(line.split("[E2E-PROBE] ", 1)[1].strip())
    print_step(f"已采集 E2E 探针: {sorted(client_probes)}")

    # 检查结果
    if not result_json.exists():
        print_error("未找到测试结果文件 plugins/TabooLibE2E/result.json")
        return False

    try:
        report = json.loads(result_json.read_text(encoding="utf-8"))
        if wait_player:
            for probe in sorted(EXPECTED_CLIENT_PROBES):
                received = probe in client_probes
                probe_result = {
                    "status": "SUCCESS" if received else "FAILURE",
                    "reason": f"E2E:clientProbe:{probe}",
                }
                if not received:
                    probe_result["error"] = f"未收到客户端探针 {probe}"
                report["results"].append(probe_result)
                report["total"] += 1
                if received:
                    report["success"] += 1
                else:
                    report["failure"] += 1
                    report["passed"] = False
            result_json.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        print_step("========== E2E 测试汇总报告 ==========")
        print(f"目标版本 : {mc_version}")
        print(f"服务端   : {report.get('serverVersion')}")
        print(f"Bukkit   : {report.get('bukkitVersion')}")
        print(f"Java     : {report.get('javaVersion')}")
        print(f"在线玩家 : {', '.join(report.get('onlinePlayers', []))}")
        print(f"触发原因 : {report.get('reason')}")
        print(f"测试总数 : {report.get('total')}")
        print(f"成功通过 : \033[1;32m{report.get('success')}\033[0m")
        print(f"测试失败 : \033[1;{ '31' if report.get('failure', 0) > 0 else '32' }m{report.get('failure')}\033[0m")
        print(f"版本跳过 : {report.get('unsupported')}")
        print("---------------------------------------")
        for res in report.get("results", []):
            st = res.get("status")
            rs = res.get("reason")
            err = res.get("error", "")
            if st == "SUCCESS":
                print(f" \033[1;32m[PASS]\033[0m {rs}")
            elif st == "FAILURE":
                print(f" \033[1;31m[FAIL]\033[0m {rs} -> {err}")
            else:
                print(f" \033[1;33m[SKIP]\033[0m {rs}")
        print("=======================================")
        return report.get("passed", False)
    except Exception as e:
        print_error(f"解析测试报告失败: {e}")
        return False


def main():
    parser = argparse.ArgumentParser(description="TabooLib E2E Version Test Runner")
    parser.add_argument("-mc", "--version", default="26.2", help="Target Minecraft Version (e.g. 26.2, 26.1.2, 1.21.4)")
    parser.add_argument("--server", choices=("paper", "spigot"), default="paper", help="Server distribution used by the E2E run")
    parser.add_argument("--skip-publish", action="store_true", help="Skip publishToMavenLocal step")
    parser.add_argument("--wait-player", action="store_true", default=True, help="Wait for Mineflayer bot player to join before running tests")
    parser.add_argument("--no-wait-player", dest="wait_player", action="store_false", help="Run tests immediately on ACTIVE without waiting for player")
    parser.add_argument("--timeout", type=int, default=180, help="Test timeout in seconds")
    parser.add_argument("--java", default="java", help="Java executable used to start the server")
    parser.add_argument("--bot-version", default=None, help="Mineflayer protocol version; defaults to the target server version")
    parser.add_argument("--via", action="store_true", help="Install ViaVersion and ViaBackwards for a bridged bot protocol")
    parser.add_argument("--local-jar", type=str, default=None, help="Use existing local server jar path")

    args = parser.parse_args()

    if not args.skip_publish:
        publish_local()

    harness_jar = build_harness()

    if args.local_jar:
        server_jar = Path(args.local_jar).resolve()
        if not server_jar.exists():
            print_error(f"指定的服务端 jar 不存在: {server_jar}")
            sys.exit(1)
    else:
        server_jar = prepare_server(args.version, args.server, args.java)

    work_dir = WORK_BASE_DIR / f"{args.server}-{args.version}"
    bot_version = select_bot_version(args.version, args.bot_version)
    setup_server_dir(work_dir, server_jar, harness_jar, install_via=args.via or bot_version != args.version)

    passed = run_e2e_server(
        work_dir=work_dir,
        mc_version=args.version,
        java_command=args.java,
        timeout=args.timeout,
        wait_player=args.wait_player,
        bot_version=bot_version,
    )
    if not passed:
        print_error(f"Minecraft {args.version} E2E 测试未全部通过！")
        sys.exit(1)
    else:
        print_success(f"Minecraft {args.version} E2E 测试全部顺利通过！")
        sys.exit(0)


if __name__ == "__main__":
    main()
