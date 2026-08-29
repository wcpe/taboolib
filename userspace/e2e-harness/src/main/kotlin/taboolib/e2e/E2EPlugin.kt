package taboolib.e2e

import taboolib.common.Inject
import taboolib.common.platform.Awake
import taboolib.common.platform.Plugin
import taboolib.common.platform.function.info

/**
 * E2E 测试 Harness 插件主入口
 *
 * @author sky
 */
@Inject
@Awake
object E2EPlugin : Plugin() {

    override fun onLoad() {
        info("[E2E] TabooLib E2E Harness 加载完成")
    }

    override fun onEnable() {
        info("[E2E] TabooLib E2E Harness 启动完成")
    }

    override fun onActive() {
        if (System.getProperty("taboolib.e2e.auto") == "true" && System.getProperty("taboolib.e2e.wait-player") != "true") {
            info("[E2E] 检测到 auto 模式（无需等待玩家），准备执行测试...")
            E2ERunner.runTestsAsync("auto:ACTIVE")
        }
    }

    override fun onDisable() {
        info("[E2E] TabooLib E2E Harness 已卸载")
    }
}
