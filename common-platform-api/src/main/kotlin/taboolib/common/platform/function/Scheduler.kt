package taboolib.common.platform.function

import taboolib.common.platform.PlatformFactory
import taboolib.common.platform.service.PlatformScheduler
import taboolib.common.util.unsafeLazy

/**
 * 跨平台调度服务，目前只有 Bukkit（含 Folia）提供服务实例。
 *
 * 其它平台上该值为 null，跨平台代码必须判空，需要保底执行时退回 [executorService]。
 * 不要假定它一定存在。
 */
val schedulerService by unsafeLazy { PlatformFactory.getServiceOrNull<PlatformScheduler>() }
