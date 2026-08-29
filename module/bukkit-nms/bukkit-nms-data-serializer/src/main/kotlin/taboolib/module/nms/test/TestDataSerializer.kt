package taboolib.module.nms.test

import io.netty.buffer.ByteBuf
import taboolib.common.Test
import taboolib.module.nms.createDataSerializer
import taboolib.module.nms.dataSerializerBuilder
import java.time.DayOfWeek
import java.util.BitSet
import java.util.EnumSet
import java.util.UUID

/**
 * TabooLib
 * taboolib.module.nms.test.TestDataSerializer
 *
 * @author 坏黑
 * @since 2023/8/5 00:56
 */
object TestDataSerializer : Test() {

    override fun check(): List<Result> {
        return listOf(
            sandbox("NMSDataSerializer:primitiveBytes") {
                val buffer = dataSerializerBuilder {
                    writeUtf("test")
                    writeVarInt(300)
                    writeBoolean(true)
                }.build() as ByteBuf
                try {
                    check(buffer.readUnsignedByte().toInt() == 4)
                    val text = ByteArray(4)
                    buffer.readBytes(text)
                    check(text.decodeToString() == "test")
                    check(buffer.readUnsignedByte().toInt() == 0xac)
                    check(buffer.readUnsignedByte().toInt() == 0x02)
                    check(buffer.readBoolean())
                } finally {
                    buffer.release()
                }
            },
            sandbox("NMSDataSerializer:primitiveValues") {
                val bytes = byteArrayOf(0x01, 0x7f, 0x80.toByte())
                val buffer = dataSerializerBuilder {
                    writeByte((-42).toByte())
                    writeBytes(bytes)
                    writeShort(0x1234)
                    writeInt(0x12345678)
                    writeLong(0x1020304050607080L)
                    writeFloat(12.5f)
                    writeDouble(-9876.125)
                }.build() as ByteBuf
                try {
                    check(buffer.readByte() == (-42).toByte())
                    val actualBytes = ByteArray(bytes.size)
                    buffer.readBytes(actualBytes)
                    check(actualBytes.contentEquals(bytes))
                    check(buffer.readShort() == 0x1234.toShort())
                    check(buffer.readInt() == 0x12345678)
                    check(buffer.readLong() == 0x1020304050607080L)
                    check(buffer.readFloat() == 12.5f)
                    check(buffer.readDouble() == -9876.125)
                } finally {
                    buffer.release()
                }
            },
            sandbox("NMSDataSerializer:booleanCallback") {
                var called = false
                val buffer = dataSerializerBuilder {
                    writeBoolean(true) { called = true }
                }.build() as ByteBuf
                try {
                    check(called)
                    check(buffer.readBoolean())
                } finally {
                    buffer.release()
                }
            },
            sandbox("NMSDataSerializer:uuid") {
                val uuid = UUID.fromString("12345678-1234-5678-9abc-def012345678")
                val buffer = dataSerializerBuilder {
                    writeUUID(uuid)
                }.build() as ByteBuf
                try {
                    check(buffer.readLong() == uuid.mostSignificantBits)
                    check(buffer.readLong() == uuid.leastSignificantBits)
                } finally {
                    buffer.release()
                }
            },
            sandbox("NMSDataSerializer:blockPosition") {
                // 负坐标用于验证写入时的掩码和符号位不会改变固定打包结果。
                val buffer = dataSerializerBuilder {
                    writeBlockPosition(-12345, -321, 54321)
                }.build() as ByteBuf
                try {
                    check(buffer.readLong() == -3393114425207759L)
                } finally {
                    buffer.release()
                }
            },
            sandbox("NMSDataSerializer:varIntArray") {
                // 覆盖单字节、多字节和负数 VarInt，直接校验协议字节序列。
                val buffer = dataSerializerBuilder {
                    writeVarIntArray(intArrayOf(1, 300, -1))
                }.build() as ByteBuf
                try {
                    check(buffer.readUnsignedByte().toInt() == 3)
                    check(buffer.readUnsignedByte().toInt() == 1)
                    check(buffer.readUnsignedByte().toInt() == 0xac)
                    check(buffer.readUnsignedByte().toInt() == 0x02)
                    check(buffer.readUnsignedByte().toInt() == 0xff)
                    check(buffer.readUnsignedByte().toInt() == 0xff)
                    check(buffer.readUnsignedByte().toInt() == 0xff)
                    check(buffer.readUnsignedByte().toInt() == 0xff)
                    check(buffer.readUnsignedByte().toInt() == 0x0f)
                } finally {
                    buffer.release()
                }
            },
            sandbox("NMSDataSerializer:nullable") {
                val buffer = dataSerializerBuilder {
                    writeNullable<String>(null) {
                        error("Null value should not be written")
                    }
                    writeNullable("value") {
                        writeString(it)
                    }
                }.build() as ByteBuf
                try {
                    check(!buffer.readBoolean())
                    check(buffer.readBoolean())
                    check(buffer.readUnsignedByte().toInt() == 5)
                    val value = ByteArray(5)
                    buffer.readBytes(value)
                    check(value.decodeToString() == "value")
                } finally {
                    buffer.release()
                }
            },
            sandbox("NMSDataSerializer:enumSet") {
                val buffer = dataSerializerBuilder {
                    writeEnumSet(EnumSet.of(DayOfWeek.MONDAY, DayOfWeek.FRIDAY), DayOfWeek::class.java)
                }.build() as ByteBuf
                try {
                    check(buffer.readUnsignedByte().toInt() == 0x11)
                } finally {
                    buffer.release()
                }
            },
            sandbox("NMSDataSerializer:fixedBitSet") {
                // 位 9 跨越字节边界，验证 size=10 时的定长补齐行为。
                val bitSet = BitSet().apply {
                    set(0)
                    set(9)
                }
                val buffer = dataSerializerBuilder {
                    writeFixedBitSet(bitSet, 10)
                }.build() as ByteBuf
                try {
                    check(buffer.readUnsignedByte().toInt() == 0x01)
                    check(buffer.readUnsignedByte().toInt() == 0x02)
                } finally {
                    buffer.release()
                }
            },
            sandbox("NMSDataSerializer:string") {
                val expected = "TabooLib 数据"
                val expectedBytes = expected.encodeToByteArray()
                val buffer = dataSerializerBuilder {
                    writeString(expected)
                }.build() as ByteBuf
                try {
                    check(buffer.readUnsignedByte().toInt() == expectedBytes.size)
                    val actual = ByteArray(expectedBytes.size)
                    buffer.readBytes(actual)
                    check(actual.decodeToString() == expected)
                } finally {
                    buffer.release()
                }
            },
            sandbox("NMSDataSerializer:dataOutput") {
                // DataOutput 必须与序列化器共享同一写入缓冲区。
                val serializer = dataSerializerBuilder()
                serializer.dataOutput().writeLong(0x0102030405060708L)
                val buffer = serializer.build() as ByteBuf
                try {
                    check(buffer.readLong() == 0x0102030405060708L)
                } finally {
                    buffer.release()
                }
            },
            sandbox("NMSDataSerializer:createDataSerializer") {
                val buffer = createDataSerializer {
                    writeInt(0x13572468)
                }.build() as ByteBuf
                try {
                    check(buffer.readInt() == 0x13572468)
                } finally {
                    buffer.release()
                }
            },
        )
    }
}
