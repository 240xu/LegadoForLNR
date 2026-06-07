package io.legado.engine.constant

/**
 * 对齐 lyc486 StringExtensions.isTrue()
 * 判断字符串是否为"真"值：非空、非null、非"null"、且不匹配 false/no/not/0/0.0
 */
fun String?.isTrue(nullIsTrue: Boolean = false): Boolean {
    if (this.isNullOrBlank() || this == "null") {
        return nullIsTrue
    }
    return !this.trim().matches("(?i)^(?:false|no|not|0|0.0)$".toRegex())
}