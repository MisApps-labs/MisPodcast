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

package com.ferlagod.mispodcast.playback

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import com.ferlagod.mispodcast.data.local.entities.EpisodeEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import kotlin.coroutines.coroutineContext

/**
 * Caché en disco del audio en streaming.
 *
 * - Todo lo que se reproduce por streaming queda guardado (hasta [MAX_CACHE_BYTES], expulsando lo
 *   menos usado), así que retroceder o volver a un episodio no depende de la cobertura.
 * - [StreamPreloader] descarga por adelantado el principio de los siguientes episodios de la cola,
 *   para que arranquen al instante aunque en ese momento no haya red (túneles, zonas sin cobertura).
 *
 * Vive en la carpeta de caché de la app: Android puede vaciarla si se queda sin espacio.
 * Los episodios descargados (ficheros locales) no pasan por aquí.
 */
@OptIn(UnstableApi::class)
object StreamCache {
    private const val TAG = "StreamCache"
    private const val DIR_NAME = "stream_audio_cache"
    const val MAX_CACHE_BYTES = 300L * 1024 * 1024

    @Volatile private var instance: SimpleCache? = null

    /** SimpleCache exige una única instancia por carpeta en todo el proceso. */
    fun get(context: Context): SimpleCache =
        instance ?: synchronized(this) {
            instance ?: SimpleCache(
                File(context.applicationContext.cacheDir, DIR_NAME),
                LeastRecentlyUsedCacheEvictor(MAX_CACHE_BYTES),
                StandaloneDatabaseProvider(context.applicationContext)
            ).also { instance = it }
        }

    /**
     * Fuente de datos que lee de la caché y, si falta algo, de [upstreamFactory] guardándolo a la vez.
     * Si la caché falla (disco lleno, carpeta borrada…) se reproduce directamente desde la red.
     */
    fun cacheDataSourceFactory(context: Context, upstreamFactory: DataSource.Factory): CacheDataSource.Factory =
        CacheDataSource.Factory()
            .setCache(get(context))
            .setUpstreamDataSourceFactory(upstreamFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
}

/**
 * Precarga en la caché el tramo de los próximos episodios por el que van a empezar a sonar:
 * el principio del fichero o, si estaba a medias, la zona de la posición guardada.
 */
@OptIn(UnstableApi::class)
class StreamPreloader(
    private val cacheDataSourceFactory: CacheDataSource.Factory,
    private val scope: CoroutineScope
) {
    private data class Range(val uri: Uri, val position: Long, val length: Long)

    private var job: Job? = null
    private var lastRanges: List<Range>? = null

    /**
     * Precarga los episodios indicados (en orden de prioridad). Si la lista no ha cambiado no hace
     * nada; si ha cambiado, cancela la precarga anterior y empieza la nueva.
     */
    fun preload(episodes: List<Pair<EpisodeEntity, Uri>>) {
        val ranges = episodes.flatMap { (ep, uri) -> rangesFor(ep, uri) }
        if (ranges == lastRanges) return
        lastRanges = ranges
        job?.cancel()
        if (ranges.isEmpty()) return
        job = scope.launch(Dispatchers.IO) {
            for (range in ranges) {
                coroutineContext.ensureActive()
                cacheRange(range)
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        lastRanges = null
    }

    private fun rangesFor(episode: EpisodeEntity, uri: Uri): List<Range> {
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return emptyList()

        val resumeMs = episode.currentPositionMs
        val resumes = resumeMs > 0L && episode.duration > 0L && resumeMs < episode.duration - 5000L
        if (!resumes) return listOf(Range(uri, 0L, START_BYTES))

        // Sin tamaño conocido no se puede estimar dónde cae la posición guardada en el fichero
        val size = episode.fileSizeBytes
        if (size <= 0L) return emptyList()
        // Estimación por tasa de bits constante (la misma que usa ExoPlayer para buscar en MP3)
        val estimated = (size.toDouble() * resumeMs / episode.duration).toLong()
        val from = (estimated - RESUME_LEAD_BYTES).coerceAtLeast(0L)
        val length = (RESUME_BYTES).coerceAtMost(size - from).coerceAtLeast(0L)
        // La cabecera (ID3, Xing…) se lee siempre antes de buscar
        return listOfNotNull(
            Range(uri, 0L, HEADER_BYTES),
            if (length > 0L) Range(uri, from, length) else null
        )
    }

    private suspend fun cacheRange(range: Range) {
        val dataSpec = DataSpec.Builder()
            .setUri(range.uri)
            .setPosition(range.position)
            .setLength(range.length)
            .build()
        val writer = CacheWriter(cacheDataSourceFactory.createDataSourceForDownloading(), dataSpec, null, null)
        val handle = coroutineContext.job.invokeOnCompletion { writer.cancel() }
        try {
            writer.cache()
        } catch (_: InterruptedIOException) {
            // Cancelado: cambió la cola o el episodio
        } catch (e: IOException) {
            // Sin red o el servidor no admite rangos: se intentará de nuevo al reproducir
            Log.d(TAG, "No se pudo precargar ${range.uri}: ${e.message}")
        } finally {
            handle.dispose()
        }
    }

    private companion object {
        const val TAG = "StreamPreloader"
        /** ~3 min a 128 kbps: lo suficiente para arrancar y pasar un túnel. */
        const val START_BYTES = 3L * 1024 * 1024
        const val HEADER_BYTES = 512L * 1024
        const val RESUME_LEAD_BYTES = 256L * 1024
        const val RESUME_BYTES = 3L * 1024 * 1024
    }
}
