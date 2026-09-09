package com.fieldnote.ui.screen

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.LineWeight
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.fieldnote.MainViewModel
import com.fieldnote.NoteTodoPlacement
import com.fieldnote.TodoItem
import com.fieldnote.ui.navigation.ScreenFrame
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

private enum class NoteTool {
    Pen,
    Eraser,
    Cut,
    Move
}

/** One recorded point of a stroke. [pressure] is normalized 0f..1f -- Android maps the S Pen's
 * up-to-4096-level hardware pressure range onto this float, we don't see the raw 4096 steps
 * directly, only this already-scaled value. */
@Immutable
private data class StrokePoint(val position: Offset, val pressure: Float = 1f)

@Immutable
private data class InkStroke(
    val color: Color,
    val width: Float,
    val points: List<StrokePoint>
)

@Composable
fun NoteScreen(viewModel: MainViewModel, tabletMode: Boolean) {
    val initialPages = remember(viewModel.currentNote.id) { decodePages(viewModel.currentNote.content) }
    val pageNumbers = remember(viewModel.currentNote.id) {
        mutableStateMapOf<Int, Boolean>().apply {
            initialPages.keys.forEach { put(it, true) }
        }
    }
    val pageStrokes = remember(viewModel.currentNote.id) {
        mutableStateMapOf<Int, List<InkStroke>>().apply { putAll(initialPages) }
    }
    var currentPage by remember { mutableIntStateOf(1) }
    var undoStack by remember { mutableStateOf(emptyList<List<InkStroke>>()) }
    var redoStack by remember { mutableStateOf(emptyList<List<InkStroke>>()) }
    var liveStroke by remember { mutableStateOf<InkStroke?>(null) }
    var lassoPoints by remember { mutableStateOf<List<Offset>?>(null) }
    var selectedStrokes by remember { mutableStateOf<Set<InkStroke>>(emptySet()) }
    var activeTool by remember { mutableStateOf(NoteTool.Pen) }
    var selectedColor by remember { mutableStateOf(Color(0xFF171717)) }
    var strokeWidth by remember { mutableFloatStateOf(6f) }
    var strokeStability by remember { mutableFloatStateOf(DEFAULT_STABILITY) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var penOnlyMode by remember { mutableStateOf(true) }
    var pagesOpen by remember { mutableStateOf(false) }
    var lastLocalContent by remember(viewModel.currentNote.id) { mutableStateOf(viewModel.currentNote.content) }

    LaunchedEffect(viewModel.currentNote.content) {
        if (lastLocalContent != viewModel.currentNote.content) {
            val remotePages = decodePages(viewModel.currentNote.content)
            pageStrokes.clear()
            pageStrokes.putAll(remotePages)
            pageNumbers.clear()
            remotePages.keys.forEach { pageNumbers[it] = true }
            currentPage = remotePages.keys.minOrNull() ?: 1
            undoStack = emptyList()
            redoStack = emptyList()
            selectedStrokes = emptySet()
            lastLocalContent = viewModel.currentNote.content
        }
    }

    fun strokes(): List<InkStroke> = pageStrokes[currentPage].orEmpty()

    fun setStrokes(value: List<InkStroke>) {
        pageStrokes[currentPage] = value
    }

    fun persistNote() {
        val content = encodePages(pageStrokes)
        lastLocalContent = content
        viewModel.saveNoteContent(content)
    }

    fun commitSnapshot(before: List<InkStroke>) {
        undoStack = undoStack + listOf(before)
        redoStack = emptyList()
    }

    fun undo() {
        if (undoStack.isNotEmpty()) {
            redoStack = redoStack + listOf(strokes())
            setStrokes(undoStack.last())
            undoStack = undoStack.dropLast(1)
            selectedStrokes = emptySet()
            persistNote()
        }
    }

    fun redo() {
        if (redoStack.isNotEmpty()) {
            undoStack = undoStack + listOf(strokes())
            setStrokes(redoStack.last())
            redoStack = redoStack.dropLast(1)
            selectedStrokes = emptySet()
            persistNote()
        }
    }

    fun addPage() {
        val nextPage = (pageNumbers.keys.maxOrNull() ?: 0) + 1
        pageNumbers[nextPage] = true
        pageStrokes[nextPage] = emptyList()
        currentPage = nextPage
        undoStack = emptyList()
        redoStack = emptyList()
        liveStroke = null
        selectedStrokes = emptySet()
        pan = Offset.Zero
        zoom = 1f
        persistNote()
    }

    fun selectPage(page: Int) {
        currentPage = page
        undoStack = emptyList()
        redoStack = emptyList()
        liveStroke = null
        selectedStrokes = emptySet()
        pagesOpen = false
    }

    fun selectTool(tool: NoteTool) {
        activeTool = tool
        selectedStrokes = emptySet()
    }

    fun deleteSelection() {
        if (selectedStrokes.isEmpty()) return
        val before = strokes()
        commitSnapshot(before)
        setStrokes(before.filterNot { it in selectedStrokes })
        selectedStrokes = emptySet()
        persistNote()
    }

    ScreenFrame(
        title = "필드 노트",
        subtitle = "S펜 중심 필기 작업 공간"
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            NoteTopBar(
                activeTool = activeTool,
                onToolSelected = ::selectTool,
                selectedColor = selectedColor,
                onColorSelected = { selectedColor = it },
                strokeWidth = strokeWidth,
                onStrokeWidthChange = { strokeWidth = it },
                strokeStability = strokeStability,
                onStrokeStabilityChange = { strokeStability = it },
                penOnlyMode = penOnlyMode,
                onPenOnlyModeChange = { penOnlyMode = it },
                canUndo = undoStack.isNotEmpty(),
                canRedo = redoStack.isNotEmpty(),
                onUndo = ::undo,
                onRedo = ::redo,
                zoom = zoom,
                onZoomIn = { zoom = (zoom + 0.25f).coerceAtMost(4f) },
                onZoomOut = { zoom = (zoom - 0.25f).coerceAtMost(4f).coerceAtLeast(0.5f) },
                pagesOpen = pagesOpen,
                onTogglePages = { pagesOpen = !pagesOpen }
            )

            PagesHandle(
                pagesOpen = pagesOpen,
                currentPage = currentPage,
                pageCount = pageNumbers.size,
                onToggle = { pagesOpen = !pagesOpen },
                onOpen = { pagesOpen = true },
                onClose = { pagesOpen = false }
            )
            AnimatedVisibility(visible = pagesOpen, enter = expandVertically(), exit = shrinkVertically()) {
                PagesDrawer(
                    pageNumbers = pageNumbers.keys.sorted(),
                    currentPage = currentPage,
                    onPageSelected = ::selectPage,
                    onAddPage = ::addPage
                )
            }

            if (selectedStrokes.isNotEmpty()) {
                SelectionActionBar(count = selectedStrokes.size, onDelete = ::deleteSelection, onClear = { selectedStrokes = emptySet() })
            }

            NoteCanvas(
                strokes = strokes(),
                liveStroke = liveStroke,
                lassoPoints = lassoPoints,
                selectedStrokes = selectedStrokes,
                todoItems = viewModel.todos,
                todoPlacements = viewModel.noteTodoPlacements,
                activeTool = activeTool,
                selectedColor = selectedColor,
                strokeWidth = strokeWidth,
                strokeStability = strokeStability,
                zoom = zoom,
                pan = pan,
                penOnlyMode = penOnlyMode,
                onZoomChange = { zoom = it },
                onPanChange = { pan = it },
                onLiveStrokeChange = { liveStroke = it },
                onLassoChange = { lassoPoints = it },
                onStrokeCommitted = { before, stroke ->
                    commitSnapshot(before)
                    setStrokes(before + stroke)
                    liveStroke = null
                    persistNote()
                },
                onEraseCommitted = { before, after ->
                    commitSnapshot(before)
                    setStrokes(after)
                    persistNote()
                },
                onSelectionChanged = { selectedStrokes = it },
                onTodoMoved = viewModel::moveNoteTodo,
                onTodoResized = viewModel::resizeNoteTodo,
                onTodoRemoved = viewModel::removeNoteTodo,
                onTodoFixedChanged = viewModel::toggleNoteTodoFixed,
                onTodoCheckedChanged = viewModel::toggleTodo,
                modifier = Modifier.fillMaxWidth().weight(1f)
            )
        }
    }
}

