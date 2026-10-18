package app.myplayer.core.datastore

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SettingsStoreTest {
    @Test fun `默认倍速为一点零且应用锁默认开启`() = runTest {
        val store = InMemorySettingsStore()
        val s = store.settings.first()
        assertThat(s.defaultSpeed).isEqualTo(1.0f)
        assertThat(s.appLockEnabled).isTrue()
        assertThat(s.keepBackgroundPlayback).isFalse()
        assertThat(s.unlockMethod).isEqualTo(UnlockMethod.PATTERN)
        assertThat(s.hideRecentsSnapshot).isFalse()
        assertThat(s.libraryDisplayMode).isEqualTo(LibraryDisplayMode.AUTO)
    }

    @Test fun `setSpeed 会持久化且不污染其它项`() = runTest {
        val store = InMemorySettingsStore()
        store.setDefaultSpeed(1.5f)
        val s = store.settings.first()
        assertThat(s.defaultSpeed).isEqualTo(1.5f)
        assertThat(s.autoPlayNext).isTrue()
    }

    @Test fun `update 是唯一写入口且只改目标项`() = runTest {
        val store = InMemorySettingsStore()
        store.update { it.copy(keepBackgroundPlayback = true, doubleTapSeekMillis = 30_000L) }
        val s = store.settings.first()
        assertThat(s.keepBackgroundPlayback).isTrue()
        assertThat(s.doubleTapSeekMillis).isEqualTo(30_000L)
        assertThat(s.defaultSpeed).isEqualTo(1.0f)
        assertThat(s.appLockEnabled).isTrue()
    }

    @Test fun `画面与字幕设置项有明确默认值`() = runTest {
        val s = InMemorySettingsStore().settings.first()
        assertThat(s.screenOrientation).isEqualTo(ScreenOrientation.AUTO)
        assertThat(s.defaultAspectRatio).isEqualTo(AspectRatio.FIT)
        assertThat(s.subtitleStyle.fontScale).isEqualTo(1.0f)
    }
}
