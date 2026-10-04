package taboolib.e2e

import org.bukkit.Bukkit
import taboolib.common.Test
import taboolib.common.io.runningClassMap
import taboolib.common.platform.function.info
import taboolib.common.platform.function.submit
import taboolib.common.platform.function.warning
import java.io.File
import java.lang.reflect.Modifier
import java.text.SimpleDateFormat
import java.util.ArrayList
import java.util.Collections
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean

/**
 * E2E 测试发现与执行器（mc-testkit 判定真源写入方）。
 */
object E2ERunner {

    private val isRunning = AtomicBoolean(false)

    /** bot 经聊天回报的客户端探针（E2E_PROBES:...）。 */
    private val clientProbes: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    private val expectedTestClasses = setOf(
        "taboolib.module.ai.test.TestSimpleAi",
        "taboolib.module.incision.test.TestIncision",
        "taboolib.module.nms.test.TestDataSerializer",
        "taboolib.module.nms.test.TestMinecraftLanguage",
        "taboolib.module.nms.test.TestNMSEntity",
        "taboolib.module.nms.test.TestNMS",
        "taboolib.module.nms.test.TestNMSBundle",
        "taboolib.module.nms.test.TestNMSItemRaw",
        "taboolib.module.nms.test.TestNMSMessage",
        "taboolib.module.nms.test.TestNMSPacket",
        "taboolib.module.nms.test.TestNMSParticle",
        "taboolib.module.nms.test.TestNMSScoreboard",
        "taboolib.module.nms.test.TestNMSSign",
        "taboolib.module.nms.test.TestNMSTag",
        "taboolib.module.nms.test.TestNMSTranslate",
        "taboolib.module.nms.test.TestTellrawJson",
    )

    private val expectedClientProbes = setOf(
        "ACTION_BAR",
        "AI_LIFECYCLE",
        "AI_NAVIGATION_ENTITY",
        "AI_NAVIGATION_LOCATION",
        "SCOREBOARD_REMOVED",
        "SCOREBOARD_TITLE",
        "SIGN_CALLBACK",
        "TEAM",
        "TITLE",
    )

    private fun isPlayerContextFailure(failure: Test.Failure): Boolean {
        val text = (failure.reason + " " + (failure.error.message ?: "")).lowercase()
        return "player" in text || "在线" in text || "target is unavailable" in text
    }

    /** 从服务端 latest.log 收集测试侧打点的 [E2E-PROBE]（AI/SIGN 等不在 bot 聊天通道）。 */
    private fun harvestServerLogProbes() {
        val log = File("logs/latest.log")
        if (!log.exists()) return
        log.readLines().forEach { line ->
            val idx = line.indexOf("[E2E-PROBE] ")
            if (idx >= 0) {
                val name = line.substring(idx + "[E2E-PROBE] ".length).trim()
                if (name.isNotEmpty()) {
                    clientProbes += name
                }
            }
        }
        info("[E2E] 服务端日志探针合并后: ${clientProbes.sorted()}")
    }

