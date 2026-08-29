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
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean

/**
 * E2E 测试发现与执行器
 *
 * @author sky
 */
object E2ERunner {

    private val isRunning = AtomicBoolean(false)
    private val expectedTestClasses = setOf(
        "taboolib.module.ai.test.TestSimpleAi",
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

    /**
     * 发现所有已加载的 Test 实现类/对象
     */
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

    /**
     * 异步运行测试，并在完成后根据系统属性决定是否关闭服务端
     *
     * @param triggerReason 本次执行的触发来源
     * @param delayTicks 开始执行前等待的服务端 tick 数
     */
    fun runTestsAsync(triggerReason: String, delayTicks: Long = 20L) {
        submit(delay = delayTicks) {
            runTests(triggerReason)
        }
    }

    /**
     * 同步执行测试套件并写入结果
     *
     * @param triggerReason 本次执行的触发来源
     * @return 测试套件结果
     */
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
            // 历史 Exchanges 回归测试必须先于其它 NMS 测试，确保首次加载代理类时仍使用模拟状态。
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

            val suiteResult = TestSuiteResult(allResults, triggerReason)
            info("[E2E] ========== 测试完成 ==========")
            info("[E2E] 总数: ${suiteResult.total}, 成功: ${suiteResult.success}, 失败: ${suiteResult.failure}, 跳过: ${suiteResult.unsupported}")

            val resultFile = writeResultJson(suiteResult)
            info("[E2E] 结果已写入: ${resultFile.absolutePath}")

            // 写入完成标记文件供外部脚本感知
            val doneFile = File("e2e-done.marker")
            doneFile.writeText(if (suiteResult.failure == 0) "SUCCESS" else "FAILURE")

            if (System.getProperty("taboolib.e2e.exit") == "true") {
                info("[E2E] taboolib.e2e.exit=true，5 秒后关闭服务端...")
                submit(delay = 100L) {
                    try {
                        val serverCls = Class.forName("org.bukkit.Bukkit")
                        val shutdownMethod = serverCls.getMethod("shutdown")
                        shutdownMethod.invoke(null)
                    } catch (ex: Throwable) {
                        System.exit(if (suiteResult.failure == 0) 0 else 1)
                    }
                }
            }

            return suiteResult
        } finally {
            isRunning.set(false)
        }
    }

    /**
     * 将结果写出为 JSON 文件
     *
     * @param suite 测试套件结果
     * @return 写出的结果文件
     */
    fun writeResultJson(suite: TestSuiteResult): File {
        val outDir = File("plugins/TabooLibE2E")
        if (!outDir.exists()) {
            outDir.mkdirs()
        }
        val file = File(outDir, "result.json")
        file.writeText(suite.toJsonString())
        return file
    }

    /**
     * 测试套件汇总结果
     *
     * @property results 全部测试结果
     * @property reason 本次执行的触发来源
     */
    class TestSuiteResult(val results: List<Test.Result>, val reason: String) {

        /**
         * 测试结果总数。
         */
        val total: Int = results.size

        /**
         * 成功结果数。
         */
        val success: Int = results.count { it is Test.Success }

        /**
         * 失败结果数。
         */
        val failure: Int = results.count { it is Test.Failure }

        /**
         * 因版本不支持而跳过的结果数。
         */
        val unsupported: Int = results.count { it is Test.Unsupported }

        /**
         * 报告生成时间。
         */
        val timestamp: String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ").format(Date())

        /**
         * 服务端品牌与 Minecraft 版本信息。
         */
        val serverVersion: String = Bukkit.getVersion()

        /**
         * Bukkit API 版本。
         */
        val bukkitVersion: String = Bukkit.getBukkitVersion()

        /**
         * 服务端使用的 Java 版本。
         */
        val javaVersion: String = System.getProperty("java.version")

        /**
         * 执行测试时在线的真实协议玩家。
         */
        val onlinePlayers: List<String> = Bukkit.getOnlinePlayers().map { it.name }

        /**
         * 将套件结果序列化为 JSON。
         *
         * @return JSON 文本
         */
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
