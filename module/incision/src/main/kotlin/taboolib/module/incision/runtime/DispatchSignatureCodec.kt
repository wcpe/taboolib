package taboolib.module.incision.runtime

import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * dispatch 签名协议编解码。
 *
 * 约定格式：
 * - phase 入口：`baseSig@PHASE`
 * - site 入口：`baseSig#encodedAdviceId`
 * - phase + site 不叠加；site 侧用 adviceId 精确路由到单条 entry
 *
 * adviceId 本身可能含 `@` / `#`，因此统一做 URL-safe Base64 编码并加前缀，
 * 避免被 phase / id 分隔逻辑误拆。
 */
object DispatchSignatureCodec {

    private const val advicePrefix = "b64:"
    private val phaseSuffixes = setOf("LEAD", "TRAIL", "SPLICE", "TRAIL_THROW")

    /**
     * `targetSig → 解析结果` 缓存。
     *
     * targetSig 由 weaver 以 LDC 常量写死在字节码里，**每个织入点一条、取值集合有限**；
     * 而解析是纯函数（同一入参必然得到同一结果），因此不需要任何失效逻辑。
     *
     * 缓存前每次 dispatch 都要付 lastIndexOf / 两次 substring，site 入口还要 Base64 解码 ——
     * 这些属于被织入方法的固定开销，与 advice 逻辑无关；缓存后热路径退化为一次 map 查找。
     */
    private val cache = ConcurrentHashMap<String, Parsed>()

    data class Parsed(
        val baseSig: String,
        val adviceId: String?,
        val phase: String?,
    )

    fun compose(baseSig: String, adviceId: String): String {
        if (adviceId.isBlank()) return baseSig
        return "$baseSig#${encodeAdviceId(adviceId)}"
    }

    fun parse(targetSig: String): Parsed {
        val cached = cache[targetSig]
        if (cached != null) return cached
        val parsed = parseUncached(targetSig)
        // 并发下重复解析无害：结果只由入参决定，先写入者胜出，后到者读到的必然是同一个值。
        cache.putIfAbsent(targetSig, parsed)
        return parsed
    }

    /** 真正的解析逻辑；只允许 [parse] 调用，保证所有调用方共享同一份缓存。 */
    private fun parseUncached(targetSig: String): Parsed {
        val (sigWithoutPhase, phase) = splitPhase(targetSig)
        val hashIdx = sigWithoutPhase.indexOf('#')
        if (hashIdx < 0) return Parsed(sigWithoutPhase, null, phase)
        val baseSig = sigWithoutPhase.substring(0, hashIdx)
        val encodedAdviceId = sigWithoutPhase.substring(hashIdx + 1).takeIf { it.isNotEmpty() }
        return Parsed(baseSig, encodedAdviceId?.let(::decodeAdviceId), phase)
    }

    private fun splitPhase(targetSig: String): Pair<String, String?> {
        val atIdx = targetSig.lastIndexOf('@')
        if (atIdx <= 0) return targetSig to null
        val maybePhase = targetSig.substring(atIdx + 1)
        return if (maybePhase in phaseSuffixes) {
            targetSig.substring(0, atIdx) to maybePhase
        } else {
            targetSig to null
        }
    }

    private fun encodeAdviceId(adviceId: String): String {
        val encoded = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(adviceId.toByteArray(Charsets.UTF_8))
        return advicePrefix + encoded
    }

    private fun decodeAdviceId(encodedAdviceId: String): String {
        if (!encodedAdviceId.startsWith(advicePrefix)) return encodedAdviceId
        val encoded = encodedAdviceId.removePrefix(advicePrefix)
        return String(Base64.getUrlDecoder().decode(encoded), Charsets.UTF_8)
    }
}
