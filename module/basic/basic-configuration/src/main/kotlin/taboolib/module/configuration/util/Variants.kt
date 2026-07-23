package taboolib.module.configuration.util

import taboolib.library.configuration.ConfigurationSection

/**
 * 展开配置中的 Patch Variant 节点。
 * `$variants` 只作为编辑期元配置存在，展开后会生成普通顶层配置并从结果中移除。
 * 每个变体节点的键作为生成后的顶层键模板，`{id}` 会替换为原节点键。
 * 变体节点内容就是 patch，patch 键使用点路径与列表下标定位目标值，例如 `event.on_consume[1].buff[0].data`。
 * `[]+` 表示追加到列表末尾，`[n]+` 表示插入到指定下标前，`[]` 与普通列表值都会覆盖整段列表。
 * 当中间节点不存在时会自动创建；列表下标仅允许替换已有元素或追加到末尾，避免补洞产生含义不清的空元素。
 *
 * @param variantsKey 变体节点名称
 * @return 当前配置节点
 * @author sky
 */
fun ConfigurationSection.expandVariants(variantsKey: String = "\$variants"): ConfigurationSection {
    val generated = arrayListOf<Pair<String, Map<String, Any?>>>()
    for (id in getKeys(false)) {
        val source = getConfigurationSection(id) ?: continue
        val variants = source.getConfigurationSection(variantsKey) ?: continue
        for (variantName in variants.getKeys(false)) {
            val variant = variants.getConfigurationSection(variantName) ?: continue
            val variantId = variantName.replace("{id}", id)
            val map = mutableCopyMap(source.toMap())
            map.remove(variantsKey)
            for ((path, value) in collectPatchValues(variant.toMap())) {
                PatchPath(path).set(map, mutableCopy(value))
            }
            generated += variantId to map
        }
        source[variantsKey] = null
    }
    generated.forEach { (id, map) -> this[id] = map }
    return this
}

private fun collectPatchValues(map: Map<String, Any?>, parent: String = ""): List<Pair<String, Any?>> {
    val values = arrayListOf<Pair<String, Any?>>()
    for ((key, value) in map) {
        val path = if (parent.isEmpty()) key else "$parent.$key"
        if (value is Map<*, *>) {
            val valueMap = mutableCopyMap(value)
            if (hasListPatchPath(valueMap)) {
                // NightConfig 会把带点号的 key 预先拆成嵌套节点，这里还原为完整 patch 路径再执行定位。
                values += collectPatchValues(valueMap, path)
            } else {
                values += path to valueMap
            }
        } else {
            values += path to value
        }
    }
    return values
}

private fun hasListPatchPath(map: Map<*, *>): Boolean {
    for ((key, value) in map) {
        if (key.toString().contains('[')) {
            return true
        }
        if (value is Map<*, *> && hasListPatchPath(value)) {
            return true
        }
    }
    return false
}

private fun mutableCopy(value: Any?): Any? {
    return when (value) {
        is ConfigurationSection -> mutableCopy(value.toMap())
        is Map<*, *> -> mutableCopyMap(value)
        is List<*> -> value.mapTo(ArrayList()) { mutableCopy(it) }
        else -> value
    }
}

private fun mutableCopyMap(map: Map<*, *>): LinkedHashMap<String, Any?> {
    val copy = linkedMapOf<String, Any?>()
    for ((key, value) in map) {
        copy[key.toString()] = mutableCopy(value)
    }
    return copy
}
