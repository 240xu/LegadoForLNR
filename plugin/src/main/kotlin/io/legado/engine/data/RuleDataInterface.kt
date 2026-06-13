package io.legado.engine.data
interface RuleDataInterface {
    fun putVariable(key: String, value: String)
    fun getVariable(key: String): String
    fun putVariable(value: String) = putVariable("", value)
    fun getVariable(): String = getVariable("")
    fun put(key: String, value: String): String {
        putVariable(key, value)
        return value
    }
    fun get(key: String): String = getVariable(key)
}

open class RuleData : RuleDataInterface {
    private val variableMap = mutableMapOf<String, String>()
    override fun putVariable(key: String, value: String) { variableMap[key] = value }
    override fun getVariable(key: String): String = variableMap[key] ?: ""
}
