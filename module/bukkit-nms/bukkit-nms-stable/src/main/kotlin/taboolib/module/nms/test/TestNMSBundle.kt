package taboolib.module.nms.test

import org.tabooproject.reflex.Reflex.Companion.invokeConstructor
import taboolib.common.Test
import taboolib.common.UnsupportedVersionException
import taboolib.module.nms.MinecraftVersion
import taboolib.module.nms.PacketImpl
import taboolib.module.nms.createBundlePacket
import taboolib.module.nms.isBundlePacket
import taboolib.module.nms.nmsClass
import taboolib.module.nms.subPackets

/**
 * 验证 BundlePacket 创建与子包读取行为。
 *
 * @author sky
 */
object TestNMSBundle : Test() {

    override fun check(): List<Result> {
        return listOf(
            sandbox("NMSBundlePacket:roundTrip") {
                // BundlePacket 是 1.19.4 才加入的协议能力，低版本不能伪造 round-trip 结果。
                if (!MinecraftVersion.isBundlePacketSupported) {
                    throw UnsupportedVersionException()
                }
                val packetName = when {
                    MinecraftVersion.isUnobfuscated -> "network.protocol.game.ClientboundSetHealthPacket"
                    MinecraftVersion.isUniversal -> "ClientboundSetHealthPacket"
                    else -> "PacketPlayOutUpdateHealth"
                }
                val first = PacketImpl(nmsClass(packetName).invokeConstructor(20f, 20, 5f))
                val second = PacketImpl(nmsClass(packetName).invokeConstructor(20f, 20, 5f))
                val bundle = listOf(first, second).createBundlePacket()
                check(bundle.isBundlePacket())
                val packets = bundle.subPackets()
                check(packets.size == 2)
                check(packets.map { it.name } == listOf(first.name, second.name))
                check(first.subPackets().isEmpty())
            },
        )
    }
}
