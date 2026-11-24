package taboolib.common.platform;

import org.jetbrains.annotations.Nullable;
import org.tabooproject.reflex.Reflex;
import taboolib.common.LifeCycle;
import taboolib.common.PrimitiveIO;
import taboolib.common.TabooLib;

/**
 * 插件关闭流程。
 */
public final class PluginLifecycle {

    private PluginLifecycle() {
    }

    /**
     * 执行用户卸载回调、框架关闭生命周期与反射缓存清理。
     */
    public static void disable(@Nullable Plugin plugin) {
        Throwable failure = null;
        try {
            if (plugin != null && !TabooLib.isStopped()) {
                plugin.onDisable();
            }
        } catch (Throwable ex) {
            failure = ex;
        }

        failure = runCleanup(failure, () -> TabooLib.lifeCycle(LifeCycle.DISABLE));
        failure = runCleanup(failure, PluginLifecycle::clearReflexCaches);

        if (failure != null) {
            rethrow(failure);
        }
    }

    /**
     * 清理反射缓存。
     *
     * Reflex 是 compileOnly 依赖，运行时可能由隔离类加载器持有、不在插件自身的类加载器中。
     * 此时即便直接引用也会有 {@link LinkageError}，因此这里就地忽略链接错误，避免一个可选的
     * 清理步骤中断整个关闭流程；真正的清理异常仍需向上抛出。
     */
    private static void clearReflexCaches() {
        try {
            Reflex.clearCaches();
        } catch (LinkageError ex) {
            PrimitiveIO.debug("跳过反射缓存清理，当前类加载器中缺少 Reflex：{0}", ex.toString());
        }
    }

    private static Throwable mergeFailure(@Nullable Throwable primary, Throwable secondary) {
        if (primary == null) {
            return secondary;
        }
        if (primary != secondary) {
            primary.addSuppressed(secondary);
        }
        return primary;
    }

    private static void rethrow(Throwable failure) {
        if (failure instanceof RuntimeException) {
            throw (RuntimeException) failure;
        }
        if (failure instanceof Error) {
            throw (Error) failure;
        }
        throw new RuntimeException(failure);
    }

    private static Throwable runCleanup(@Nullable Throwable failure, Runnable cleanup) {
        try {
            cleanup.run();
        } catch (Throwable ex) {
            return mergeFailure(failure, ex);
        }
        return failure;
    }

    /**
     * 执行不含用户插件回调的关闭流程。
     */
    public static void disable() {
        disable(null);
    }
}
