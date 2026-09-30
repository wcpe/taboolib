package taboolib.e2e

import java.io.File
import java.io.FileOutputStream
import java.util.Properties

/**
 * mc-testkit 场景判定桩（Java 8 兼容，不依赖 harness-core）。
 *
 * 结果文件路径来自 MC_TESTKIT_E2E_RESULT_FILE；status=PASS|FAIL 为 verify 唯一真源。
 */
object E2EMcTestkitVerdict {

    private const val RESULT_ENV = "MC_TESTKIT_E2E_RESULT_FILE"
    private const val SCENARIO_ENV = "MC_TESTKIT_E2E_SCENARIO"
    private const val SERVE_SCENARIO = "__mc_testkit_serve__"

    fun scenarioId(): String? = System.getenv(SCENARIO_ENV)?.takeIf { it.isNotBlank() }

    fun isServeScenario(): Boolean = scenarioId() == SERVE_SCENARIO

    /** full-* 需要玩家（bot）入服后再跑；其余（smoke-* 等）ACTIVE 即跑。 */
    fun requiresPlayer(): Boolean {
        val id = scenarioId() ?: return false
        return id.startsWith("full-") || id.startsWith("taboolib-full")
    }

    fun resultFile(): File? {
        val path = System.getenv(RESULT_ENV) ?: return null
        if (path.isBlank()) return null
        return File(path)
    }

    fun report(ok: Boolean, message: String, extras: Map<String, String> = emptyMap()): Boolean {
        val file = resultFile() ?: return false
        file.parentFile?.mkdirs()
        val props = Properties()
        props.setProperty("status", if (ok) "PASS" else "FAIL")
        props.setProperty("message", message)
        extras.forEach { (k, v) -> props.setProperty(k, v) }
        val tmp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(tmp).use { out -> props.store(out, "taboolib e2e harness") }
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
        return true
    }

    fun shutdownServer() {
        try {
            Class.forName("org.bukkit.Bukkit").getMethod("shutdown").invoke(null)
        } catch (_: Throwable) {
            // ignore
        }
    }
}
