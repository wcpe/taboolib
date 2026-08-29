package taboolib.module.nms.test

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import taboolib.common.Test
import taboolib.module.nms.MinecraftVersion
import taboolib.module.nms.createPacket
import taboolib.module.nms.sendPacket

/**
 * TabooLib
 * taboolib.test.nms_util.TestNMSParticle
 *
 * @author 坏黑
 * @since 2023/8/5 00:56
 */
object TestNMSParticle : Test() {

    override fun check(): List<Result> {
        val location = Location(Bukkit.getWorlds().firstOrNull(), 0.0, 0.0, 0.0)
        return listOf(
            sandbox("NMSParticle:createPacket()") {
                val packet = Particle.CLOUD.createPacket(location)
                check(packet.javaClass.name.startsWith("net.minecraft."))
                Bukkit.getOnlinePlayers().firstOrNull()?.sendPacket(packet)
            },
            if (MinecraftVersion.isLower(MinecraftVersion.V1_13)) {
                // 1.12 的 Bukkit API 没有 BlockData，避免在旧运行时解析不存在的方法。
                Unsupported("NMSParticle:createPacket(BlockData)（Minecraft 1.12 不支持 BlockData）")
            } else {
                sandbox("NMSParticle:createPacket(BlockData)") {
                    val particle = runCatching { Particle.valueOf("BLOCK") }.getOrElse { Particle.valueOf("BLOCK_CRACK") }
                    val packet = particle.createPacket(location, data = Material.STONE.createBlockData())
                    check(packet.javaClass.name.startsWith("net.minecraft."))
                    Bukkit.getOnlinePlayers().firstOrNull()?.sendPacket(packet)
                }
            },
        )
    }
}
