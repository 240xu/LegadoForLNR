package io.legado.engine.data

interface BaseBook : RuleDataInterface {
    var bookUrl: String
    var tocUrl: String?
    override var variableMap: HashMap<String, String>
    var type: Int
    var order: Int
    var imageStyle: String?
    var durChapterIndex: Int

    override fun putVariable(key: String, value: String?): Boolean {
        return super.putVariable(key, value)
    }

    override fun putVariable(value: String): Boolean {
        return putVariable("", value)
    }

    override fun getVariable(key: String): String {
        return variableMap[key] ?: ""
    }

    override fun getVariable(): String {
        return variableMap[""] ?: ""
    }
}
