package taboolib.e2e

import taboolib.common.Inject
import taboolib.common.platform.ProxyCommandSender
import taboolib.common.platform.command.CommandBody
import taboolib.common.platform.command.CommandHeader
import taboolib.common.platform.command.mainCommand
import taboolib.common.platform.command.subCommand

/**
 * /tbtest 命令注册
 *
 * @author sky
 */
@Inject
@CommandHeader(name = "tbtest", aliases = ["e2etest", "taboolib-test"], description = "TabooLib E2E Version Test")
object E2ECommand {

    /**
     * 执行全部已发现的 E2E 测试。
     */
    @CommandBody
    val main = mainCommand {
        execute<ProxyCommandSender> { sender, _, _ ->
            sender.sendMessage("§e[E2E] 正在触发 E2E 全量测试...")
            E2ERunner.runTestsAsync("command:${sender.name}", delayTicks = 1L)
        }
    }

    /**
     * 列出当前测试插件发现的测试类。
     */
    @CommandBody
    val list = subCommand {
        execute<ProxyCommandSender> { sender, _, _ ->
            val tests = E2ERunner.discoverTests()
            sender.sendMessage("§e[E2E] 当前发现 ${tests.size} 个测试项:")
            tests.forEach { test ->
                sender.sendMessage("§7 - §f${test.javaClass.name}")
            }
        }
    }
}
