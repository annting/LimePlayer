package app.myplayer

/**
 * 应用锁门卫：决定「当前是否需要重新展示解锁页」。
 * 规则（docs/02-技术规范.md §6 应用锁）：
 * - 未启用应用锁 → 永不锁；
 * - 冷启动（没有解锁记录）→ 锁；
 * - 有解锁记录且未超过宽限期 → 不锁，超过 → 锁。
 */
fun shouldLock(lockEnabled: Boolean, lastUnlockAtMillis: Long?, nowMillis: Long, graceMillis: Long): Boolean {
    if (!lockEnabled) return false
    if (lastUnlockAtMillis == null) return true
    return nowMillis - lastUnlockAtMillis > graceMillis
}
