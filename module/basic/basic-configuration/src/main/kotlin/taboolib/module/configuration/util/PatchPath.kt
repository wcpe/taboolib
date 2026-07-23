package taboolib.module.configuration.util

/**
 * Patch Variant 的路径定位器。
 * 支持点路径、列表下标、列表追加与插入语法，并在缺失中间节点时创建必要容器。
 *
 * @param path patch 路径
 * @author sky
 */
class PatchPath(path: String) {

    private val parts = parse(if (path.endsWith("[]")) path.removeSuffix("[]") else path)

    /**
     * 将值写入目标 map。
     *
     * @param root 目标根节点
     * @param value 写入值
     */
    fun set(root: MutableMap<String, Any?>, value: Any?) {
        if (parts.isEmpty()) {
            return
        }
        set(root, parts, value)
    }

    private fun parse(path: String): List<Part> {
        val result = arrayListOf<Part>()
        val key = StringBuilder()
        var index = 0
        while (index < path.length) {
            when (val char = path[index]) {
                '.' -> {
                    if (key.isNotEmpty()) {
                        result += Part.Key(key.toString())
                        key.clear()
                    }
                    index++
                }
                '[' -> {
                    if (key.isNotEmpty()) {
                        result += Part.Key(key.toString())
                        key.clear()
                    }
                    val end = path.indexOf(']', index)
                    if (end == -1) {
                        error("Invalid patch path: $path")
                    }
                    val indexed = path.substring(index + 1, end)
                    val insert = path.getOrNull(end + 1) == '+'
                    result += when {
                        indexed.isEmpty() && insert -> Part.Append
                        indexed.isNotEmpty() && insert -> Part.Insert(indexed.toInt())
                        indexed.isNotEmpty() -> Part.Index(indexed.toInt())
                        else -> error("Invalid patch path: $path")
                    }
                    index = if (insert) end + 2 else end + 1
                }
                else -> {
                    key.append(char)
                    index++
                }
            }
        }
        if (key.isNotEmpty()) {
            result += Part.Key(key.toString())
        }
        return result
    }

    @Suppress("UNCHECKED_CAST")
    private fun set(current: Any, parts: List<Part>, value: Any?) {
        val part = parts.first()
        val last = parts.size == 1
        when (part) {
            is Part.Key -> {
                val map = current as? MutableMap<String, Any?> ?: error("Patch path expects map at ${part.name}")
                if (last) {
                    map[part.name] = value
                    return
                }
                val next = parts[1]
                val child = map[part.name] ?: newContainer(next)
                map[part.name] = child
                set(child, parts.drop(1), value)
            }
            is Part.Index -> {
                val list = current as? MutableList<Any?> ?: error("Patch path expects list at [${part.index}]")
                if (part.index > list.size) {
                    error("Patch path index ${part.index} skips list size ${list.size}")
                }
                if (last) {
                    // 只允许替换已有元素或追加末尾，避免自动补空元素后让配置语义变得不可预测。
                    if (part.index == list.size) {
                        list += value
                    } else {
                        list[part.index] = value
                    }
                    return
                }
                val next = parts[1]
                val child = if (part.index == list.size) {
                    newContainer(next).also { list += it }
                } else {
                    list[part.index] ?: newContainer(next).also { list[part.index] = it }
                }
                set(child, parts.drop(1), value)
            }
            is Part.Append -> {
                val list = current as? MutableList<Any?> ?: error("Patch path expects list append")
                if (last) {
                    list.addPatchValue(value)
                    return
                }
                val next = parts[1]
                val child = newContainer(next)
                list += child
                set(child, parts.drop(1), value)
            }
            is Part.Insert -> {
                val list = current as? MutableList<Any?> ?: error("Patch path expects list insert at [${part.index}]")
                if (part.index > list.size) {
                    error("Patch path index ${part.index} skips list size ${list.size}")
                }
                if (last) {
                    list.addPatchValue(part.index, value)
                    return
                }
                val next = parts[1]
                val child = newContainer(next)
                list.add(part.index, child)
                set(child, parts.drop(1), value)
            }
        }
    }

    private fun MutableList<Any?>.addPatchValue(value: Any?) {
        if (value is List<*>) {
            addAll(value)
        } else {
            add(value)
        }
    }

    private fun MutableList<Any?>.addPatchValue(index: Int, value: Any?) {
        if (value is List<*>) {
            addAll(index, value)
        } else {
            add(index, value)
        }
    }

    private fun newContainer(next: Part): Any {
        return when (next) {
            is Part.Key -> linkedMapOf<String, Any?>()
            is Part.Index -> arrayListOf<Any?>()
            is Part.Append -> arrayListOf<Any?>()
            is Part.Insert -> arrayListOf<Any?>()
        }
    }

    private sealed class Part {

        data class Key(val name: String) : Part()

        data class Index(val index: Int) : Part()

        object Append : Part()

        data class Insert(val index: Int) : Part()
    }
}
