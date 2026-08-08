package taboolib.module.incision.annotation

/** 选择器匹配的字节码元素类别。 */
enum class SelectorKind { NONE, CLASS, METHOD, FIELD }

/** 选择器字符串的匹配方式；GLOB 使用统一的 `*` 与 `?` 语义。 */
enum class MatchMode { EXACT, GLOB }

/**
 * 结构化成员选择器。
 *
 * owner 与 descriptor 使用 JVM internal name/descriptor，避免 Java 风格类型解析在不同入口产生歧义。
 */
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
annotation class Selector(
    val kind: SelectorKind = SelectorKind.NONE,
    val owner: String = "",
    val name: String = "",
    val descriptor: String = "",
    val matchMode: MatchMode = MatchMode.EXACT,
)

/**
 * 结构化 pointcut。
 *
 * 语义固定为 `allOf 全部命中 && (anyOf 为空或至少一个命中) && noneOf 全部不命中`。
 * minMatches/maxMatches 约束最终宿主方法数量，防止零命中或过宽声明静默安装。
 * Advice 同时声明非空 scope 时，兼容的一行 scope 优先，扫描器会警告并忽略本 pointcut。
 */
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
annotation class Pointcut(
    val allOf: Array<Selector> = [],
    val anyOf: Array<Selector> = [],
    val noneOf: Array<Selector> = [],
    val minMatches: Int = 1,
    val maxMatches: Int = 1,
)

/** 指令模式的跨度策略；默认只接受连续的真实字节码指令。 */
enum class PatternMode { CONTIGUOUS, ORDERED_SUBSEQUENCE }
