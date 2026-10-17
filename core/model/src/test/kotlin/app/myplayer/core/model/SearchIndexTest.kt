package app.myplayer.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SearchIndexTest {

    /** 测试替身：只认测试用到的字，验证拼音通路而不依赖 ICU。 */
    private val fakePinyin = object : PinyinConverter {
        private val map = mapOf(
            "减" to "jian", "肥" to "fei", "操" to "cao", "内" to "nei", "脏" to "zang",
            "脂" to "zhi", "肪" to "fang", "最" to "zui", "有" to "you", "效" to "xiao",
            "的" to "de", "运" to "yun", "动" to "dong", "锻" to "duan", "炼" to "lian",
            "腹" to "fu", "肌" to "ji",
        )

        private fun syllables(text: String) = text.mapNotNull { map[it.toString()] }

        override fun full(text: String): String = syllables(text).joinToString("")

        override fun initials(text: String): String = syllables(text).joinToString("") { it.take(1) }
    }

    private fun item(id: String, title: String) = MediaItemDto(
        id = id, serverId = "s", sourceType = SourceType.EMBY, title = title,
    )

    private val items = listOf(
        item("a", "减肥操"),
        item("b", "减内脏脂肪最有效的运动"),
        item("c", "FigureRobics (1)"),
    )

    private val index = SearchIndex.build(items, fakePinyin)

    @Test
    fun `空查询返回空列表`() {
        assertThat(index.search("")).isEmpty()
        assertThat(index.search("   ")).isEmpty()
    }

    @Test
    fun `中文子串命中`() {
        assertThat(index.search("脂肪").map { it.id }).containsExactly("b")
        assertThat(index.search("肥操").map { it.id }).containsExactly("a")
    }

    @Test
    fun `拼音全拼命中`() {
        assertThat(index.search("jianfei").map { it.id }).containsExactly("a")
        assertThat(index.search("jiannei").map { it.id }).containsExactly("b")
    }

    @Test
    fun `拼音首字母命中`() {
        assertThat(index.search("jfc").map { it.id }).containsExactly("a")
        assertThat(index.search("jnzz").map { it.id }).containsExactly("b")
    }

    @Test
    fun `英文不区分大小写`() {
        assertThat(index.search("figur").map { it.id }).containsExactly("c")
        assertThat(index.search("ROBICS").map { it.id }).containsExactly("c")
    }

    @Test
    fun `标点与空格不影响匹配`() {
        // 「FIGURE-ROBICS (1)」归一化后是 figurerobics1，与标题完全一致
        assertThat(index.search("FIGURE-ROBICS (1)").map { it.id }).containsExactly("c")
        assertThat(index.search("figure robics").map { it.id }).containsExactly("c")
    }

    @Test
    fun `标题前缀优于包含`() {
        val idx = SearchIndex.build(
            listOf(item("contains", "运动减脂"), item("prefix", "减脂运动")),
        )
        assertThat(idx.search("减脂").first().id).isEqualTo("prefix")
    }

    @Test
    fun `无拼音实现时中文仍可搜`() {
        val idx = SearchIndex.build(items)
        assertThat(idx.search("肥操").map { it.id }).containsExactly("a")
        assertThat(idx.search("jianfei")).isEmpty()
    }

    @Test
    fun `归一化只保留字母数字与汉字`() {
        assertThat(SearchIndex.normalize(" FigureRobics (1) ")).isEqualTo("figurerobics1")
        assertThat(SearchIndex.normalize("减 内-脏")).isEqualTo("减内脏")
    }
}