private fun encodePages(pages: Map<Int, List<InkStroke>>): String {
    val pageObject = JSONObject()
    pages.toSortedMap().forEach { (page, strokes) ->
        val strokeArray = JSONArray()
        strokes.forEach { stroke ->
            val points = JSONArray()
            stroke.points.forEach { point ->
                points.put(
                    JSONArray()
                        .put(point.position.x.toDouble())
                        .put(point.position.y.toDouble())
                        .put(point.pressure.toDouble())
                )
            }
            strokeArray.put(
                JSONObject()
                    .put("color", stroke.color.toArgb())
                    .put("width", stroke.width.toDouble())
                    .put("points", points)
            )
        }
        pageObject.put(page.toString(), strokeArray)
    }
    return JSONObject().put("pages", pageObject).toString()
}

private fun decodePages(content: String): Map<Int, List<InkStroke>> = runCatching {
    val result = mutableMapOf<Int, List<InkStroke>>()
    val pages = JSONObject(content).getJSONObject("pages")
    pages.keys().forEach { pageKey ->
        val strokesJson = pages.getJSONArray(pageKey)
        val strokes = buildList {
            for (strokeIndex in 0 until strokesJson.length()) {
                val strokeJson = strokesJson.getJSONObject(strokeIndex)
                val pointsJson = strokeJson.getJSONArray("points")
                val points = buildList {
                    for (pointIndex in 0 until pointsJson.length()) {
                        val point = pointsJson.getJSONArray(pointIndex)
                        // optDouble(2, 1.0) keeps notes saved before pressure was recorded
                        // loading fine, defaulting them to full width.
                        add(
                            StrokePoint(
                                Offset(point.getDouble(0).toFloat(), point.getDouble(1).toFloat()),
                                point.optDouble(2, 1.0).toFloat()
                            )
                        )
                    }
                }
                add(
                    InkStroke(
                        color = Color(strokeJson.getInt("color")),
                        width = strokeJson.getDouble("width").toFloat(),
                        points = points
                    )
                )
            }
        }
        result[pageKey.toInt()] = strokes
    }
    result.ifEmpty { mapOf(1 to emptyList()) }
}.getOrElse { mapOf(1 to emptyList()) }

