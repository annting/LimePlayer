package app.myplayer.core.model

import java.util.Locale
import kotlin.math.roundToLong

/**
 * 时长 / 体积的**统一展示格式**（`03` §6.5）。
 *
 * 放在 `:core:model` 而不是各自的 feature 模块：库页卡片元信息与详情页信息分区要用**同一份**
 * 实现，否则两处各写一遍必然写歪（2026-10-19 用户要求统一）。
 */

/**
 * 时长（`Ticks` → 显示串），**全程精确到秒**（2026-10-19 用户要求：各处一律带秒，不丢精度）：
 * - 不足 1 分钟 → 「45秒」；
 * - 不足 1 小时 → 「31分30秒」；
 * - 1 小时及以上 → 「1小时23分45秒」；
 * - 整点 / 整分时省掉为 0 的尾部单位（「2小时」「30分」），不出现「2小时0分0秒」；
 * - 未知或非正 → `null`（调用方据此整项不渲染）。
 *
 * 数字与单位之间**不留空格**（2026-10-19 用户反馈：元信息行用等宽字体，空格的进距是一个完整
 * 字宽，看起来「数字和单位离得太远」）。
 */
fun formatDuration(ticks: Ticks): String? {
    val total = ticks.seconds
    if (total <= 0L) return null
    val hours = total / 3600L
    val minutes = (total % 3600L) / 60L
    val seconds = total % 60L
    return when {
        hours > 0L && minutes > 0L && seconds > 0L -> "${hours}小时${minutes}分${seconds}秒"
        hours > 0L && minutes > 0L -> "${hours}小时${minutes}分"
        hours > 0L && seconds > 0L -> "${hours}小时${seconds}秒"
        hours > 0L -> "${hours}小时"
        minutes > 0L && seconds > 0L -> "${minutes}分${seconds}秒"
        minutes > 0L -> "${minutes}分"
        else -> "${seconds}秒"
    }
}

/**
 * 体积（字节 → 显示串）：单位随量级切换，避免「0.04 GB」这种读不出大小的写法。
 * - 不足 1 GB → 整数 MB（四舍五入）；
 * - 1 GB 及以上 → 1 位小数 GB；
 * - 未知或非正 → `null`（调用方据此整项不渲染）。
 *
 * 数字与单位之间同样不留空格（原因见 [formatDuration]）。
 */
fun formatFileSize(bytes: Long): String? {
    if (bytes <= 0L) return null
    val megabytes = bytes / 1024.0 / 1024.0
    return if (megabytes < 1024.0) {
        "${megabytes.roundToLong()}MB"
    } else {
        String.format(Locale.US, "%.1fGB", megabytes / 1024.0)
    }
}
