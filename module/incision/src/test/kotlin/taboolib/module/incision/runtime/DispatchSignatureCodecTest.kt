package taboolib.module.incision.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * dispatch 签名解析的语义锚点。
 *
 * 解析结果现在**按签名串缓存**（每个织入点一条 LDC 常量，取值集合有限），因此必须有测试守住
 * "缓存不改变可观察语义"：同一串永远得到同一结果，且重复调用不得漂移。
 *
 * 同时覆盖三种形态：纯 baseSig、`@PHASE` 入口、`#adviceId` site 入口（含需要 Base64 转义的特殊字符）。
 */
@DisplayName("dispatch 签名编解码")
class DispatchSignatureCodecTest {

    private val base = "com/example/Foo.greet(Ljava/lang/String;I)I"

    @Test
    @DisplayName("纯签名 / phase / site adviceId 三种形态解析正确")
    fun parsesAllThreeShapes() {
        val cases = linkedMapOf(
            base to DispatchSignatureCodec.Parsed(base, null, null),
            "$base@LEAD" to DispatchSignatureCodec.Parsed(base, null, "LEAD"),
            "$base@TRAIL" to DispatchSignatureCodec.Parsed(base, null, "TRAIL"),
            "$base@SPLICE" to DispatchSignatureCodec.Parsed(base, null, "SPLICE"),
            "$base@TRAIL_THROW" to DispatchSignatureCodec.Parsed(base, null, "TRAIL_THROW"),
            // '@' 后面不是已知相位：整串都算 baseSig，不能被误拆
            "$base@UNKNOWN" to DispatchSignatureCodec.Parsed("$base@UNKNOWN", null, null),
            "$base#" to DispatchSignatureCodec.Parsed(base, null, null),
            "$base#plain-id" to DispatchSignatureCodec.Parsed(base, "plain-id", null),
        )
        for ((input, expected) in cases) {
            assertEquals(expected, DispatchSignatureCodec.parse(input), "解析结果不符: $input")
        }
    }

    @Test
    @DisplayName("adviceId 含 @ / # / 中文时仍精确还原")
    fun roundTripsHostileAdviceIds() {
        val ids = listOf(
            "site@inner",
            "site#inner",
            "site@inner#both",
            "带中文的 id",
            "a b c",
            "id-with_symbols.+=",
        )
        for (id in ids) {
            val composed = DispatchSignatureCodec.compose(base, id)
            assertEquals(
                DispatchSignatureCodec.Parsed(base, id, null),
                DispatchSignatureCodec.parse(composed),
                "compose/parse 往返失败: id=$id composed=$composed",
            )
        }
        // adviceId 为空串时 compose 不加后缀（保持原调用方的字节码常量形状）
        assertEquals(base, DispatchSignatureCodec.compose(base, ""))
    }

    @Test
    @DisplayName("phase 与 site 不叠加：前缀带 @PHASE 的 site 签名整段作为 baseSig")
    fun keepsPhasePrefixInsideBaseSignature() {
        val withPhase = "$base@SPLICE"
        val parsed = DispatchSignatureCodec.parse(DispatchSignatureCodec.compose(withPhase, "advice-1"))
        assertEquals(withPhase, parsed.baseSig)
        assertEquals("advice-1", parsed.adviceId)
        assertNull(parsed.phase)
    }

    @Test
    @DisplayName("重复解析命中缓存：同一入参返回同一实例，且结果不随调用次数变化")
    fun repeatedParsingHitsCache() {
        val input = "$base#cached-id"
        val first = DispatchSignatureCodec.parse(input)
        repeat(8) {
            assertSame(first, DispatchSignatureCodec.parse(input), "缓存未命中或结果被改写: $input")
        }
        // 解析是纯函数：不同入参必须互不干扰（缓存键是整串，不是前缀）
        val other = DispatchSignatureCodec.parse("$base@LEAD")
        assertSame(DispatchSignatureCodec.parse("$base@LEAD"), other)
        assertEquals(base, other.baseSig)
        assertEquals("cached-id", first.adviceId)
    }
}