@Composable
private fun NoteTopBar(
    activeTool: NoteTool,
    onToolSelected: (NoteTool) -> Unit,
    selectedColor: Color,
    onColorSelected: (Color) -> Unit,
    strokeWidth: Float,
    onStrokeWidthChange: (Float) -> Unit,
    strokeStability: Float,
    onStrokeStabilityChange: (Float) -> Unit,
    penOnlyMode: Boolean,
    onPenOnlyModeChange: (Boolean) -> Unit,
    canUndo: Boolean,
    canRedo: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    zoom: Float,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    pagesOpen: Boolean,
    onTogglePages: () -> Unit
) {
    var penMenuOpen by remember { mutableStateOf(false) }
    val colors = listOf(Color(0xFF171717), Color(0xFF1E5AA8), Color(0xFFC0392B), Color(0xFF2E7D32))

    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 4.dp, vertical = 4.dp)
        ) {
            IconButton(onClick = onTogglePages) {
                Icon(
                    if (pagesOpen) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = "페이지 목록"
                )
            }

            Box {
                // Tapping the pen icon both selects the Pen tool and reveals its settings
                // (color/width/stability) below, so there's one obvious place to tune them
                // instead of a separate unrelated icon.
                ToolIconButton(Icons.Filled.Edit, "펜", activeTool == NoteTool.Pen) {
                    onToolSelected(NoteTool.Pen)
                    penMenuOpen = true
                }
                DropdownMenu(expanded = penMenuOpen, onDismissRequest = { penMenuOpen = false }) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            colors.forEach { color ->
                                ColorSwatch(color = color, selected = selectedColor == color) {
                                    onColorSelected(color)
                                    onToolSelected(NoteTool.Pen)
                                }
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Filled.LineWeight, contentDescription = null)
                            Text(text = "굵기 ${strokeWidth.toInt()}", style = MaterialTheme.typography.bodySmall)
                        }
                        Slider(
                            value = strokeWidth,
                            onValueChange = onStrokeWidthChange,
                            valueRange = 2f..22f,
                            steps = 9,
                            modifier = Modifier.width(200.dp)
                        )
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Filled.Palette, contentDescription = null)
                            Text(text = "필체 안정성 ${(strokeStability * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                        }
                        Slider(
                            value = strokeStability * 100f,
                            onValueChange = { onStrokeStabilityChange((it / 100f).coerceIn(0.01f, 1f)) },
                            valueRange = 1f..100f,
                            modifier = Modifier.width(200.dp)
                        )
                    }
                }
            }
            ToolIconButton(Icons.AutoMirrored.Filled.Backspace, "지우개", activeTool == NoteTool.Eraser) { onToolSelected(NoteTool.Eraser) }
            ToolIconButton(Icons.Filled.ContentCut, "자르기", activeTool == NoteTool.Cut) { onToolSelected(NoteTool.Cut) }
            ToolIconButton(Icons.Filled.OpenWith, "이동", activeTool == NoteTool.Move) { onToolSelected(NoteTool.Move) }

            IconButton(onClick = { onPenOnlyModeChange(!penOnlyMode) }) {
                Icon(
                    Icons.Filled.TouchApp,
                    contentDescription = "손가락 입력 허용",
                    tint = if (penOnlyMode) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary
                )
            }

            IconButton(onClick = onUndo, enabled = canUndo) { Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "되돌리기") }
            IconButton(onClick = onRedo, enabled = canRedo) { Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "다시 실행") }

            IconButton(onClick = onZoomOut) { Icon(Icons.Filled.ZoomOut, contentDescription = "축소") }
            Text(text = "${(zoom * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
            IconButton(onClick = onZoomIn) { Icon(Icons.Filled.ZoomIn, contentDescription = "확대") }
        }
    }
}

@Composable
private fun ToolIconButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun PagesHandle(
    pagesOpen: Boolean,
    currentPage: Int,
    pageCount: Int,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    onClose: () -> Unit
) {
    var dragAccumulated by remember { mutableFloatStateOf(0f) }
    Row(
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp)
            .clickable(onClick = onToggle)
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragEnd = { dragAccumulated = 0f },
                    onDragCancel = { dragAccumulated = 0f },
                    onVerticalDrag = { change, dragAmount ->
                        change.consume()
                        dragAccumulated += dragAmount
                        if (dragAccumulated > 40f) {
                            onOpen()
                            dragAccumulated = 0f
                        } else if (dragAccumulated < -40f) {
                            onClose()
                            dragAccumulated = 0f
                        }
                    }
                )
            }
    ) {
        Icon(
            if (pagesOpen) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription = null,
            modifier = Modifier.size(16.dp)
        )
        Text(text = "페이지 $currentPage/$pageCount", style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun PagesDrawer(
    pageNumbers: List<Int>,
    currentPage: Int,
    onPageSelected: (Int) -> Unit,
    onAddPage: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(10.dp)
        ) {
            pageNumbers.forEach { page ->
                AssistChip(onClick = { onPageSelected(page) }, label = { Text(if (page == currentPage) "${page}쪽*" else "${page}쪽") })
            }
            IconButton(onClick = onAddPage) { Icon(Icons.Filled.Add, contentDescription = "페이지 추가") }
        }
    }
}

