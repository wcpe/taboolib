package taboolib.common.platform.function

import taboolib.common.PrimitiveIO
import taboolib.common.io.isDebugMode
import taboolib.common.io.newFile
import taboolib.common.io.runningResources
import taboolib.common.platform.PlatformFactory
import taboolib.common.platform.service.PlatformIO
import taboolib.common.util.unsafeLazy
import java.io.File

val ioService by unsafeLazy { PlatformFactory.getService<PlatformIO>() }
val ioServiceOrNull by unsafeLazy { PlatformFactory.getServiceOrNull<PlatformIO>() }

/**
 * 获取控制台对象
 * 例如：
 * server<ConsoleCommandSender>()
 */
fun <T> server(): T {
    return ioService.server()
}

/**
 * 打印调试信息
 *
 * @param message 日志内容
 */
fun debug(vararg message: Any?) {
    if (isDebugMode) message.filterNotNull().forEach { PrimitiveIO.debug(it) }
}

/**
 * 打印日志
 *
 * @param message 日志内容
 */
fun info(vararg message: Any?) {
    val service = ioServiceOrNull
    if (service != null) {
        service.info(*message)
    } else {
        message.forEach { PrimitiveIO.println(it) }
    }
}

/**
 * 打印错误日志
 *
 * @param message 日志内容
 */
fun severe(vararg message: Any?) {
    val service = ioServiceOrNull
    if (service != null) {
        service.severe(*message)
    } else {
        message.forEach { PrimitiveIO.warning(it) }
    }
}

/**
 * 打印警告日志
 *
 * @param message 日志内容
 */
fun warning(vararg message: Any?) {
    val service = ioServiceOrNull
    if (service != null) {
        service.warning(*message)
    } else {
        message.forEach { PrimitiveIO.warning(it) }
    }
}

/**
 * 释放当前插件内的特定资源文件
 *
 * @param source 资源文件源路径
 * @param replace 是否覆盖文件
 * @param target 资源文件目标路径
 */
fun releaseResourceFile(source: String, replace: Boolean = false, target: String = source): File {
    return ioService.releaseResourceFile(source, target, replace)
}

/**
 * 释放当前插件内特定目录下的所有资源文件
 *
 * @param prefix 资源文件目录前缀
 * @param replace 是否覆盖文件
 */
fun releaseResourceFolder(prefix: String, replace: Boolean = false) {
    runningResources.entries.forEach { resource ->
        val path = resource.key
        if (path.startsWith(prefix)) {
            val file = File(getDataFolder(), path)
            if (file.exists() && !replace) {
                return@forEach
            }
            newFile(file).writeBytes(resource.value)
        }
    }
}

/**
 * 获取当前插件的 Jar 文件对象
 */
fun getJarFile(): File {
    return ioService.getJarFile()
}

/**
 * 获取当前插件的配置文件目录
 * 可能不存在，需要手动调用 mkdirs 方法创建
 */
fun getDataFolder(): File {
    return ioService.getDataFolder()
}

/**
 * 获取当前平台的信息
 * 用于 BStats 统计，无实际用途
 */
fun getPlatformData(): Map<String, Any> {
    return ioService.getPlatformData()
}
