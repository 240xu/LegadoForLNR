package io.legado.engine.model

import com.google.gson.annotations.SerializedName

/**
 * Legado 登录 UI 行定义
 * 对应书源 loginUi JSON 中的每个表单元素
 */
data class RowUi(
    val name: String = "",
    val type: String = "text",
    val action: String? = null,
    val chars: Array<String?>? = null,
    val default: String? = null,
    var viewName: String? = null,
    val style: Any? = null,
) {
    object Type {
        const val text = "text"
        const val password = "password"
        const val button = "button"
        const val toggle = "toggle"
        const val select = "select"
    }

    override fun equals(other: Any?): Boolean {
        if (other is RowUi) {
            return other.name == name && other.type == type && other.action == action && other.default == default && other.viewName == viewName
        }
        return false
    }

    override fun hashCode(): Int {
        var result = name.hashCode() + type.hashCode()
        result = 31 * result + (action?.hashCode() ?: 0)
        result = 31 * result + (default?.hashCode() ?: 0)
        return result
    }
}