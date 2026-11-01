package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.database.ChapterEntity
import com.example.core.database.MangaDatabase
import com.example.core.database.MangaEntity
import com.example.core.database.ReadingHistoryEntity
import com.example.core.database.SourceEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExampleRobolectricTest {

    private lateinit var context: Context
    private lateinit var db: MangaDatabase

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, MangaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `read string from context`() {
        val appName = context.getString(R.string.app_name)
        assertEquals("Manga Reader", appName)
    }

    @Test
    fun `room database prevents duplicate chapters and preserves reading state`() = runTest {
        val dao = db.mangaDao()

        val source = SourceEntity(
            id = "mangalek",
            name = "مانجا ليك Mangalek",
            domain = "mangalik.net",
            baseUrl = "https://mangalik.net",
            language = "ar",
            iconUrl = null,
            isEnabled = true,
            isSupported = true,
            version = 1,
            lastCheckedAt = 1000L
        )
        dao.upsertSource(source)

        val manga = MangaEntity(
            id = "mangalek_murim",
            sourceId = "mangalek",
            remoteId = "https://mangalik.net/manga/murim/",
            title = "Murim Psychopath",
            coverUrl = null,
            description = "قصة موريم",
            status = "OnGoing",
            author = "Tigers",
            artist = "Kirose"
        )
        dao.upsertManga(manga)

        val ch1 = ChapterEntity(
            id = "mangalek_murim_ch1",
            mangaId = "mangalek_murim",
            sourceId = "mangalek",
            remoteId = "ch_1",
            title = "1",
            chapterNumber = 1f,
            uploadDate = 1000L,
            isRead = true,
            isDownloaded = true,
            lastReadPage = 14
        )
        dao.upsertChapters(listOf(ch1))

        // Syncing updated chapter list from remote should preserve isRead, isDownloaded, and lastReadPage
        val incomingCh1 = ch1.copy(isRead = false, isDownloaded = false, lastReadPage = 0)
        val incomingCh2 = ChapterEntity(
            id = "mangalek_murim_ch2",
            mangaId = "mangalek_murim",
            sourceId = "mangalek",
            remoteId = "ch_2",
            title = "2",
            chapterNumber = 2f,
            uploadDate = 2000L
        )
        dao.syncChaptersPreservingState("mangalek_murim", listOf(incomingCh1, incomingCh2))

        val storedChapters = dao.getChaptersByMangaOnce("mangalek_murim")
        assertEquals(2, storedChapters.size)
        assertTrue(storedChapters[0].isRead)
        assertTrue(storedChapters[0].isDownloaded)
        assertEquals(14, storedChapters[0].lastReadPage)

        // Save and restore reading history
        dao.upsertReadingHistory(
            ReadingHistoryEntity(
                id = "hist_mangalek_murim",
                mangaId = "mangalek_murim",
                chapterId = "mangalek_murim_ch1",
                chapterTitle = "1",
                pageIndex = 14,
                scrollOffset = 320,
                updatedAt = 5000L
            )
        )
        val history = dao.observeHistoryForManga("mangalek_murim").first()
        assertEquals(14, history?.pageIndex)
        assertEquals(320, history?.scrollOffset)
    }
}
