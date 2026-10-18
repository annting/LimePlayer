package app.myplayer.feature.library

import app.myplayer.core.model.PinyinConverter

/**
 * 拼音转换（任务 3-5）：Android `android.icu` 的 `Han-Latin` 转写（API 24+，minSdk 26 可用）。
 * 汉字逐字转写再取首字母 —— 单字即单音节，首字母天然对齐。
 */
class IcuPinyinConverter : PinyinConverter {

    private val full = runCatching {
        android.icu.text.Transliterator.getInstance("Han-Latin; Latin-ASCII; Lower")
    }.getOrNull()

    private val single = runCatching {
        android.icu.text.Transliterator.getInstance("Han-Latin; Latin-ASCII; Lower")
    }.getOrNull()

    override fun full(text: String): String {
        val t = full ?: return ""
        return lettersOnly(t.transliterate(text))
    }

    override fun initials(text: String): String {
        val t = single ?: return ""
        val sb = StringBuilder()
        for (ch in text) {
            // 只对 CJK 逐字转写：单字 → 一个音节 → 取首字母
            if (ch.code in 0x4E00..0x9FFF) {
                val p = lettersOnly(t.transliterate(ch.toString()))
                if (p.isNotEmpty()) sb.append(p[0])
            }
        }
        return sb.toString()
    }

    private fun lettersOnly(s: String): String = buildString {
        for (c in s) if (c in 'a'..'z' || c in 'A'..'Z') append(c.lowercaseChar())
    }
}
