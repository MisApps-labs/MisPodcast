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
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheWriter
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ferlagod.mispodcast.playback.StaleCacheException
import com.ferlagod.mispodcast.playback.StreamCache
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * La caché del streaming no debe mezclar trozos de dos versiones del mismo fichero (anuncios
 * dinámicos): si el servidor devuelve otra longitud, se descarta lo guardado.
 */
@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class StreamCacheVersionTest {

    private lateinit var context: Context
    private lateinit var uri: Uri
    private val server = FakeServer()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        uri = Uri.parse("https://mispodcast.test/${UUID.randomUUID()}.mp3")
    }

    @After
    fun tearDown() {
        StreamCache.get(context).removeResource(uri.toString())
    }

    private fun factory() = StreamCache.cacheDataSourceFactory(context) { server.newSource() }

    private fun precache(position: Long, length: Long) {
        val spec = DataSpec.Builder().setUri(uri).setPosition(position).setLength(length).build()
        CacheWriter(factory().createDataSourceForDownloading(), spec, null, null).cache()
    }

    private fun readAll(): ByteArray {
        val source = factory().createDataSource()
        val out = ByteArrayOutputStream()
        try {
            source.open(DataSpec(uri))
            val buf = ByteArray(64)
            while (true) {
                val n = source.read(buf, 0, buf.size)
                if (n == C.RESULT_END_OF_INPUT) break
                out.write(buf, 0, n)
            }
        } finally {
            source.close()
        }
        return out.toByteArray()
    }

    @Test
    fun sameVersion_readsCachedAndNetworkBytes() {
        server.content = version('A', 1000)
        precache(0, 300)
        assertArrayEquals(version('A', 1000), readAll())
    }

    @Test
    fun changedVersion_discardsCacheAndRetryIsConsistent() {
        server.content = version('A', 1000)
        precache(0, 300)
        // Otro anuncio insertado: el fichero cambia de longitud
        server.content = version('B', 1200)

        try {
            readAll()
            fail("Debía abortar la lectura al detectar otra versión")
        } catch (_: StaleCacheException) {
        }
        // El reintento ya no mezcla nada de la versión antigua
        assertArrayEquals(version('B', 1200), readAll())
    }

    @Test
    fun repeatedChange_doesNotChainErrors() {
        server.content = version('A', 1000)
        precache(0, 300)
        server.content = version('B', 1200)
        runCatching { readAll() }

        // Vuelve a cambiar enseguida: se descarta lo guardado pero sin otro error
        precache(0, 300)
        server.content = version('C', 900)
        val bytes = readAll()
        assertEquals(900, bytes.size)
        precache(0, 100)
        assertArrayEquals(version('C', 900), readAll())
    }

    private fun version(fill: Char, length: Int) = ByteArray(length) { fill.code.toByte() }

    /** Servidor HTTP mínimo con soporte de rangos y cabeceras como las de un CDN real. */
    private class FakeServer {
        @Volatile var content: ByteArray = ByteArray(0)
        fun newSource(): DataSource = FakeHttpSource(this)
    }

    private class FakeHttpSource(private val server: FakeServer) : HttpDataSource {
        private var data = ByteArray(0)
        private var readPos = 0
        private var end = 0
        private var code = 0
        private var headers: Map<String, List<String>> = emptyMap()
        private var openedUri: Uri? = null

        override fun open(dataSpec: DataSpec): Long {
            data = server.content
            val total = data.size.toLong()
            val ranged = dataSpec.position > 0 || dataSpec.length != C.LENGTH_UNSET.toLong()
            val from = dataSpec.position
            val to = if (dataSpec.length == C.LENGTH_UNSET.toLong()) total else minOf(total, from + dataSpec.length)
            readPos = from.toInt()
            end = to.toInt()
            openedUri = dataSpec.uri
            if (ranged) {
                code = 206
                headers = mapOf("content-range" to listOf("bytes $from-${to - 1}/$total"))
            } else {
                code = 200
                headers = mapOf("Content-Length" to listOf("$total"))
            }
            return to - from
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (readPos >= end) return C.RESULT_END_OF_INPUT
            val n = minOf(length, end - readPos)
            System.arraycopy(data, readPos, buffer, offset, n)
            readPos += n
            return n
        }

        override fun close() {
            openedUri = null
        }

        override fun getUri(): Uri? = openedUri
        override fun getResponseCode(): Int = code
        override fun getResponseHeaders(): Map<String, List<String>> = headers
        override fun addTransferListener(transferListener: TransferListener) {}
        override fun setRequestProperty(name: String, value: String) {}
        override fun clearRequestProperty(name: String) {}
        override fun clearAllRequestProperties() {}
    }
}
