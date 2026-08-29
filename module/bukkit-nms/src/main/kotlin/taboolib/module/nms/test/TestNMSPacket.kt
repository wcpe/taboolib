package taboolib.module.nms.test

import org.bukkit.Bukkit
import org.tabooproject.reflex.Reflex.Companion.invokeConstructor
import taboolib.common.Inject
import taboolib.common.LifeCycle
import taboolib.common.Test
import taboolib.common.event.InternalEventBus
import taboolib.common.io.isDevelopmentMode
import taboolib.common.platform.Awake
import taboolib.module.nms.*
import java.awt.Point
import java.util.concurrent.TimeUnit

/**
 * TabooLib
 * taboolib.module.nms.test.TestPacketSender
 *
 * @author 坏黑
 * @since 2023/8/5 00:56
 */
@Inject
object TestNMSPacket : Test() {

    var testSend = false
    var testSendHandshake = false
    var testReceive = false
    var testReceiveHandshake = false

    @Awake(LifeCycle.LOAD)
    fun setup() {
        if (isDevelopmentMode) {
            InternalEventBus.listen(PacketSendEvent::class.java) { testSend = true }
            InternalEventBus.listen(PacketSendEvent.Handshake::class.java) { testSendHandshake = true }
            InternalEventBus.listen(PacketReceiveEvent::class.java) { testReceive = true }
            InternalEventBus.listen(PacketReceiveEvent.Handshake::class.java) { testReceiveHandshake = true }
        }
    }

    override fun check(): List<Result> {
        val result = arrayListOf<Result>()
        val player = Bukkit.getOnlinePlayers().firstOrNull()
        if (player != null) {
            // 测试连接
            result += sandbox("NMS:getConnection(Player)") { PacketSender.getConnection(player) }
            // 测试发包
            // KeepAlive 在旧版和新版都有对应的数据包，且不依赖已经移除或改名的视距包。
            // 真实客户端会自动回应 KeepAlive，改用无回包副作用的生命值同步包验证发送链路。
            val packetName = when {
                MinecraftVersion.isUnobfuscated -> "network.protocol.game.ClientboundSetHealthPacket"
                MinecraftVersion.isUniversal -> "ClientboundSetHealthPacket"
                else -> "PacketPlayOutUpdateHealth"
            }
            val createPacket = { nmsClass(packetName).invokeConstructor(player.health.toFloat(), player.foodLevel, player.saturation) }
            result += sandbox("NMS:sendPacketBlocking(Player, Any)") {
                player.sendPacketBlocking(createPacket())
            }
            result += sandbox("NMS:sendBundlePacketBlocking(Player, Any)") {
                player.sendBundlePacketBlocking(createPacket())
            }
            result += sandbox("NMS:sendPacket(Player, Any)") {
                val future = player.sendPacket(createPacket())
                future.get(5, TimeUnit.SECONDS)
                check(future.isDone && !future.isCompletedExceptionally)
            }
            result += sandbox("NMS:sendBundlePacket(Player, Any)") {
                val future = player.sendBundlePacket(createPacket())
                future.get(5, TimeUnit.SECONDS)
                check(future.isDone && !future.isCompletedExceptionally)
            }
            result += sandbox("NMS:Packet.readWriteOverwrite") {
                val point = Point(1, 2)
                val packet = PacketImpl(point)
                check(packet.read<Int>("x", remap = false) == 1)
                packet.write("x", 3, remap = false)
                check(point.x == 3)
                val replacement = Point(4, 5)
                packet.overwrite(replacement)
                check(packet.source === replacement)
                check(packet.name == "Point")
                check(packet.fullyName == Point::class.java.name)
            }
            result += sandbox("NMS:Packet.syntheticClass") {
                check(PacketImpl(Runnable {}).isUnmappedSyntheticClass())
            }
            result += sandbox("NMS:ProtocolHandler.isInjected") { check(ProtocolHandler.isInjected()) }
            // 测试事件
            result += if (testSend) Success.of("NMS:PacketSendEvent") else Failure.of("NMS:PacketSendEvent", "NOT_TRIGGERED")
            result += if (testSendHandshake) Success.of("NMS:PacketSendEvent.Handshake") else Failure.of("NMS:PacketSendEvent.Handshake", "NOT_TRIGGERED")
            result += if (testReceive) Success.of("NMS:PacketReceiveEvent") else Failure.of("NMS:PacketReceiveEvent", "NOT_TRIGGERED")
            // result += if (testReceiveHandshake) Success.of("NMS:PacketReceiveEvent.Handshake") else Failure.of("NMS:PacketReceiveEvent.Handshake", "NOT_TRIGGERED")
        }
        return result
    }
}
