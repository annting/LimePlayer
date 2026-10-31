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

/**
 * 冷启动时要**立刻**盖解锁页吗（与 ON_START 的宽限期判定 [shouldLock] 是两件事）。
 *
 * `appLockEnabled == null` 表示设置还没从 DataStore 读出来。这种情况一律不盖：
 * 早先写的是「未知 ⇒ 按锁着处理」，于是**开关是关的**用户冷启动时也会先看到一屏
 * 解锁界面，等设置回值再被撤掉 —— 肉眼就是「没开应用锁却闪一下解锁界面」
 * （用户 2026-10-29 反馈）。
 *
 * 那「未知时不盖」会不会漏锁？不会：`MainActivity` 让 NavHost 也等 `settings` 到位
 * 才建（`servers != null && settings != null`），未知态下屏幕上没有可看的内容。
 */
fun shouldShowAppLock(
    appLockEnabled: Boolean?,
    patternConfigured: Boolean,
    unlocked: Boolean,
): Boolean = appLockEnabled == true && patternConfigured && !unlocked
