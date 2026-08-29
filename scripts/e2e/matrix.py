"""按 NMS 兼容性断点运行 Paper/Spigot E2E 矩阵。"""

import argparse
import json
import os
import subprocess
import sys
from pathlib import Path

from run import ROOT_DIR, WORK_BASE_DIR, publish_local


# 每个主版本取一个稳定补丁；同一主版本内存在独立 NMS 分支时追加断点。
COMPATIBILITY_MATRIX = (
    ("1.12.2", 8, "1.12 API 下限与 LONG_ARRAY"),
    ("1.13.2", 8, "扁平化、译名与记分板构造"),
    ("1.14.4", 8, "AI selector 字段与地图包"),
    ("1.15.2", 8, "NBT 工厂方法"),
    ("1.16.5", 8, "聊天组件类型"),
    ("1.17.1", 17, "universal CraftBukkit 边界"),
    ("1.18.2", 17, "方法映射与发包方法"),
    ("1.19.2", 17, "1.19 主版本映射"),
    ("1.19.3", 17, "Bukkit Translatable"),
    ("1.19.4", 17, "BundlePacket"),
    ("1.20.1", 17, "1.20 实体、牌子与地图包"),
    ("1.20.2", 17, "AdvancementHolder 与粒子参数"),
    ("1.20.4", 17, "1.20.3 记分板与组件序列化断点"),
    ("1.20.6", 21, "1.20.5 Mojang mapping、DataComponent 与新序列化器"),
    ("1.21.1", 21, "1.21 MapId"),
    ("1.21.5", 21, "NBT value 与 AdventureModePredicate"),
    ("1.21.8", 21, "1.21.6 ItemStack CODEC"),
    ("26.1.2", 25, "非混淆 NMS 边界"),
    ("26.2", 25, "CraftMirror、BlockPredicate 与 TeamColor"),
    ("26.3", 25, "SignTextSlot 与 GoalSelector getter"),
)


def java_command(major: int) -> str:
    configured = os.environ.get(f"TABOOLIB_E2E_JAVA_{major}")
    if configured:
        return configured
    if sys.platform == "win32":
        executable = Path(f"C:/Program Files/Zulu/zulu-{major}/bin/java.exe")
        if executable.exists():
            return str(executable)
    return "java"


def select_matrix(from_version: str, through_version: str):
    versions = [entry[0] for entry in COMPATIBILITY_MATRIX]
    start = versions.index(from_version) if from_version else 0
    end = versions.index(through_version) + 1 if through_version else len(versions)
    return COMPATIBILITY_MATRIX[start:end]


def existing_result(distribution: str, version: str):
    result_file = WORK_BASE_DIR / f"{distribution}-{version}" / "plugins" / "TabooLibE2E" / "result.json"
    if not result_file.exists():
        return None
    try:
        report = json.loads(result_file.read_text(encoding="utf-8"))
        return report if report.get("passed") else None
    except (OSError, ValueError):
        return None


def run_target(distribution: str, version: str, java_major: int, timeout: int):
    log_dir = ROOT_DIR / ".e2e" / "matrix-logs"
    log_dir.mkdir(parents=True, exist_ok=True)
    log_file = log_dir / f"{distribution}-{version}.log"
    command = [
        sys.executable,
        str(ROOT_DIR / "scripts" / "e2e" / "run.py"),
        "-mc",
        version,
        "--server",
        distribution,
        "--skip-publish",
        "--java",
        java_command(java_major),
        "--timeout",
        str(timeout),
    ]
    process = subprocess.run(
        command,
        cwd=ROOT_DIR,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    log_file.write_text(process.stdout, encoding="utf-8")
    report = existing_result(distribution, version)
    return process.returncode, report, log_file, process.stdout


def main():
    parser = argparse.ArgumentParser(description="TabooLib NMS compatibility-breakpoint E2E matrix")
    parser.add_argument("--server", choices=("paper", "spigot", "both"), default="both")
    parser.add_argument("--from-version", choices=tuple(entry[0] for entry in COMPATIBILITY_MATRIX))
    parser.add_argument("--through-version", choices=tuple(entry[0] for entry in COMPATIBILITY_MATRIX))
    parser.add_argument("--skip-publish", action="store_true")
    parser.add_argument("--resume", action="store_true", help="Skip targets with an existing passing result")
    parser.add_argument("--timeout", type=int, default=300)
    args = parser.parse_args()

    if not args.skip_publish:
        publish_local()

    distributions = ("paper", "spigot") if args.server == "both" else (args.server,)
    selected = select_matrix(args.from_version, args.through_version)
    results = []
    total_targets = len(selected) * len(distributions)
    current = 0

    for version, java_major, reason in selected:
        for distribution in distributions:
            current += 1
            previous = existing_result(distribution, version) if args.resume else None
            if previous:
                print(f"[MATRIX] [{current}/{total_targets}] PASS {distribution} {version} (resume)", flush=True)
                results.append({"server": distribution, "version": version, "reason": reason, "status": "PASS", "report": previous})
                continue

            print(f"[MATRIX] [{current}/{total_targets}] RUN  {distribution} {version} (Java {java_major}: {reason})", flush=True)
            return_code, report, log_file, output = run_target(distribution, version, java_major, args.timeout)
            passed = return_code == 0 and report is not None
            status = "PASS" if passed else "FAIL"
            print(f"[MATRIX] [{current}/{total_targets}] {status} {distribution} {version} -> {log_file}", flush=True)
            if not passed:
                tail = output.splitlines()[-20:]
                print("\n".join(tail), flush=True)
            results.append({"server": distribution, "version": version, "reason": reason, "status": status, "report": report})

    passed = sum(result["status"] == "PASS" for result in results)
    matrix_report = {
        "total": len(results),
        "passed": passed,
        "failed": len(results) - passed,
        "results": results,
    }
    report_file = ROOT_DIR / ".e2e" / "matrix-result.json"
    report_file.write_text(json.dumps(matrix_report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"[MATRIX] 完成: {passed}/{len(results)}，汇总: {report_file}", flush=True)
    sys.exit(0 if passed == len(results) else 1)


if __name__ == "__main__":
    main()
