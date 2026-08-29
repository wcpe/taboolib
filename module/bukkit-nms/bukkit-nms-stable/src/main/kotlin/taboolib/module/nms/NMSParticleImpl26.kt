package taboolib.module.nms

import net.minecraft.core.particles.ParticleOptions
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.craftbukkit.CraftParticle
import org.bukkit.util.Vector

/**
 * 使用 26.1+ 非混淆名称创建粒子数据包。
 *
 * @author sky
 */
class NMSParticleImpl26 : NMSParticle() {

    /**
     * 创建非混淆服务端粒子数据包。
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
        val param = CraftParticle.createParticleParam(particle, data) as ParticleOptions
        return ClientboundLevelParticlesPacket(
            param,
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
    }
}
