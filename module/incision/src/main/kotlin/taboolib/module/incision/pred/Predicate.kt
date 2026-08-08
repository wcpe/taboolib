package taboolib.module.incision.pred

/**
 * 编译后的谓词。
 *
 * 由 [PredCompiler] 从 [PredAst] 生成 ASM 字节码，并装载到以 advice ClassLoader 为 parent 的
 * 专用生成类加载器后实例化。专用 loader 避免依赖受模块封装限制的反射 defineClass 或 JVMTI。
 *
 * 实现类要求：
 * - 无状态、线程安全（dispatcher 多线程并发 `test`）
 * - `test` 内部不允许抛异常逃出（除非是无法兜底的 [taboolib.module.incision.diagnostic.Trauma.Predicate.RuntimeFailure]）
 *   - `as T` 失败 → false
 *   - `?.` 在 null 上 → false
 *   - 普通成员访问失败若处于 `??` 兜底链则 false，否则交给上层（由 dispatcher warnOnce）
 */
interface Predicate {
    fun test(ctx: EvalContext): Boolean
}
