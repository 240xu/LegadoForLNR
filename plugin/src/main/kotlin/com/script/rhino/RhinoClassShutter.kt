package com.script.rhino

import org.mozilla.javascript.ClassShutter

object RhinoClassShutter : ClassShutter {
    private val allowedClasses = setOf(
        "java.io.ByteArrayInputStream",
        "java.io.ByteArrayOutputStream",
        "java.util.Base64"
    )

    private val blockedPrefixes = listOf(
        "android.app",
        "android.content",
        "android.database",
        "android.os",
        "android.provider",
        "androidx.room",
        "androidx.sqlite",
        "com.script",
        "dalvik.system",
        "java.io",
        "java.lang.Class",
        "java.lang.ClassLoader",
        "java.lang.Process",
        "java.lang.ProcessBuilder",
        "java.lang.Runtime",
        "java.lang.System",
        "java.lang.invoke",
        "java.lang.reflect",
        "java.net.URLClassLoader",
        "java.nio.file",
        "java.security",
        "javax.crypto",
        "kotlin.reflect",
        "libcore",
        "org.mozilla",
        "sun.misc"
    )

    override fun visibleToScripts(fullClassName: String): Boolean {
        if (fullClassName in allowedClasses) return true
        return blockedPrefixes.none { prefix ->
            fullClassName == prefix || fullClassName.startsWith("$prefix.")
        }
    }
}
