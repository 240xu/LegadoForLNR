package io.legado.engine.data

interface RuleDataInterface {
    val variableMap: HashMap<String, String>

    /**
     * 对齐 lyc486: 返回 Boolean 表示 key 是否已存在
     */
    fun putVariable(key: String, value: String?): Boolean {
        val keyExist = variableMap.contains(key)
        when {
            value == null -> {
                variableMap.remove(key)
                putBigVariable(key, null)
            }
            value.length < 10000 -> {
                putBigVariable(key, null)
                variableMap[key] = value
            }
            else -> {
                variableMap.remove(key)
                putBigVariable(key, value)
            }
        }
        return keyExist
    }

    fun putVariable(value: String?): Boolean = putVariable("", value)

    fun putBigVariable(key: String, value: String?) {
        // Default: no-op. Override for big variable storage.
    }

    fun getVariable(key: String): String {
        return variableMap[key] ?: getBigVariable(key) ?: ""
    }

    fun getVariableValue(): String = getVariable("")

    fun getBigVariable(key: String): String? {
        return null
    }

    fun put(key: String, value: String): String {
        putVariable(key, value)
        return value
    }

    fun get(key: String): String = getVariable(key)
}

open class RuleData : RuleDataInterface {
    override val variableMap = hashMapOf<String, String>()

    override fun putBigVariable(key: String, value: String?) {
        if (value == null) {
            variableMap.remove(key)
        } else {
            variableMap[key] = value
        }
    }

    override fun getBigVariable(key: String): String? {
        return null
    }

    fun getVariableJson(): String? {
        if (variableMap.isEmpty()) return null
        return io.legado.engine.shim.GSON.toJson(variableMap)
    }
}