package taboolib.module.incision.pred

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 谓词生成类的 ClassLoader 隔离回归。
 *
 * 两个插件各自拥有 defining loader 时，谓词必须在各自的子 loader 中独立定义，且整个过程
 * 不依赖反射开放 ClassLoader#defineClass，也不允许触发只能由一个插件加载的 JVMTI native。
 */
@DisplayName("PredCompiler 多 ClassLoader 类定义")
class PredCompilerClassLoaderTest {

    @Test
    fun `predicates compile independently under two plugin loaders`() {
        val moduleLoader = PredCompilerClassLoaderTest::class.java.classLoader
        val pluginLoaderA = object : ClassLoader(moduleLoader) {}
        val pluginLoaderB = object : ClassLoader(moduleLoader) {}

        val predicateA = PredCompiler.compile(
            "args[0] == \"alpha\"",
            AdviceCtx("plugin-a", pluginLoaderA),
        )
        val predicateB = PredCompiler.compile(
            "args[0] == \"beta\"",
            AdviceCtx("plugin-b", pluginLoaderB),
        )

        assertSame(pluginLoaderA, predicateA.javaClass.classLoader.parent)
        assertSame(pluginLoaderB, predicateB.javaClass.classLoader.parent)
        assertNotSame(predicateA.javaClass.classLoader, predicateB.javaClass.classLoader)
        assertTrue(predicateA.test(TestEvalContext(arrayOf("alpha"))))
        assertFalse(predicateA.test(TestEvalContext(arrayOf("beta"))))
        assertTrue(predicateB.test(TestEvalContext(arrayOf("beta"))))
    }

    /** 最小运行时上下文，仅暴露本测试表达式需要的 args。 */
    private class TestEvalContext(private val args: Array<Any?>) : EvalContext {
        override fun argAt(i: Int): Any? = args[i]
        override fun argCount(): Int = args.size
        override fun thisRef(): Any? = null
        override fun result(): Any? = null
        override fun env(): Map<String, Any?> = emptyMap()
        override fun site(): Any? = null
        override fun caller(): Any? = null
    }
}
