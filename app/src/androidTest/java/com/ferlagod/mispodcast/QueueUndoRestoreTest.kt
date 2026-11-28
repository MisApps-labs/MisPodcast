/*
 * Mis Podcast - Gestor y reproductor libre de podcasts para Android
 * Copyright (C) 2026 ferlagod
 *
 * Este programa es software libre: puedes redistribuirlo y/o modificarlo
 * bajo los términos de la Licencia Pública General de GNU tal como fue
 * publicada por la Free Software Foundation, ya sea la versión 3 de la
 * Licencia, o (a tu elección) cualquier versión posterior.
 *
 * Este programa se distribuye con la esperanza de que sea útil,
 * pero SIN NINGUNA GARANTÍA; ni siquiera la garantía implícita de
 * COMERCIABILIDAD o IDONEIDAD PARA UN PROPÓSITO PARTICULAR.
 * Consulta la Licencia Pública General de GNU para más detalles.
 *
 * Deberías haber recibido una copia de la Licencia Pública General de GNU
 * junto con este programa. Si no es así, consulta <https://www.gnu.org/licenses/>.
 */

package com.ferlagod.mispodcast

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ferlagod.mispodcast.data.local.PodcastDatabase
import com.ferlagod.mispodcast.data.local.dao.PodcastDao
import com.ferlagod.mispodcast.data.local.entities.EpisodeEntity
import com.ferlagod.mispodcast.data.local.entities.PodcastEntity
import com.ferlagod.mispodcast.data.local.entities.QueueItemEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Deshacer «Vaciar cola» u «Ordenar» devuelve el orden previo sin mover el episodio que suena
 * ahora de la posición 0 ni reponer el que sonaba si ya terminó.
 */
@RunWith(AndroidJUnit4::class)
class QueueUndoRestoreTest {

    private lateinit var database: PodcastDatabase
    private lateinit var dao: PodcastDao

    @Before
    fun setUp(): Unit = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, PodcastDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.podcastDao()

        val now = System.currentTimeMillis()
        dao.insertPodcast(
            PodcastEntity(
                id = "pod-q",
                title = "Podcast Cola",
                author = "Mis Podcast",
                description = "",
                feedUrl = "https://mispodcast.test/q.xml",
                artworkUrl = "",
                isSubscribed = true,
                subscribedAt = now - 100000L,
                lastUpdated = now,
                category = "Test"
            )
        )
        dao.insertEpisodes((1..5).map { i ->
            EpisodeEntity(
                id = "ep-$i",
                podcastId = "pod-q",
                title = "Episodio $i",
                description = "",
                audioUrl = "https://mispodcast.test/$i.mp3",
                imageUrl = "",
                pubDate = now - i * 1000L,
                duration = 60_000L,
                fileSizeBytes = 0L,
                isPlayed = false
            )
        })
    }

    @After
    fun tearDown() {
        database.close()
    }

    private suspend fun queueOf(vararg ids: String, playing: String? = null) {
        ids.forEachIndexed { i, id ->
            dao.insertQueueItem(QueueItemEntity(episodeId = id, positionIndex = i, isCurrentlyPlaying = id == playing))
        }
    }

    @Test
    fun undoClearWithoutPlayback_keepsEpisodeStartedAfterwardsFirst(): Unit = runBlocking {
        queueOf("ep-1", "ep-2", "ep-3")
        val snapshot = dao.getQueueItemsSync()
        dao.clearQueue()
        // Después de vaciar, empieza a sonar otro episodio
        dao.setCurrentPlaybackInQueue("ep-5")

        dao.restoreQueueSnapshot(snapshot)

        assertEquals(listOf("ep-5", "ep-1", "ep-2", "ep-3"), dao.getOrderedQueueEpisodeIds())
        val playing = dao.getQueueItemsSync().filter { it.isCurrentlyPlaying }.map { it.episodeId }
        assertEquals(listOf("ep-5"), playing)
    }

    @Test
    fun undoClearWithPlayback_restoresOrderBehindPlaying(): Unit = runBlocking {
        queueOf("ep-1", "ep-2", "ep-3", playing = "ep-1")
        val snapshot = dao.getQueueItemsSync()
        dao.clearSubsequentQueue()
        dao.insertQueueItem(QueueItemEntity(episodeId = "ep-4", positionIndex = 1))

        dao.restoreQueueSnapshot(snapshot)

        assertEquals(listOf("ep-1", "ep-2", "ep-3", "ep-4"), dao.getOrderedQueueEpisodeIds())
    }

    @Test
    fun undoClear_doesNotRestoreEpisodeThatFinishedMeanwhile(): Unit = runBlocking {
        queueOf("ep-1", "ep-2", "ep-3", playing = "ep-1")
        val snapshot = dao.getQueueItemsSync()
        dao.clearSubsequentQueue()
        // El que sonaba termina antes de pulsar «Deshacer»
        dao.markAsPlayedAndRemoveFromQueue("ep-1")

        dao.restoreQueueSnapshot(snapshot)

        assertEquals(listOf("ep-2", "ep-3"), dao.getOrderedQueueEpisodeIds())
        assertEquals(0, dao.getQueueItemsSync().count { it.isCurrentlyPlaying })
    }

    @Test
    fun undoSort_restoresManualOrder(): Unit = runBlocking {
        queueOf("ep-1", "ep-3", "ep-2", "ep-4", playing = "ep-1")
        val snapshot = dao.getQueueItemsSync()
        dao.reorderQueue(listOf("ep-1", "ep-2", "ep-3", "ep-4"))

        dao.restoreQueueSnapshot(snapshot)

        assertEquals(listOf("ep-1", "ep-3", "ep-2", "ep-4"), dao.getOrderedQueueEpisodeIds())
    }
}
