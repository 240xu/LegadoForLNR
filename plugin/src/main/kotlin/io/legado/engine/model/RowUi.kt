package io.legado.engine.model

import com.google.gson.annotations.SerializedName

/**
 * FlexBox 子元素样式，对齐 lyc486 FlexChildStyle
 */
data class FlexChildStyle(
    val layout_flexGrow: Float = 0f,
    val layout_flexShrink: Float = 1f,
    val layout_alignSelf: String = "auto",
    val layout_flexBasisPercent: Float = -1f,
    val layout_wrapBefore: Boolean = false,
    val layout_justifySelf: String = "auto"
)

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
    fun parsedStyle(): FlexChildStyle {
        val s = style ?: return FlexChildStyle()
        return try {
            if (s is Map<*, *>) {
                FlexChildStyle(
                    layout_flexGrow = (s["layout_flexGrow"] as? Number)?.toFloat() ?: 0f,
                    layout_flexShrink = (s["layout_flexShrink"] as? Number)?.toFloat() ?: 1f,
                    layout_alignSelf = s["layout_alignSelf"]?.toString() ?: "auto",
                    layout_flexBasisPercent = (s["layout_flexBasisPercent"] as? Number)?.toFloat() ?: -1f,
                    layout_wrapBefore = s["layout_wrapBefore"] as? Boolean ?: false,
                    layout_justifySelf = s["layout_justifySelf"]?.toString() ?: "auto"
                )
            } else FlexChildStyle()
        } catch (_: Exception) { FlexChildStyle() }
    }

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