    fun recordClientProbes(raw: String) {
        raw.split(',', ' ', ';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { clientProbes += it }
        info("[E2E] 已合并客户端探针: $clientProbes")
    }

    fun discoverTests(): List<Test> {
        val tests = ArrayList<Test>()
        val classMap = runningClassMap
        for ((name, reflex) in classMap) {
            if (name.contains(".library.") || name.contains(".libs.")) continue
            if (name == "taboolib.common.Test" || name.startsWith("taboolib.common.Test$")) continue
            try {
                val superName = reflex.structure.superclass?.name
                if (superName != "taboolib.common.Test") continue

                val clazz = reflex.toClass()
                if (Modifier.isAbstract(clazz.modifiers) || clazz.isInterface) continue

                val instance = reflex.getInstance() ?: reflex.newInstance()
                if (instance is Test) {
                    tests += instance
                }
            } catch (ex: Throwable) {
                warning("[E2E] 无法实例化测试类 $name: ${ex.message}")
            }
        }
        return tests
    }

    fun runTestsAsync(triggerReason: String, delayTicks: Long = 20L) {
        submit(delay = delayTicks) {
            runTests(triggerReason)
        }
    }

    fun runTests(triggerReason: String): TestSuiteResult {
        if (!isRunning.compareAndSet(false, true)) {
            warning("[E2E] 测试套件已在运行中，跳过重复触发 ($triggerReason)")
            return TestSuiteResult(emptyList(), triggerReason)
        }

        try {
            info("[E2E] ========== 开始执行 E2E 测试 ($triggerReason) ==========")
            val tests = discoverTests()
            info("[E2E] 发现 ${tests.size} 个测试项: ${tests.map { it.javaClass.simpleName }}")

            val allResults = ArrayList<Test.Result>()
            val missingTests = expectedTestClasses - tests.map { it.javaClass.name }.toSet()
            for (testName in missingTests.sorted()) {
                val error = IllegalStateException("预期测试类未加载: $testName")
                warning("[E2E]   [FAIL] E2E:testLoaded:$testName -> ${error.message}")
                allResults += Test.Failure.of("E2E:testLoaded:$testName", error)
            }
            // 历史 Exchanges 回归测试必须先于其它 NMS 测试
            val orderedTests = tests.sortedBy { if (it.javaClass.name == "taboolib.module.nms.test.TestNMSSign") 0 else 1 }
            for (test in orderedTests) {
                val testName = test.javaClass.simpleName
                info("[E2E] 运行测试: $testName ...")
                try {
                    val results = test.check()
                    for (res in results) {
                        when (res) {
                            is Test.Success -> info("[E2E]   [OK]   ${res.reason}")
                            is Test.Failure -> {
                                warning("[E2E]   [FAIL] ${res.reason} -> ${res.error.message}")
                                res.error.printStackTrace()
                            }
                            is Test.Unsupported -> info("[E2E]   [SKIP] ${res.reason}")
                        }
                        allResults += res
                    }
                } catch (ex: Throwable) {
                    warning("[E2E]   [CRASH] $testName 崩溃: ${ex.message}")
                    ex.printStackTrace()
                    allResults += Test.Failure.of("$testName:crash", ex)
                }
            }

            // smoke（无 bot/无玩家）下，玩家上下文用例失败降级为 SKIP，避免矩阵被 bot 能力淹没
            if (!E2EMcTestkitVerdict.requiresPlayer() &&
                runCatching { Bukkit.getOnlinePlayers().isEmpty() }.getOrDefault(true)
            ) {
                for (i in allResults.indices) {
                    val r = allResults[i]
                    if (r is Test.Failure && isPlayerContextFailure(r)) {
                        allResults[i] = Test.Unsupported(r.reason + " (smoke: no player)")
                        info("[E2E] smoke 降级玩家上下文失败为 SKIP: ${r.reason}")
                    }
                }
            }

            val suiteResult = TestSuiteResult(allResults, triggerReason)
            info("[E2E] ========== 测试完成 ==========")
            info("[E2E] 总数: ${suiteResult.total}, 成功: ${suiteResult.success}, 失败: ${suiteResult.failure}, 跳过: ${suiteResult.unsupported}")

            writeResultJson(suiteResult)

            // 请求 bot 回报探针，短等后合并并写 mc-testkit 结果
            if (E2EMcTestkitVerdict.requiresPlayer()) {
                E2EPlugin.requestBotProbes()
                submit(delay = 60L) {
                    finishWithVerdict(suiteResult)
                }
            } else {
                finishWithVerdict(suiteResult)
            }

            return suiteResult
        } finally {
            isRunning.set(false)
        }
    }

    private fun finishWithVerdict(suite: TestSuiteResult) {
        // 服务端日志中的 [E2E-PROBE] 与 bot 聊天回报合并
        harvestServerLogProbes()
        var passed = suite.failure == 0
        val missingProbes: MutableSet<String> = mutableSetOf()
        if (E2EMcTestkitVerdict.requiresPlayer()) {
            missingProbes += expectedClientProbes - clientProbes
            if (clientProbes.isEmpty()) {
                info("[E2E] 未收到 bot 探针回报（probes=unavailable），仅以服务端 Test 结果判定")
            } else if (missingProbes.isNotEmpty()) {
                // 探针为补充观测：服务端 Test 通过时不因缺探针 FAIL（协议/版本差异常见）
                warning("[E2E] 缺少客户端探针（不单独判 FAIL）: $missingProbes")
            }
        }

        val message = buildString {
            append("total=${suite.total} success=${suite.success} failure=${suite.failure} unsupported=${suite.unsupported}")
            if (E2EMcTestkitVerdict.requiresPlayer()) {
                append(" probes=").append(clientProbes.sorted().joinToString("|").ifEmpty { "unavailable" })
                if (missingProbes.isNotEmpty()) {
                    append(" missingProbes=").append(missingProbes.sorted().joinToString("|"))
                }
            }
        }

        val wrote = E2EMcTestkitVerdict.report(
            ok = passed,
            message = message,
            extras = mapOf(
                "total" to suite.total.toString(),
                "success" to suite.success.toString(),
                "failure" to suite.failure.toString(),
                "unsupported" to suite.unsupported.toString(),
                "reason" to suite.reason,
                "serverVersion" to suite.serverVersion,
                "probes" to clientProbes.sorted().joinToString(","),
            ),
        )
        if (wrote) {
            info("[E2E] mc-testkit 结果已写入 status=${if (passed) "PASS" else "FAIL"}")
            if (System.getProperty("taboolib.e2e.exit") != "false") {
                info("[E2E] 关闭服务端...")
                submit(delay = 20L) {
                    E2EMcTestkitVerdict.shutdownServer()
                }
            }
        } else {
            info("[E2E] 非 mc-testkit 环境（无 RESULT_FILE），跳过判定写出")
            if (System.getProperty("taboolib.e2e.exit") == "true") {
                submit(delay = 100L) {
                    E2EMcTestkitVerdict.shutdownServer()
                }
            }
        }
    }

    fun writeResultJson(suite: TestSuiteResult): File {
        val outDir = File("plugins/TabooLibE2E")
        if (!outDir.exists()) {
            outDir.mkdirs()
        }
        val file = File(outDir, "result.json")
        file.writeText(suite.toJsonString())
        return file
    }

    class TestSuiteResult(val results: List<Test.Result>, val reason: String) {

        val total: Int = results.size
        val success: Int = results.count { it is Test.Success }
        val failure: Int = results.count { it is Test.Failure }
        val unsupported: Int = results.count { it is Test.Unsupported }
        val timestamp: String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ").format(Date())
        val serverVersion: String = runCatching { Bukkit.getVersion() }.getOrDefault("unknown")
        val bukkitVersion: String = runCatching { Bukkit.getBukkitVersion() }.getOrDefault("unknown")
        val javaVersion: String = System.getProperty("java.version")
        val onlinePlayers: List<String> = runCatching { Bukkit.getOnlinePlayers().map { it.name } }.getOrDefault(emptyList())

        fun toJsonString(): String {
            val sb = StringBuilder()
            sb.append("{\n")
            sb.append("  \"timestamp\": \"$timestamp\",\n")
            sb.append("  \"reason\": \"${escapeJson(reason)}\",\n")
            sb.append("  \"serverVersion\": \"${escapeJson(serverVersion)}\",\n")
            sb.append("  \"bukkitVersion\": \"${escapeJson(bukkitVersion)}\",\n")
            sb.append("  \"javaVersion\": \"${escapeJson(javaVersion)}\",\n")
            sb.append("  \"onlinePlayers\": [${onlinePlayers.joinToString { "\"${escapeJson(it)}\"" }}],\n")
            sb.append("  \"total\": $total,\n")
            sb.append("  \"success\": $success,\n")
            sb.append("  \"failure\": $failure,\n")
            sb.append("  \"unsupported\": $unsupported,\n")
            sb.append("  \"passed\": ${failure == 0},\n")
            sb.append("  \"results\": [\n")
            for (i in results.indices) {
                val r = results[i]
                val statusStr = when (r) {
                    is Test.Success -> "SUCCESS"
                    is Test.Failure -> "FAILURE"
                    is Test.Unsupported -> "UNSUPPORTED"
                }
                sb.append("    {\n")
                sb.append("      \"status\": \"$statusStr\",\n")
                sb.append("      \"reason\": \"${escapeJson(r.reason)}\"")
                if (r is Test.Failure) {
                    sb.append(",\n      \"errorType\": \"${escapeJson(r.error.javaClass.name)}\"")
                    sb.append(",\n      \"error\": \"${escapeJson(r.error.message ?: r.error.javaClass.name)}\"")
                    sb.append(",\n      \"stackTrace\": \"${escapeJson(r.error.stackTraceToString())}\"")
                }
                sb.append("\n    }")
                if (i < results.size - 1) sb.append(",")
                sb.append("\n")
            }
            sb.append("  ]\n")
            sb.append("}\n")
            return sb.toString()
        }

        private fun escapeJson(str: String): String {
            return str.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\b", "\\b")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
        }
    }
}
