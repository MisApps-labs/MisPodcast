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
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import com.ferlagod.mispodcast.data.local.entities.EpisodeEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
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
     * Cada respuesta de la red se contrasta con lo guardado (ver [VersionCheckingDataSource]).
     */
    fun cacheDataSourceFactory(context: Context, upstreamFactory: DataSource.Factory): CacheDataSource.Factory {
        val cache = get(context)
        return CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory { VersionCheckingDataSource(upstreamFactory.createDataSource(), cache) }
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    /** Veces que se ha descartado lo guardado de un episodio por haber cambiado en el servidor. */
    private val evictions = AtomicInteger(0)
    fun evictionCount(): Int = evictions.get()

    /** Último descarte con error de cada clave: evita encadenar errores si el servidor cambia en cada petición. */
    private val lastStaleErrorAt = ConcurrentHashMap<String, Long>()
    private const val STALE_ERROR_INTERVAL_MS = 10 * 60_000L

    /**
     * Comprueba que la respuesta de la red es la misma versión del fichero que hay en la caché.
     *
     * Muchos podcasts insertan anuncios dinámicos: la misma URL puede devolver otro MP3, de otra
     * longitud, en cada petición. Mezclar trozos de dos versiones produce saltos o audio roto, así
     * que si la longitud total no coincide con la guardada se descarta todo lo guardado de esa URL.
     * No se usa el ETag: hay servidores con varios nodos que lo cambian sin que cambie el fichero.
     *
     * @return true si se descartó la caché y conviene abortar la lectura en curso (que se reintente
     *   ya con datos coherentes); false si todo cuadra o no se puede saber.
     */
    internal fun checkVersion(cache: Cache, dataSpec: DataSpec, source: DataSource): Boolean {
        val http = source as? HttpDataSource ?: return false
        val headers = http.responseHeaders
        fun header(name: String): String? =
            headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()

        // «Content-Range: bytes 100-199/12345» o, si el servidor devuelve el fichero entero, Content-Length
        val total = header("Content-Range")?.substringAfterLast('/', "")?.trim()?.toLongOrNull()
            ?: if (http.responseCode == 200) header("Content-Length")?.trim()?.toLongOrNull() else null
        if (total == null || total <= 0L) return false

        val key = dataSpec.key ?: dataSpec.uri.toString()
        val storedLength = ContentMetadata.getContentLength(cache.getContentMetadata(key))
        val mutations = ContentMetadataMutations()
        ContentMetadataMutations.setContentLength(mutations, total)
        if (storedLength == C.LENGTH_UNSET.toLong() || storedLength == total) {
            if (storedLength == C.LENGTH_UNSET.toLong()) cache.applyContentMetadataMutations(key, mutations)
            return false
        }

        Log.i(TAG, "El fichero cambió en el servidor ($storedLength → $total bytes): se descarta lo guardado")
        cache.removeResource(key)
        cache.applyContentMetadataMutations(key, mutations)
        evictions.incrementAndGet()

        val now = SystemClock.elapsedRealtime()
        val last = lastStaleErrorAt[key]
        if (last != null && now - last < STALE_ERROR_INTERVAL_MS) return false
        lastStaleErrorAt[key] = now
        return true
    }
}

/** La versión guardada no coincide con la del servidor: la lectura se reintenta desde cero. */
class StaleCacheException : IOException("El fichero cambió en el servidor")

/**
 * Envuelve la fuente de red de la caché para validar cada respuesta con [StreamCache.checkVersion].
 * Si la caché tenía otra versión, se descarta y se aborta esta lectura: [CacheDataSource] calculó
 * cuánto leer con la longitud antigua, y ExoPlayer (o la precarga) reintenta ya con la nueva.
 */
