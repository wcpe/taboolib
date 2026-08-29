package taboolib.module.nms

import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.util.Vector
import org.tabooproject.reflex.Reflex.Companion.invokeConstructor
import taboolib.module.nms.MinecraftVersion.versionId

// region NMSParticleImpl
/**
 * 使用服务端映射名称创建粒子数据包，适用于 26.1 之前的混淆服务端。
 *
 * @author sky
 */
class NMSParticleImpl : NMSParticle() {

    /**
     * 当前服务端版本 ID。
     */
    val version = versionId

    /**
     * 创建映射服务端粒子数据包。
     *
     * @param particle 粒子类型
     * @param location 粒子位置
     * @param offset 粒子偏移
     * @param speed 粒子速度
     * @param count 粒子数量
     * @param data 粒子数据
     * @return 粒子数据包
     */
    override fun createParticlePacket(particle: Particle, location: Location, offset: Vector, speed: Double, count: Int, data: Any?): Any {
        if (data != null && !particle.dataType.isInstance(data)) {
            error("data should be ${particle.dataType} (${data.javaClass})")
        }
        return if (MinecraftVersion.isHigher(MinecraftVersion.V1_12)) {
            val param = if (versionId >= 12002) {
                try {
                    org.bukkit.craftbukkit.v1_21_R3.CraftParticle.createParticleParam(particle, data)
                } catch (e: NoSuchMethodError) {
                    org.bukkit.craftbukkit.v1_16_R1.CraftParticle.toNMS(particle, data)
                }
            } else {
                org.bukkit.craftbukkit.v1_16_R1.CraftParticle.toNMS(particle, data)
            }
            if (MinecraftVersion.isLower(MinecraftVersion.V1_15)) {
                net.minecraft.server.v1_16_R1.PacketPlayOutWorldParticles::class.java.invokeConstructor(param, true, location.x.toFloat(), location.y.toFloat(), location.z.toFloat(), offset.x.toFloat(), offset.y.toFloat(), offset.z.toFloat(), speed.toFloat(), count)!!
            } else if (version > 12101) {
                net.minecraft.network.protocol.game.PacketPlayOutWorldParticles(
                    param as net.minecraft.core.particles.ParticleParam,
                    true,
                    true,
                    location.x,
                    location.y,
                    location.z,
                    offset.x.toFloat(),
                    offset.y.toFloat(),
                    offset.z.toFloat(),
                    speed.toFloat(),
                    count
                )
            } else {
                net.minecraft.server.v1_16_R1.PacketPlayOutWorldParticles(
                    param as net.minecraft.server.v1_16_R1.ParticleParam,
                    true,
                    location.x,
                    location.y,
                    location.z,
                    offset.x.toFloat(),
                    offset.y.toFloat(),
                    offset.z.toFloat(),
                    speed.toFloat(),
                    count
                )
            }
        } else {
            net.minecraft.server.v1_12_R1.PacketPlayOutWorldParticles(
                org.bukkit.craftbukkit.v1_12_R1.CraftParticle.toNMS(particle),
                true,
                location.x.toFloat(),
                location.y.toFloat(),
                location.z.toFloat(),
                offset.x.toFloat(),
                offset.y.toFloat(),
                offset.z.toFloat(),
                speed.toFloat(),
                count
            )
        }
    }
}
// endregion
