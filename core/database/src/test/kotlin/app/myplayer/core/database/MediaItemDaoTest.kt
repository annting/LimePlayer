package app.myplayer.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.myplayer.core.model.*
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MediaItemDaoTest {
    private lateinit var db: MediaDatabase

    @Before fun setup() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, MediaDatabase::class.java).allowMainThreadQueries().build()
    }
    @After fun tearDown() = db.close()

    private fun item(id: String, lib: String, title: String) = MediaItemDto(
        id = id, serverId = "srv", sourceType = SourceType.EMBY, title = title, libraryId = lib,
    )

    @Test fun `upsert then observe library`() = runTest {
        db.mediaItemDao().upsertAll(listOf(item("1", "lib1", "A").toEntity(), item("2", "lib1", "B").toEntity()))
        val rows = db.mediaItemDao().observeLibrary("srv", "lib1").first()
        assertThat(rows.map { it.title }).containsExactly("A", "B")
    }

    @Test fun `upsert is idempotent on primary key`() = runTest {
        db.mediaItemDao().upsertAll(listOf(item("1", "lib1", "A").toEntity()))
        db.mediaItemDao().upsertAll(listOf(item("1", "lib1", "A2").toEntity()))
        assertThat(db.mediaItemDao().count("srv", "lib1")).isEqualTo(1)
    }

    @Test fun `delete by server clears rows`() = runTest {
        db.mediaItemDao().upsertAll(listOf(item("1", "lib1", "A").toEntity(), item("3", "lib2", "C").toEntity()))
        db.mediaItemDao().deleteByServer("srv")
        assertThat(db.mediaItemDao().count("srv", "lib1")).isEqualTo(0)
        assertThat(db.mediaItemDao().count("srv", "lib2")).isEqualTo(0)
    }
}
