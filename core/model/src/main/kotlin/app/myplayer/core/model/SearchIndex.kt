package app.myplayer.core.model

/**
 * 搜索索引（`02` §6.5 / 任务 3-5）。
 *
 * 设计取舍：本项目的媒体库规模在**数千条以内**（单服务器自建库），
 * 因此用「内存索引 + 预计算检索列」实现，效果等同 FTS（输入即搜、无 loading），
 * 且省掉一次 Room 迁移与触发器维护。条目数增长到万级时再换成 FTS4 表即可，
 * 上层只依赖 [SearchIndex.search] 一个入口。
 *
 * 检索列（每条预计算）：
 * - 归一化标题：小写、去标点空白，中文逐字保留（`unicode61` 等价效果）
 * - 拼音全拼（`jianfei` 这类）
 * - 拼音首字母（`jf` 这类）
 *
 * 匹配优先级：标题前缀 > 首字母前缀 > 全拼前缀 > 标题包含 > 全拼包含。
 */
class SearchIndex private constructor(
    private val docs: List<Doc>,
) {
    private class Doc(
        val item: MediaItemDto,
        val title: String,
        val pinyin: String,
        val initials: String,
    )

    /**
     * 执行检索。[rawQuery] 为空时返回空列表。
     * 结果按匹配分降序、同分按标题升序。
     */
    fun search(rawQuery: String, limit: Int = 60): List<MediaItemDto> {
        val q = normalize(rawQuery)
        if (q.isEmpty()) return emptyList()
        val scored = docs.mapNotNull { doc ->
            val score = scoreOf(doc, q)
            if (score <= 0) null else doc to score
        }
        return scored
            .sortedWith(compareByDescending<Pair<Doc, Int>> { it.second }.thenBy { it.first.title })
            .take(limit)
            .map { it.first.item }
    }

    private fun scoreOf(doc: Doc, q: String): Int = when {
        doc.title.startsWith(q) -> 1000 - (doc.title.length - q.length).coerceAtLeast(0)
        doc.initials.startsWith(q) -> 800 - (doc.initials.length - q.length).coerceAtLeast(0)
        doc.pinyin.startsWith(q) -> 700 - (doc.pinyin.length - q.length).coerceAtLeast(0)
        doc.title.contains(q) -> 500
        doc.pinyin.contains(q) -> 300
        // 首字母出现在中段（如「fei」命中「jianfei」的首字母段）较少见，按低分兜底
        doc.initials.contains(q) -> 200
        else -> 0
    }

    companion object {
        /**
         * 构建索引。[pinyin] 由平台提供（Android 用 ICU；JVM 测试可注入替身或留空）。
         */
        fun build(items: List<MediaItemDto>, pinyin: PinyinConverter = PinyinConverter.None): SearchIndex =
            SearchIndex(
                items.map { item ->
                    val title = normalize(item.title)
                    val py = if (pinyin === PinyinConverter.None) "" else pinyin.full(item.title)
                    Doc(
                        item = item,
                        title = title,
                        // 索引侧同样归一化：去掉拼音里的空格/连字符/数字声调
                        pinyin = normalize(py),
                        initials = if (pinyin === PinyinConverter.None) "" else pinyin.initials(item.title),
                    )
                }
            )

        /** 归一化：小写、去标点与空白（保留 CJK 与字母数字）。 */
        fun normalize(text: String): String {
            val sb = StringBuilder(text.length)
            for (ch in text) {
                if (ch.isLetterOrDigit()) sb.append(ch.lowercaseChar())
            }
            return sb.toString()
        }
    }
}

/**
 * 拼音转换（由平台实现）。默认 [None] 表示不做拼音匹配，仅中文 / 原文匹配。
 */
interface PinyinConverter {
    /** 全拼，如「减肥」→ `jianfei`。 */
    fun full(text: String): String

    /** 首字母，如「减内脏脂肪最有效的运动」→ `jnzzzyxdyy`。 */
    fun initials(text: String): String

    object None : PinyinConverter {
        override fun full(text: String): String = ""
        override fun initials(text: String): String = ""
    }
}
