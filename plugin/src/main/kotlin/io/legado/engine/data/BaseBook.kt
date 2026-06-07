package io.legado.engine.data

interface BaseBook : RuleDataInterface {
    var bookUrl: String
    var tocUrl: String
    override var variableMap: HashMap<String, String>
    var type: Int
    var order: Int
    var imageStyle: String?
    var durChapterIndex: Int
    var infoHtml: String?
    var tocHtml: String?

    override fun putVariable(key: String, value: String?) {
        if (value == null) {
            variableMap.remove(key)
        } else {
            variableMap[key] = value
        }
    }

    override fun putVariable(value: String?) = putVariable("", value)

    override fun getVariable(key: String): String {
        return variableMap[key] ?: ""
    }

    override fun getVariable(): String {
        return variableMap[""] ?: ""
    }
}