@OptIn(UnstableApi::class)
private class VersionCheckingDataSource(
    private val upstream: DataSource,
    private val cache: Cache
) : DataSource {
    override fun addTransferListener(transferListener: TransferListener) =
        upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        val length = upstream.open(dataSpec)
        val stale = try {
            StreamCache.checkVersion(cache, dataSpec, upstream)
        } catch (e: Exception) {
            // Un fallo de la comprobación no debe cortar la reproducción
            Log.w("StreamCache", "No se pudo comprobar la versión en caché", e)
            false
        }
        if (stale) {
            runCatching { upstream.close() }
            throw StaleCacheException()
        }
        return length
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = upstream.read(buffer, offset, length)
    override fun getUri(): Uri? = upstream.uri
    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders
    override fun close() = upstream.close()
}

/**
 * Precarga en la caché el tramo de los próximos episodios por el que van a empezar a sonar:
 * el principio del fichero o, si estaba a medias, la zona de la posición guardada.
 *
 * [canPreload] decide si la red actual sirve (p. ej. «Descargar solo con Wi-Fi»): mientras no
 * sirva, la precarga espera. Lo que falle por falta de red se reintenta con espera creciente.
 */
@OptIn(UnstableApi::class)
class StreamPreloader(
    private val cacheDataSourceFactory: CacheDataSource.Factory,
    private val scope: CoroutineScope,
    private val canPreload: () -> Boolean
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
        job = scope.launch(Dispatchers.IO) { preloadWithRetries(ranges) }
    }

    private suspend fun preloadWithRetries(ranges: List<Range>) {
        var pending = ranges
        var failedPasses = 0
        var repeatedAfterEviction = false
        while (pending.isNotEmpty()) {
            if (!canPreload()) {
                delay(WAIT_FOR_NETWORK_MS)
                continue
            }
            val evictionsBefore = StreamCache.evictionCount()
            val failed = mutableListOf<Range>()
            for (range in pending) {
                coroutineContext.ensureActive()
                if (!canPreload() || !cacheRange(range)) failed += range
            }
            // Si algún episodio cambió en el servidor se descartó lo ya guardado (p. ej. la
            // cabecera): se repasa todo una vez para que quede la versión nueva completa
            if (!repeatedAfterEviction && StreamCache.evictionCount() != evictionsBefore) {
                repeatedAfterEviction = true
                pending = ranges
                continue
            }
            pending = failed
            if (pending.isEmpty()) return
            if (canPreload()) {
                if (++failedPasses > MAX_FAILED_PASSES) return
                delay((RETRY_BASE_MS shl (failedPasses - 1)).coerceAtMost(RETRY_MAX_MS))
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

    /** @return true si el tramo quedó en caché (o ya estaba). */
    private suspend fun cacheRange(range: Range): Boolean {
        val dataSpec = DataSpec.Builder()
            .setUri(range.uri)
            .setPosition(range.position)
            .setLength(range.length)
            .build()
        val writer = CacheWriter(cacheDataSourceFactory.createDataSourceForDownloading(), dataSpec, null, null)
        val handle = coroutineContext.job.invokeOnCompletion { writer.cancel() }
        return try {
            writer.cache()
            true
        } catch (_: InterruptedIOException) {
            // Cancelado: cambió la cola o el episodio (si no, ensureActive no lanza y se reintenta)
            coroutineContext.ensureActive()
            false
        } catch (e: IOException) {
            // Sin red, corte o el fichero cambió en el servidor: se reintenta más tarde
            Log.d(TAG, "No se pudo precargar ${range.uri}: ${e.message}")
            false
        } finally {
            handle.dispose()
        }
    }

    private companion object {
        const val TAG = "StreamPreloader"
        /** Comprobación de la red mientras no se permite precargar (sin red o datos móviles). */
        const val WAIT_FOR_NETWORK_MS = 60_000L
        const val RETRY_BASE_MS = 30_000L
        const val RETRY_MAX_MS = 5 * 60_000L
        const val MAX_FAILED_PASSES = 6
        /** ~3 min a 128 kbps: lo suficiente para arrancar y pasar un túnel. */
        const val START_BYTES = 3L * 1024 * 1024
        const val HEADER_BYTES = 512L * 1024
        const val RESUME_LEAD_BYTES = 256L * 1024
        const val RESUME_BYTES = 3L * 1024 * 1024
    }
}
