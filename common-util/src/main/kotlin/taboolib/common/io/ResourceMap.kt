package taboolib.common.io

import java.util.concurrent.ConcurrentHashMap

/**
 * 按需读取资源内容的只读 Map
 * 资源索引及内容均延迟到首次访问，避免插件启动时扫描尚未使用的资源。
 */
internal class ResourceMap private constructor(providerFactory: () -> Map<String, () -> ByteArray>) : AbstractMap<String, ByteArray>() {

    private val cache = ConcurrentHashMap<String, ByteArray>()

    private val providers by lazy(providerFactory)

    override val size: Int
        get() = providers.size

    override fun containsKey(key: String): Boolean {
        return providers.containsKey(key)
    }

    override fun get(key: String): ByteArray? {
        val provider = providers[key] ?: return null
        return cache.computeIfAbsent(key) { provider() }
    }

    override val entries: Set<Map.Entry<String, ByteArray>>
        get() = object : AbstractSet<Map.Entry<String, ByteArray>>() {

            override val size: Int
                get() = providers.size

            override fun iterator(): Iterator<Map.Entry<String, ByteArray>> {
                val iterator = providers.keys.iterator()
                return object : Iterator<Map.Entry<String, ByteArray>> {

                    override fun hasNext(): Boolean {
                        return iterator.hasNext()
                    }

                    override fun next(): Map.Entry<String, ByteArray> {
                        val key = iterator.next()
                        return object : Map.Entry<String, ByteArray> {

                            override val key: String = key

                            override val value: ByteArray
                                get() = getValue(key)
                        }
                    }
                }
            }
        }

    companion object {

        /**
         * 组合多个资源 Map，后加入的资源覆盖同名资源
         *
         * @param maps 待组合的资源 Map
         * @return 保持按需读取语义的资源视图
         */
        fun combine(maps: List<Map<String, ByteArray>>): Map<String, ByteArray> {
            if (maps.size == 1) {
                return maps.first()
            }
            return ResourceMap {
                val providers = LinkedHashMap<String, () -> ByteArray>()
                maps.forEach { map ->
                    if (map is ResourceMap) {
                        providers.putAll(map.providers)
                    } else {
                        map.keys.forEach { key -> providers[key] = { map.getValue(key) } }
                    }
                }
                providers
            }
        }

        /**
         * 创建按需读取的资源 Map
         *
         * @param providerFactory 资源索引构造函数
         * @return 资源视图
         */
        fun of(providerFactory: () -> Map<String, () -> ByteArray>): ResourceMap {
            return ResourceMap(providerFactory)
        }
    }
}
