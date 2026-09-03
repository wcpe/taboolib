package taboolib.common.platform;

import org.jetbrains.annotations.Nullable;
import org.tabooproject.reflex.Reflex;
import taboolib.common.LifeCycle;
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
        failure = runCleanup(failure, Reflex::clearCaches);

        if (failure != null) {
            rethrow(failure);
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
