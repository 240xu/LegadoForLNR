package io.legado.engine.constant

/**
 * 以二进制位来区分,可能一本书籍包含多个类型,每一位代表一个类型,数值为2的n次方
 * 与 Legado BookType 完全一致
 */
@Suppress("ConstPropertyName")
object BookType {
    const val video = 0b100        // 4
    const val text = 0b1000        // 8
    const val updateError = 0b10000 // 16
    const val audio = 0b100000     // 32
    const val image = 0b1000000    // 64
    const val webFile = 0b10000000 // 128
    const val local = 0b100000000  // 256
    const val archive = 0b1000000000 // 512
    const val notShelf = 0b100_0000_0000 // 1024

    const val default = text
    const val allBookType = video or text or image or audio or webFile
    const val allBookTypeLocal = video or text or image or audio or webFile or local
    const val localTag = "loc_book"
    const val webDavTag = "webDav::"
}