@Composable
private fun SelectionActionBar(count: Int, onDelete: () -> Unit, onClear: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    ) {
        Text(text = "${count}개 선택됨 · 드래그하면 이동", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "선택 삭제") }
        AssistChip(onClick = onClear, label = { Text("선택 해제") })
    }
}

@Composable
private fun ColorSwatch(color: Color, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(if (selected) 34.dp else 30.dp)
            .clip(CircleShape)
            .border(2.dp, if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape)
            .background(color, CircleShape)
            .clickable(onClick = onClick)
    )
}

@Composable
private fun NoteCanvas(
    strokes: List<InkStroke>,
    liveStroke: InkStroke?,
    lassoPoints: List<Offset>?,
    selectedStrokes: Set<InkStroke>,
    todoItems: List<TodoItem>,
    todoPlacements: List<NoteTodoPlacement>,
    activeTool: NoteTool,
    selectedColor: Color,
    strokeWidth: Float,
    strokeStability: Float,
    zoom: Float,
    pan: Offset,
    penOnlyMode: Boolean,
    onZoomChange: (Float) -> Unit,
    onPanChange: (Offset) -> Unit,
    onLiveStrokeChange: (InkStroke?) -> Unit,
    onLassoChange: (List<Offset>?) -> Unit,
    onStrokeCommitted: (before: List<InkStroke>, stroke: InkStroke) -> Unit,
    onEraseCommitted: (before: List<InkStroke>, after: List<InkStroke>) -> Unit,
    onSelectionChanged: (Set<InkStroke>) -> Unit,
    onTodoMoved: (todoId: Long, dx: Float, dy: Float) -> Unit,
    onTodoResized: (todoId: Long, widthDeltaDp: Float, heightDeltaDp: Float) -> Unit,
    onTodoRemoved: (todoId: Long) -> Unit,
    onTodoFixedChanged: (todoId: Long) -> Unit,
    onTodoCheckedChanged: (todoId: Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val paperColor = Color(0xFFFFFCF6)
    val lineColor = Color(0xFFE5DDCD)
    val paperShape = RoundedCornerShape(8.dp)

    // zoom/pan change continuously *during* the very pinch/pan/move gesture that produces them
    // (onZoomChange/onPanChange fire every frame). Using them as pointerInput keys used to
    // restart the gesture coroutine on every one of those updates -- awaitFirstDown() in the new
    // coroutine then just hung waiting for a fresh touch-down that never came (the fingers were
    // already down), so pinch-zoom visibly moved once and then stopped responding. Reading them
    // through rememberUpdatedState instead lets the same long-lived coroutine see the latest
    // value without needing to be a key.
    val latestZoom = rememberUpdatedState(zoom)
    val latestPan = rememberUpdatedState(pan)

    fun toNotePoint(screenPoint: Offset): Offset = (screenPoint - latestPan.value) / latestZoom.value

    Box(
        modifier = modifier
            .clip(paperShape)
            .background(paperColor)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, paperShape)
            .pointerInput(activeTool, selectedColor, strokeWidth, strokeStability, penOnlyMode, strokes.size, selectedStrokes) {
                awaitEachGesture {
                    // requireUnconsumed defaults to true: a touch that landed on a todo card
                    // (which now consumes its own down/move events, see TodoOnNoteCard) is
                    // skipped here instead of also starting a drawing gesture underneath it.
                    val down = awaitFirstDown()
                    val before = strokes.toList()

                    if (activeTool == NoteTool.Move) {
                        var nextPan = latestPan.value
                        var nextZoom = latestZoom.value
                        while (true) {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.isEmpty()) break
                            if (pressed.size >= 2) {
                                val transform = calculateGestureTransform(pressed, nextZoom, nextPan)
                                nextZoom = transform.zoom
                                nextPan = transform.pan
                                onZoomChange(nextZoom)
                                onPanChange(nextPan)
                            } else {
                                val change = pressed.firstOrNull { it.id == down.id } ?: pressed.first()
                                nextPan += change.positionChange()
                                onPanChange(nextPan)
                            }
                        }
                        return@awaitEachGesture
                    }

                    if (activeTool == NoteTool.Cut) {
                        // A second finger pinch-zooms/pans just like every other tool -- this was
                        // previously only wired up for Pen/Eraser/Move, so zoom silently did
                        // nothing while Cut was active.
                        var cutNextPan = latestPan.value
                        var cutNextZoom = latestZoom.value
                        if (selectedStrokes.isNotEmpty()) {
                            // A selection already exists: this drag moves it instead of starting
                            // a new lasso.
                            var lastPoint = toNotePoint(down.position)
                            var moved = before
                            while (true) {
                                val event = awaitPointerEvent()
                                val pressed = event.changes.filter { it.pressed }
                                if (pressed.isEmpty()) break
                                if (pressed.size >= 2) {
                                    val transform = calculateGestureTransform(pressed, cutNextZoom, cutNextPan)
                                    cutNextZoom = transform.zoom
                                    cutNextPan = transform.pan
                                    onZoomChange(cutNextZoom)
                                    onPanChange(cutNextPan)
                                    lastPoint = toNotePoint(pressed.first().position)
                                    continue
                                }
                                val change = pressed.firstOrNull { it.id == down.id } ?: pressed.first()
                                val notePoint = toNotePoint(change.position)
                                val delta = notePoint - lastPoint
                                lastPoint = notePoint
                                moved = moved.map { stroke ->
                                    if (stroke in selectedStrokes) {
                                        stroke.copy(points = stroke.points.map { it.copy(position = it.position + delta) })
                                    } else {
                                        stroke
                                    }
                                }
                            }
                            if (moved != before) {
                                onEraseCommitted(before, moved)
                                // moved is before mapped 1:1 (same size/order), so pair them up to
                                // find selected strokes' new (moved) instances for the next drag.
                                val newSelection = before.indices
                                    .filter { before[it] in selectedStrokes }
                                    .map { moved[it] }
                                    .toSet()
                                onSelectionChanged(newSelection)
                            }
                        } else {
                            val lasso = mutableListOf(toNotePoint(down.position))
                            onLassoChange(lasso.toList())
                            while (true) {
                                val event = awaitPointerEvent()
                                val pressed = event.changes.filter { it.pressed }
                                if (pressed.isEmpty()) break
                                if (pressed.size >= 2) {
                                    val transform = calculateGestureTransform(pressed, cutNextZoom, cutNextPan)
                                    cutNextZoom = transform.zoom
                                    cutNextPan = transform.pan
                                    onZoomChange(cutNextZoom)
                                    onPanChange(cutNextPan)
                                    continue
                                }
                                val change = pressed.firstOrNull { it.id == down.id } ?: pressed.first()
                                lasso.add(toNotePoint(change.position))
                                onLassoChange(lasso.toList())
                            }
                            onLassoChange(null)
                            if (lasso.size >= 3) {
                                onSelectionChanged(strokesInLasso(before, lasso))
                            }
                        }
                        return@awaitEachGesture
                    }

                    var nextPan = latestPan.value
                    var nextZoom = latestZoom.value
                    val points = mutableListOf<StrokePoint>()
                    var erased = before
                    var transformed = false
                    var lastPenPoint: StrokePoint? = null
                    // The actual tool for THIS gesture is resolved once, from the first real
                    // pointer event -- not assumed up front. Previously the down position was
                    // added straight into `points` before anything was known about the S Pen's
                    // side button, so a button-held erase gesture still ended up committing that
                    // one leftover point as a 1-point Pen stroke (a stray dot) instead of erasing.
                    var resolved: NoteTool? = null
                    var downHandled = false

                    while (true) {
                        val event = awaitPointerEvent()

                        if (!downHandled) {
                            downHandled = true
                            if (!penOnlyMode || down.type.isStylusInput()) {
                                resolved = when {
                                    down.type == PointerType.Eraser -> NoteTool.Eraser
                                    down.type == PointerType.Stylus && event.buttons.isPrimaryPressed -> NoteTool.Eraser
                                    else -> activeTool
                                }
                                val downPoint = toNotePoint(down.position)
                                if (resolved == NoteTool.Pen) {
                                    val point = StrokePoint(downPoint, down.pressure.coerceIn(0f, 1f))
                                    points.add(point)
                                    lastPenPoint = point
                                } else {
                                    erased = eraseNear(erased, downPoint, max(24f, strokeWidth * 2.5f) / nextZoom)
                                }
                            }
                        }

                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break

                        if (pressed.size >= 2) {
                            transformed = true
                            onLiveStrokeChange(null)
                            val transform = calculateGestureTransform(pressed, nextZoom, nextPan)
                            nextZoom = transform.zoom
                            nextPan = transform.pan
                            onZoomChange(nextZoom)
                            onPanChange(nextPan)
                            continue
                        }

                        if (transformed) continue
                        val change = pressed.firstOrNull { it.id == down.id } ?: pressed.first()
                        if (penOnlyMode && !change.type.isStylusInput()) continue
                        val notePoint = (change.position - nextPan) / nextZoom
                        // The S Pen's hardware eraser tip reports PointerType.Eraser. Holding the
                        // pen's side button while the tip touches down reports PointerType.Stylus
                        // with the primary button flagged in the pointer event -- treat that the
                        // same way, so either one erases without switching tools.
                        val drawingTool = when {
                            change.type == PointerType.Eraser -> NoteTool.Eraser
                            change.type == PointerType.Stylus && event.buttons.isPrimaryPressed -> NoteTool.Eraser
                            else -> activeTool
                        }

                        if (drawingTool == NoteTool.Pen) {
                            val point = StrokePoint(notePoint, change.pressure.coerceIn(0f, 1f))
                            lastPenPoint = point
                            val pointAdded = appendStrokePoint(points, point, strokeStability)
                            if (pointAdded) {
                                onLiveStrokeChange(InkStroke(selectedColor, strokeWidth, stabilizeLive(points, strokeStability)))
                            }
                        } else {
                            erased = eraseNear(erased, notePoint, max(24f, strokeWidth * 2.5f) / nextZoom)
                        }
                    }

                    if (!transformed) {
                        lastPenPoint?.let { appendStrokeEndpoint(points, it) }
                        if (before != erased) {
                            onEraseCommitted(before, erased)
                        } else if (resolved == NoteTool.Pen && points.isNotEmpty()) {
                            onStrokeCommitted(before, InkStroke(selectedColor, strokeWidth, finalizeStroke(points, strokeStability)))
                        }
                    }
                }
            }
            .padding(1.dp)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val horizontalGap = 32.dp.toPx() * zoom
            var y = pan.y + 48.dp.toPx() * zoom
            while (y < size.height) {
                if (y >= 0f) {
                    drawLine(lineColor, Offset(24.dp.toPx(), y), Offset(size.width - 24.dp.toPx(), y), 1.dp.toPx())
                }
                y += horizontalGap
            }

            drawContext.canvas.save()
            drawContext.canvas.translate(pan.x, pan.y)
            drawContext.canvas.scale(zoom, zoom)
            strokes.forEach { stroke ->
                drawInkStroke(stroke)
                if (stroke in selectedStrokes) drawSelectionOutline(stroke)
            }
            liveStroke?.let { drawInkStroke(it) }
            lassoPoints?.let { drawLasso(it) }
            drawContext.canvas.restore()
        }

        todoPlacements.forEach { placement ->
            val todo = todoItems.firstOrNull { it.id == placement.todoId }
            if (todo != null) {
                TodoOnNoteCard(
                    todo = todo,
                    placement = placement,
                    zoom = zoom,
                    pan = pan,
                    onMoved = onTodoMoved,
                    onResized = onTodoResized,
                    onRemoved = onTodoRemoved,
                    onFixedChanged = onTodoFixedChanged,
                    onCheckedChanged = onTodoCheckedChanged
                )
            }
        }
    }
}

