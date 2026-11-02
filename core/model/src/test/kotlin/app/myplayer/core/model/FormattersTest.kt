package app.myplayer.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** 时长 / 体积展示格式（`03` §6.5）：库页卡片与详情页共用同一实现，两处必须一致。 */
class FormattersTest {

    @Test
    fun `不足一分钟显示秒`() {
        assertThat(formatDuration(Ticks.ofSeconds(45))).isEqualTo("45秒")
        assertThat(formatDuration(Ticks.ofSeconds(59))).isEqualTo("59秒")
    }

    @Test
    fun `不足一小时显示分与秒`() {
        assertThat(formatDuration(Ticks.ofSeconds(31 * 60 + 30))).isEqualTo("31分30秒")
        assertThat(formatDuration(Ticks.ofSeconds(59 * 60 + 59))).isEqualTo("59分59秒")
    }

    @Test
    fun `超过一小时显示时分秒`() {
        assertThat(formatDuration(Ticks.ofSeconds(1 * 3600 + 23 * 60 + 45))).isEqualTo("1小时23分45秒")
        assertThat(formatDuration(Ticks.ofSeconds(3 * 3600 + 5 * 60 + 9))).isEqualTo("3小时5分9秒")
    }

    @Test
    fun `整点与整分省略为 0 的尾部位`() {
        assertThat(formatDuration(Ticks.ofSeconds(2 * 3600))).isEqualTo("2小时")
        assertThat(formatDuration(Ticks.ofSeconds(2 * 3600 + 30 * 60))).isEqualTo("2小时30分")
        assertThat(formatDuration(Ticks.ofSeconds(30 * 60))).isEqualTo("30分")
        // 有小时、秒非零但分为 0：读作「1小时30秒」
        assertThat(formatDuration(Ticks.ofSeconds(1 * 3600 + 30))).isEqualTo("1小时30秒")
    }

    @Test
    fun `未知时长不显示`() {
        assertThat(formatDuration(Ticks.ZERO)).isNull()
    }

    @Test
    fun `不足 1G 显示整数 MB`() {
        assertThat(formatFileSize(500L * 1024 * 1024)).isEqualTo("500MB")
        // 真机素材 FigureRobics (2).avi：39,219,535 字节 ≈ 37.4 MB
        assertThat(formatFileSize(39_219_535L)).isEqualTo("37MB")
    }

    @Test
    fun `不足 1M 显示 KB 而不是 0MB`() {
        // 125,128 字节（8 秒测试片）此前被四舍五入成「0MB」（2026-11-01 用户反馈）
        assertThat(formatFileSize(125_128L)).isEqualTo("122KB")
        assertThat(formatFileSize(1L * 1024)).isEqualTo("1KB")
        // 极小文件也至少显示 1KB，不出现「0KB」
        assertThat(formatFileSize(200L)).isEqualTo("1KB")
        // 边界：正好 1MB 仍走 MB 分支
        assertThat(formatFileSize(1024L * 1024)).isEqualTo("1MB")
    }

    @Test
    fun `1G 及以上显示一位小数 GB`() {
        assertThat(formatFileSize(1024L * 1024 * 1024)).isEqualTo("1.0GB")
        assertThat(formatFileSize(1536L * 1024 * 1024)).isEqualTo("1.5GB")
    }

    @Test
    fun `体积未知或非正时不显示`() {
        assertThat(formatFileSize(0L)).isNull()
        assertThat(formatFileSize(-1L)).isNull()
    }

    @Test
    fun `文件添加年份按本地时区取四位年`() {
        // 2024-06-01 12:00 (UTC+8)
        val ms = java.time.OffsetDateTime.of(2024, 6, 1, 12, 0, 0, 0, java.time.ZoneOffset.ofHours(8))
            .toInstant().toEpochMilli()
        assertThat(formatYear(ms)).isEqualTo("2024")
    }

    @Test
    fun `年份未知时不显示`() {
        assertThat(formatYear(null)).isNull()
        assertThat(formatYear(0L)).isNull()
        assertThat(formatYear(-1L)).isNull()
    }
}
