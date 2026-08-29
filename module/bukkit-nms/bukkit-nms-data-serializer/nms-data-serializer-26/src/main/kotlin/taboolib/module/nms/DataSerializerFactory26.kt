package taboolib.module.nms

import io.netty.buffer.ByteBufOutputStream
import io.netty.buffer.Unpooled
import net.minecraft.core.RegistryAccess
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.chat.ComponentSerialization
import org.bukkit.craftbukkit.util.CraftChatMessage
import java.io.DataOutput

/**
 * Minecraft 26.x 非混淆环境的数据序列化器。
 *
 * @author sky
 */
class DataSerializerFactory26 : DataSerializerFactory, DataSerializer {

    /**
     * 携带空注册表访问器的网络缓冲区。
     */
    val buf = RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY)

    override fun writeByte(byte: Byte): DataSerializer {
        return buf.writeByte(byte.toInt()).let { this }
    }

    override fun writeBytes(bytes: ByteArray): DataSerializer {
        return buf.writeBytes(bytes).let { this }
    }

    override fun writeShort(short: Short): DataSerializer {
        return buf.writeShort(short.toInt()).let { this }
    }

    override fun writeInt(int: Int): DataSerializer {
        return buf.writeInt(int).let { this }
    }

    override fun writeLong(long: Long): DataSerializer {
        return buf.writeLong(long).let { this }
    }

    override fun writeFloat(float: Float): DataSerializer {
        return buf.writeFloat(float).let { this }
    }

    override fun writeDouble(double: Double): DataSerializer {
        return buf.writeDouble(double).let { this }
    }

    override fun writeBoolean(boolean: Boolean): DataSerializer {
        return buf.writeBoolean(boolean).let { this }
    }

    override fun writeBoolean(boolean: Boolean, callback: Runnable): DataSerializer {
        buf.writeBoolean(boolean)
        if (boolean) {
            callback.run()
        }
        return this
    }

    override fun writeMetadataLegacy(meta: List<Any>): DataSerializer {
        throw UnsupportedOperationException("Legacy metadata is unavailable on Minecraft 26.x")
    }

    override fun writeComponent(json: String): DataSerializer {
        ComponentSerialization.TRUSTED_STREAM_CODEC.encode(buf, CraftChatMessage.fromJSON(json))
        return this
    }

    override fun build(): Any {
        return buf
    }

    override fun dataOutput(): DataOutput {
        return ByteBufOutputStream(buf)
    }

    override fun newSerializer(): DataSerializer {
        return DataSerializerFactory26()
    }
}
