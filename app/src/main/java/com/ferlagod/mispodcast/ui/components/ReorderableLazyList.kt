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

package com.ferlagod.mispodcast.ui.components

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Estado de arrastre para reordenar elementos de una LazyColumn de forma continua:
 * - La fila arrastrada sigue al dedo píxel a píxel (no a saltos).
 * - Se intercambia con la vecina en cuanto su centro entra en ella; las demás se apartan animadas.
 * - Al acercarse a los bordes la lista se desplaza sola, más rápido cuanto más cerca del borde.
 *
 * Solo cambia el orden local mediante [onMove]; quien lo usa decide cuándo guardar (al soltar).
 */
class ReorderableLazyListState internal constructor(
    val listState: LazyListState,
    private val scope: CoroutineScope,
    private val edgeZonePx: Float,
    private val maxScrollPerFramePx: Float,
    private val canMove: () -> (Any) -> Boolean,
    private val onMove: () -> (from: Any, to: Any) -> Unit,
    private val onSwap: () -> () -> Unit
) {
    /** Clave del elemento que se está arrastrando, o null si no hay arrastre. */
    var draggingKey by mutableStateOf<Any?>(null)
        private set

    private var draggedDistance by mutableFloatStateOf(0f)
    private var initialOffset by mutableIntStateOf(0)
    // Índice en el que estaba la fila en el último intercambio: evita deshacerlo antes del siguiente layout
    private var indexAtLastSwap = -1
    private var autoScrollJob: Job? = null

    val isDragging: Boolean get() = draggingKey != null

    private val draggingItemInfo: LazyListItemInfo?
        get() = draggingKey?.let { key -> listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key } }

    /** Desplazamiento vertical a aplicar a la fila arrastrada para que siga al dedo. */
    fun offsetOf(key: Any): Float {
        if (key != draggingKey) return 0f
        val info = draggingItemInfo ?: return 0f
        return initialOffset + draggedDistance - info.offset
    }

    fun onDragStart(key: Any) {
        val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key } ?: return
        draggingKey = key
        initialOffset = info.offset
        draggedDistance = 0f
        indexAtLastSwap = -1
        autoScrollJob?.cancel()
        autoScrollJob = scope.launch {
            while (isActive && draggingKey != null) {
                val amount = autoScrollAmount()
                if (amount != 0f) {
                    val consumed = listState.scrollBy(amount)
                    // La fila arrastrada se mantiene bajo el dedo: compensar lo desplazado
                    draggedDistance += consumed
                    checkForSwap()
                }
                withFrameNanos { }
            }
        }
    }

    fun onDrag(deltaY: Float) {
        if (draggingKey == null) return
        draggedDistance += deltaY
        checkForSwap()
    }

    fun onDragEnd() {
        autoScrollJob?.cancel()
        autoScrollJob = null
        draggingKey = null
        draggedDistance = 0f
        initialOffset = 0
        indexAtLastSwap = -1
    }

    private fun checkForSwap() {
        val current = draggingItemInfo ?: return
        if (indexAtLastSwap == current.index) return
        indexAtLastSwap = -1

        val top = initialOffset + draggedDistance
        val middle = top + current.size / 2f
        val allowed = canMove()
        val target = listState.layoutInfo.visibleItemsInfo.firstOrNull { item ->
            item.index != current.index &&
                allowed(item.key) &&
                middle >= item.offset && middle <= item.offset + item.size
        } ?: return

        // Si interviene el primer elemento visible, LazyColumn seguiría a esa clave y daría un salto
        val first = listState.firstVisibleItemIndex
        if (target.index == first || current.index == first) {
            val firstOffset = listState.firstVisibleItemScrollOffset
            scope.launch { listState.scrollToItem(first, firstOffset) }
        }
        indexAtLastSwap = current.index
        onMove()(current.key, target.key)
        onSwap()()
    }

    private fun autoScrollAmount(): Float {
        val info = draggingItemInfo ?: return 0f
        val layout = listState.layoutInfo
        val top = initialOffset + draggedDistance
        val bottom = top + info.size
        val viewportTop = layout.viewportStartOffset + layout.beforeContentPadding
        val viewportBottom = layout.viewportEndOffset - layout.afterContentPadding
        return when {
            bottom > viewportBottom - edgeZonePx && listState.canScrollForward -> {
                val f = ((bottom - (viewportBottom - edgeZonePx)) / edgeZonePx).coerceIn(0f, 1f)
                maxScrollPerFramePx * f * f
            }
            top < viewportTop + edgeZonePx && listState.canScrollBackward -> {
                val f = (((viewportTop + edgeZonePx) - top) / edgeZonePx).coerceIn(0f, 1f)
                -maxScrollPerFramePx * f * f
            }
            else -> 0f
        }
    }
}

@Composable
fun rememberReorderableLazyListState(
    listState: LazyListState,
    canMove: (key: Any) -> Boolean,
    onMove: (from: Any, to: Any) -> Unit,
    onSwap: () -> Unit = {}
): ReorderableLazyListState {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val currentCanMove by rememberUpdatedState(canMove)
    val currentOnMove by rememberUpdatedState(onMove)
    val currentOnSwap by rememberUpdatedState(onSwap)
    return remember(listState, scope, density) {
        ReorderableLazyListState(
            listState = listState,
            scope = scope,
            edgeZonePx = with(density) { 72.dp.toPx() },
            maxScrollPerFramePx = with(density) { 14.dp.toPx() },
            canMove = { currentCanMove },
            onMove = { currentOnMove },
            onSwap = { currentOnSwap }
        )
    }
}
