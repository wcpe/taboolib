package taboolib.module.nms

import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.util.Vector
import taboolib.common.util.unsafeLazy

/**
 * 通过 [Particle] 创建粒子数据包
 *
 * @param location 粒子位置
 * @param offset 粒子偏移
 * @param speed 粒子速度
 * @param count 粒子数量
 * @param data 粒子数据
 * @return 粒子数据包
 */
fun Particle.createPacket(location: Location, offset: Vector = Vector(), speed: Double = 0.0, count: Int = 1, data: Any? = null): Any {
    return NMSParticle.instance.createParticlePacket(this, location, offset, speed, count, data)
}

/**
 * TabooLib
 * taboolib.module.nms.NMSParticle
 *
 * @author 坏黑
 * @since 2023/5/2 21:57
 */
abstract class NMSParticle {

    /**
     * 创建粒子数据包
     *
     * @param particle 粒子类型
     * @param location 粒子位置
     * @param offset 粒子偏移
     * @param speed 粒子速度
     * @param count 粒子数量
     * @param data 粒子数据
     * @return 粒子数据包
     */
    abstract fun createParticlePacket(particle: Particle, location: Location, offset: Vector = Vector(), speed: Double = 0.0, count: Int = 1, data: Any? = null): Any

    companion object {

        /**
         * 当前服务端对应的粒子实现。
         */
        val instance by unsafeLazy {
            nmsProxy<NMSParticle>(if (MinecraftVersion.isUnobfuscated) "{name}Impl26" else "{name}Impl")
        }
    }
}