@Composable
private fun TodoOnNoteCard(
    todo: TodoItem,
    placement: NoteTodoPlacement,
    zoom: Float,
    pan: Offset,
    onMoved: (todoId: Long, dx: Float, dy: Float) -> Unit,
    onResized: (todoId: Long, widthDeltaDp: Float, heightDeltaDp: Float) -> Unit,
    onRemoved: (todoId: Long) -> Unit,
    onFixedChanged: (todoId: Long) -> Unit,
    onCheckedChanged: (todoId: Long) -> Unit
) {
    val density = LocalDensity.current
    Card(
        modifier = Modifier
            .offset { IntOffset((placement.x * zoom + pan.x).roundToInt(), (placement.y * zoom + pan.y).roundToInt()) }
            .graphicsLayer {
                scaleX = zoom
                scaleY = zoom
                transformOrigin = TransformOrigin(0f, 0f)
            }
            .width(placement.widthDp.dp)
            .height(placement.heightDp.dp)
            .pointerInput(placement.fixed) {
                // Consume the down (and every move) ourselves, immediately -- detectDragGestures
                // only consumes once the drag exceeds touch slop, which was late enough that
                // NoteCanvas's own gesture (which ignored consumption via requireUnconsumed =
                // false) still saw an "unconsumed" down on the card and started drawing a stroke
                // underneath it. Consuming from the first event blocks that at the source.
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: event.changes.firstOrNull()
                        if (change == null || !change.pressed) break
                        val dragAmount = change.positionChange()
                        change.consume()
                        if (!placement.fixed) onMoved(placement.todoId, dragAmount.x, dragAmount.y)
                    }
                }
            },
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Checkbox(checked = todo.completed, onCheckedChange = { onCheckedChanged(todo.id) })
                Text(
                    text = todo.title,
                    style = MaterialTheme.typography.bodyMedium,
                    textDecoration = if (todo.completed) TextDecoration.LineThrough else TextDecoration.None,
                    modifier = Modifier.weight(1f)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                AssistChip(onClick = { onFixedChanged(todo.id) }, label = { Text(if (placement.fixed) "고정 해제" else "고정") })
                AssistChip(onClick = { onRemoved(todo.id) }, enabled = !placement.fixed, label = { Text("제거") })
                if (todo.dueDay != null) AssistChip(onClick = {}, label = { Text("9월 ${todo.dueDay}일") })
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(22.dp)
                    .pointerInput(placement.fixed) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            down.consume()
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: event.changes.firstOrNull()
                                if (change == null || !change.pressed) break
                                val dragAmount = change.positionChange()
                                change.consume()
                                if (!placement.fixed) {
                                    with(density) {
                                        onResized(todo.id, dragAmount.x.toDp().value, dragAmount.y.toDp().value)
                                    }
                                }
                            }
                        }
                    },
                contentAlignment = Alignment.CenterEnd
            ) {
                Text(text = if (placement.fixed) "고정됨" else "크기 조정", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

private fun PointerType.isStylusInput(): Boolean = this == PointerType.Stylus || this == PointerType.Eraser

private fun calculateZoomDelta(changes: List<PointerInputChange>): Float {
    val currentCentroid = changes.map { it.position }.averageOffset()
    val previousCentroid = changes.map { it.previousPosition }.averageOffset()
    val currentDistance = changes.map { distance(it.position, currentCentroid) }.average().toFloat()
    val previousDistance = changes.map { distance(it.previousPosition, previousCentroid) }.average().toFloat()
    if (previousDistance <= 0.5f || currentDistance <= 0.5f) return 1f
    return (currentDistance / previousDistance).coerceIn(0.85f, 1.18f)
}

internal data class CanvasTransform(val zoom: Float, val pan: Offset)

private fun calculateGestureTransform(
    changes: List<PointerInputChange>,
    zoom: Float,
    pan: Offset
): CanvasTransform = transformAroundCentroid(
    zoom = zoom,
    pan = pan,
    previousCentroid = changes.map { it.previousPosition }.averageOffset(),
    currentCentroid = changes.map { it.position }.averageOffset(),
    zoomDelta = calculateZoomDelta(changes)
)

internal fun transformAroundCentroid(
    zoom: Float,
    pan: Offset,
    previousCentroid: Offset,
    currentCentroid: Offset,
    zoomDelta: Float
): CanvasTransform {
    val nextZoom = (zoom * zoomDelta).coerceIn(0.5f, 4f)
    val appliedZoomDelta = nextZoom / zoom
    val nextPan = currentCentroid - (previousCentroid - pan) * appliedZoomDelta
    return CanvasTransform(nextZoom, nextPan)
}

private fun List<Offset>.averageOffset(): Offset {
    if (isEmpty()) return Offset.Zero
    val x = sumOf { it.x.toDouble() }.toFloat() / size
    val y = sumOf { it.y.toDouble() }.toFloat() / size
    return Offset(x, y)
}

private fun distance(a: Offset, b: Offset): Double {
    val dx = (a.x - b.x).toDouble()
    val dy = (a.y - b.y).toDouble()
    return sqrt(dx * dx + dy * dy)
}

/** Default handwriting stability: 70%, adjustable 1%-100% from the pen settings panel. */
private const val DEFAULT_STABILITY = 0.7f

/** Cheap per-frame filtering for the live preview while the pen is still moving: dedupe
 * near-duplicate points and drop a tail that whips back sharply (the "hook" flick that shows up
 * right as a fast stroke starts to lift). Kept light so it can run on every touch-move event.
 * [stability] (0f..1f, from the settings slider) scales how aggressively points get merged and
 * how big a "hook" has to be before it's trimmed -- higher stability = steadier line, lower =
 * closer to the raw input. */
private fun appendStrokePoint(
    points: MutableList<StrokePoint>,
    point: StrokePoint,
    stability: Float
): Boolean {
    if (points.isEmpty()) {
        points.add(point)
        return true
    }
    val minDistance = 0.4 + stability.coerceIn(0f, 1f) * 2.0
    if (distance(points.last().position, point.position) >= minDistance) {
        points.add(point)
        return true
    }
    return false
}

private fun appendStrokeEndpoint(points: MutableList<StrokePoint>, point: StrokePoint) {
    if (points.isEmpty() || distance(points.last().position, point.position) >= 0.1) {
        points.add(point)
    }
}

private fun stabilizeLive(raw: List<StrokePoint>, stability: Float): List<StrokePoint> {
    if (raw.size <= 2) return raw
    val s = stability.coerceIn(0f, 1f)
    val hookShortLength = 3.0 + s * 10.0
    val hookReversalLength = 8.0 + s * 20.0
    val stable = raw.toMutableList()
    val last = stable.last().position
    val prev = stable[stable.lastIndex - 1].position
    val beforePrev = stable[stable.lastIndex - 2].position
    val v1 = prev - beforePrev
    val v2 = last - prev
    val lastLength = distance(prev, last)
    val dot = v1.x * v2.x + v1.y * v2.y
    if (lastLength < hookShortLength || (dot < 0f && lastLength < hookReversalLength)) {
        stable.removeAt(stable.lastIndex)
    }
    return stable
}

/**
 * One-time, higher-quality pass applied when a stroke is finalized (pen lift):
 * - Runs [stabilizeLive] first (dedupe + tail-hook trim).
 * - Trims a short hook at the START too -- the live filter only ever sees the tail, since the
 *   start point is fixed the moment the pen touches down.
 * - A 3-point weighted moving average rounds out sharp corners and smooths the zigzag that fast
 *   handwriting produces, without moving the fixed first/last point (keeps the stroke anchored
 *   exactly where the pen touched down and lifted). [stability] scales how strong that averaging
 *   is: 0% leaves points untouched, 100% is the strongest smoothing offered.
 */
private fun finalizeStroke(raw: List<StrokePoint>, stability: Float): List<StrokePoint> {
    val s = stability.coerceIn(0f, 1f)
    var points = stabilizeLive(raw, s)
    if (points.size < 5) return points

    val first = points[0].position
    val second = points[1].position
    val third = points[2].position
    val v1 = second - first
    val v2 = third - second
    val firstLength = distance(first, second)
    val dot = v1.x * v2.x + v1.y * v2.y
    val hookShortLength = 3.0 + s * 10.0
    val hookReversalLength = 8.0 + s * 20.0
    if (firstLength < hookShortLength || (dot < 0f && firstLength < hookReversalLength)) {
        points = points.drop(1)
    }
    if (points.size < 5) return points

    val smoothing = s * 0.25f
    val smoothed = points.toMutableList()
    for (i in 1 until points.lastIndex) {
        val prev = points[i - 1].position
        val current = points[i]
        val next = points[i + 1].position
        val position = Offset(
            prev.x * smoothing + current.position.x * (1f - 2f * smoothing) + next.x * smoothing,
            prev.y * smoothing + current.position.y * (1f - 2f * smoothing) + next.y * smoothing
        )
        smoothed[i] = current.copy(position = position)
    }
    return smoothed
}

private fun eraseNear(strokes: List<InkStroke>, point: Offset, radius: Float): List<InkStroke> {
    val radiusSquared = radius * radius
    return strokes.filterNot { stroke ->
        stroke.points.any { strokePoint ->
            val delta = strokePoint.position - point
            delta.x * delta.x + delta.y * delta.y <= radiusSquared
        }
    }
}

/** A stroke is "in" the lasso if any one of its points falls inside the closed loop -- the same
 * granularity [eraseNear] already uses (whole-stroke selection, not partial clipping). */
private fun strokesInLasso(strokes: List<InkStroke>, lasso: List<Offset>): Set<InkStroke> {
    if (lasso.size < 3) return emptySet()
    return strokes.filterTo(mutableSetOf()) { stroke -> stroke.points.any { pointInPolygon(it.position, lasso) } }
}

private fun pointInPolygon(point: Offset, polygon: List<Offset>): Boolean {
    var inside = false
    var j = polygon.lastIndex
    for (i in polygon.indices) {
        val pi = polygon[i]
        val pj = polygon[j]
        if ((pi.y > point.y) != (pj.y > point.y) &&
            point.x < (pj.x - pi.x) * (point.y - pi.y) / (pj.y - pi.y) + pi.x
        ) {
            inside = !inside
        }
        j = i
    }
    return inside
}

/** Maps a normalized 0f..1f pressure reading to a width multiplier. A floor well above zero
 * keeps very light touches visible instead of vanishing to a hairline. */
private fun pressureWidthFactor(pressure: Float): Float =
    (0.35f + 0.75f * pressure.coerceIn(0f, 1f)).coerceIn(0.35f, 1.1f)

private fun DrawScope.drawInkStroke(stroke: InkStroke) {
    val points = stroke.points
    if (points.isEmpty()) return
    if (points.size == 1) {
        val point = points.first()
        drawCircle(stroke.color, stroke.width * pressureWidthFactor(point.pressure) / 2f, point.position)
        return
    }
    if (points.size == 2) {
        val width = stroke.width * pressureWidthFactor((points[0].pressure + points[1].pressure) / 2f)
        drawLine(stroke.color, points[0].position, points[1].position, width, cap = StrokeCap.Round)
        return
    }

    // The points are already smoothed when the stroke is finalized. Drawing round line segments
    // keeps pressure variation while avoiding a new Path allocation for every segment/frame.
    for (index in 0 until points.lastIndex) {
        val start = points[index]
        val end = points[index + 1]
        val pressure = (start.pressure + end.pressure) / 2f
        drawLine(
            color = stroke.color,
            start = start.position,
            end = end.position,
            strokeWidth = stroke.width * pressureWidthFactor(pressure),
            cap = StrokeCap.Round
        )
    }
}

private fun DrawScope.drawSelectionOutline(stroke: InkStroke) {
    if (stroke.points.isEmpty()) return
    val minX = stroke.points.minOf { it.position.x } - 8f
    val maxX = stroke.points.maxOf { it.position.x } + 8f
    val minY = stroke.points.minOf { it.position.y } - 8f
    val maxY = stroke.points.maxOf { it.position.y } + 8f
    drawRect(
        color = Color(0xFF1E5AA8),
        topLeft = Offset(minX, minY),
        size = androidx.compose.ui.geometry.Size(maxX - minX, maxY - minY),
        style = Stroke(width = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
    )
}

private fun DrawScope.drawLasso(points: List<Offset>) {
    if (points.size < 2) return
    val path = Path().apply {
        moveTo(points.first().x, points.first().y)
        for (index in 1 until points.size) {
            lineTo(points[index].x, points[index].y)
        }
    }
    drawPath(
        path = path,
        color = Color(0xFF1E5AA8),
        style = Stroke(width = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f)))
    )
}
