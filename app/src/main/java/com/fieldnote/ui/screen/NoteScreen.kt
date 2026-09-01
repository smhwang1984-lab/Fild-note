package com.fieldnote.ui.screen

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
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
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

private enum class NoteTool {
    Pen,
    Eraser,
    Move
}

private data class InkStroke(
    val color: Color,
    val width: Float,
    val points: List<Offset>
)

@Composable
fun NoteScreen(viewModel: MainViewModel, tabletMode: Boolean) {
    val pageNumbers = remember { mutableStateMapOf(1 to true) }
    val pageStrokes = remember { mutableStateMapOf(1 to emptyList<InkStroke>()) }
    var currentPage by remember { mutableIntStateOf(1) }
    var undoStack by remember { mutableStateOf(emptyList<List<InkStroke>>()) }
    var redoStack by remember { mutableStateOf(emptyList<List<InkStroke>>()) }
    var liveStroke by remember { mutableStateOf<InkStroke?>(null) }
    var activeTool by remember { mutableStateOf(NoteTool.Pen) }
    var selectedColor by remember { mutableStateOf(Color(0xFF171717)) }
    var strokeWidth by remember { mutableFloatStateOf(6f) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var penOnlyMode by remember { mutableStateOf(true) }

    fun strokes(): List<InkStroke> = pageStrokes[currentPage].orEmpty()

    fun setStrokes(value: List<InkStroke>) {
        pageStrokes[currentPage] = value
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
        }
    }

    fun redo() {
        if (redoStack.isNotEmpty()) {
            undoStack = undoStack + listOf(strokes())
            setStrokes(redoStack.last())
            redoStack = redoStack.dropLast(1)
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
        pan = Offset.Zero
        zoom = 1f
    }

    fun selectPage(page: Int) {
        currentPage = page
        undoStack = emptyList()
        redoStack = emptyList()
        liveStroke = null
    }

    ScreenFrame(
        title = "필드 노트",
        subtitle = "S펜 중심 필기 작업 공간"
    ) {
        if (tabletMode) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxSize()) {
                NoteToolPanel(
                    activeTool = activeTool,
                    onToolSelected = { activeTool = it },
                    selectedColor = selectedColor,
                    onColorSelected = { selectedColor = it },
                    strokeWidth = strokeWidth,
                    onStrokeWidthChange = { strokeWidth = it },
                    zoom = zoom,
                    penOnlyMode = penOnlyMode,
                    onPenOnlyModeChange = { penOnlyMode = it },
                    pageNumbers = pageNumbers.keys.sorted(),
                    currentPage = currentPage,
                    onPageSelected = ::selectPage,
                    onAddPage = ::addPage,
                    canUndo = undoStack.isNotEmpty(),
                    canRedo = redoStack.isNotEmpty(),
                    onZoomIn = { zoom = (zoom + 0.25f).coerceAtMost(4f) },
                    onZoomOut = { zoom = (zoom - 0.25f).coerceAtLeast(0.5f) },
                    onUndo = ::undo,
                    onRedo = ::redo,
                    modifier = Modifier.width(260.dp).fillMaxHeight()
                )
                NoteCanvas(
                    strokes = strokes(),
                    liveStroke = liveStroke,
                    todoItems = viewModel.todos,
                    todoPlacements = viewModel.noteTodoPlacements,
                    activeTool = activeTool,
                    selectedColor = selectedColor,
                    strokeWidth = strokeWidth,
                    zoom = zoom,
                    pan = pan,
                    penOnlyMode = penOnlyMode,
                    onZoomChange = { zoom = it },
                    onPanChange = { pan = it },
                    onLiveStrokeChange = { liveStroke = it },
                    onStrokeCommitted = { before, stroke ->
                        commitSnapshot(before)
                        setStrokes(before + stroke)
                        liveStroke = null
                    },
                    onEraseCommitted = { before, after ->
                        commitSnapshot(before)
                        setStrokes(after)
                    },
                    onTodoMoved = viewModel::moveNoteTodo,
                    onTodoResized = viewModel::resizeNoteTodo,
                    onTodoRemoved = viewModel::removeNoteTodo,
                    onTodoFixedChanged = viewModel::toggleNoteTodoFixed,
                    onTodoCheckedChanged = viewModel::toggleTodo,
                    modifier = Modifier.weight(1f).fillMaxHeight()
                )
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
                NoteToolPanel(
                    activeTool = activeTool,
                    onToolSelected = { activeTool = it },
                    selectedColor = selectedColor,
                    onColorSelected = { selectedColor = it },
                    strokeWidth = strokeWidth,
                    onStrokeWidthChange = { strokeWidth = it },
                    zoom = zoom,
                    penOnlyMode = penOnlyMode,
                    onPenOnlyModeChange = { penOnlyMode = it },
                    pageNumbers = pageNumbers.keys.sorted(),
                    currentPage = currentPage,
                    onPageSelected = ::selectPage,
                    onAddPage = ::addPage,
                    canUndo = undoStack.isNotEmpty(),
                    canRedo = redoStack.isNotEmpty(),
                    onZoomIn = { zoom = (zoom + 0.25f).coerceAtMost(4f) },
                    onZoomOut = { zoom = (zoom - 0.25f).coerceAtLeast(0.5f) },
                    onUndo = ::undo,
                    onRedo = ::redo,
                    modifier = Modifier.fillMaxWidth()
                )
                NoteCanvas(
                    strokes = strokes(),
                    liveStroke = liveStroke,
                    todoItems = viewModel.todos,
                    todoPlacements = viewModel.noteTodoPlacements,
                    activeTool = activeTool,
                    selectedColor = selectedColor,
                    strokeWidth = strokeWidth,
                    zoom = zoom,
                    pan = pan,
                    penOnlyMode = penOnlyMode,
                    onZoomChange = { zoom = it },
                    onPanChange = { pan = it },
                    onLiveStrokeChange = { liveStroke = it },
                    onStrokeCommitted = { before, stroke ->
                        commitSnapshot(before)
                        setStrokes(before + stroke)
                        liveStroke = null
                    },
                    onEraseCommitted = { before, after ->
                        commitSnapshot(before)
                        setStrokes(after)
                    },
                    onTodoMoved = viewModel::moveNoteTodo,
                    onTodoResized = viewModel::resizeNoteTodo,
                    onTodoRemoved = viewModel::removeNoteTodo,
                    onTodoFixedChanged = viewModel::toggleNoteTodoFixed,
                    onTodoCheckedChanged = viewModel::toggleTodo,
                    modifier = Modifier.fillMaxWidth().height(560.dp)
                )
            }
        }
    }
}

@Composable
private fun NoteToolPanel(
    activeTool: NoteTool,
    onToolSelected: (NoteTool) -> Unit,
    selectedColor: Color,
    onColorSelected: (Color) -> Unit,
    strokeWidth: Float,
    onStrokeWidthChange: (Float) -> Unit,
    zoom: Float,
    penOnlyMode: Boolean,
    onPenOnlyModeChange: (Boolean) -> Unit,
    pageNumbers: List<Int>,
    currentPage: Int,
    onPageSelected: (Int) -> Unit,
    onAddPage: () -> Unit,
    canUndo: Boolean,
    canRedo: Boolean,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = listOf(Color(0xFF171717), Color(0xFF1E5AA8), Color(0xFFC0392B), Color(0xFF2E7D32))

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(12.dp)) {
            Text(text = "페이지", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pageNumbers.forEach { page ->
                    AssistChip(onClick = { onPageSelected(page) }, label = { Text(if (page == currentPage) "${page}쪽*" else "${page}쪽") })
                }
                Button(onClick = onAddPage) { Text("추가") }
            }

            Text(text = "도구", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ToolChip("펜", activeTool == NoteTool.Pen) { onToolSelected(NoteTool.Pen) }
                ToolChip("지우개", activeTool == NoteTool.Eraser) { onToolSelected(NoteTool.Eraser) }
                ToolChip("이동", activeTool == NoteTool.Move) { onToolSelected(NoteTool.Move) }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text(text = "S펜 모드", style = MaterialTheme.typography.bodyMedium)
                Switch(checked = penOnlyMode, onCheckedChange = onPenOnlyModeChange)
            }
            Text(text = if (penOnlyMode) "필기/지우개는 S펜만 인식" else "손가락과 S펜 모두 인식", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                colors.forEach { color ->
                    ColorSwatch(color = color, selected = selectedColor == color) {
                        onColorSelected(color)
                        onToolSelected(NoteTool.Pen)
                    }
                }
            }
            Text(text = "굵기 ${strokeWidth.toInt()}", style = MaterialTheme.typography.bodySmall)
            Slider(value = strokeWidth, onValueChange = onStrokeWidthChange, valueRange = 2f..22f, steps = 9)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onUndo, enabled = canUndo) { Text("되돌리기") }
                Button(onClick = onRedo, enabled = canRedo) { Text("다시") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onZoomOut) { Text("-") }
                AssistChip(onClick = {}, label = { Text("${(zoom * 100).toInt()}%") })
                Button(onClick = onZoomIn) { Text("+") }
            }
        }
    }
}

@Composable
private fun ToolChip(label: String, selected: Boolean, onClick: () -> Unit) {
    AssistChip(onClick = onClick, label = { Text(if (selected) "$label*" else label) })
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
    todoItems: List<TodoItem>,
    todoPlacements: List<NoteTodoPlacement>,
    activeTool: NoteTool,
    selectedColor: Color,
    strokeWidth: Float,
    zoom: Float,
    pan: Offset,
    penOnlyMode: Boolean,
    onZoomChange: (Float) -> Unit,
    onPanChange: (Offset) -> Unit,
    onLiveStrokeChange: (InkStroke?) -> Unit,
    onStrokeCommitted: (before: List<InkStroke>, stroke: InkStroke) -> Unit,
    onEraseCommitted: (before: List<InkStroke>, after: List<InkStroke>) -> Unit,
    onTodoMoved: (todoId: Long, dx: Float, dy: Float) -> Unit,
    onTodoResized: (todoId: Long, widthDeltaDp: Float, heightDeltaDp: Float) -> Unit,
    onTodoRemoved: (todoId: Long) -> Unit,
    onTodoFixedChanged: (todoId: Long) -> Unit,
    onTodoCheckedChanged: (todoId: Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val paperColor = Color(0xFFFFFCF6)
    val lineColor = Color(0xFFE5DDCD)

    fun toNotePoint(screenPoint: Offset): Offset = (screenPoint - pan) / zoom

    Box(
        modifier = modifier
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
            .background(paperColor, RoundedCornerShape(8.dp))
            .pointerInput(activeTool, selectedColor, strokeWidth, zoom, pan, penOnlyMode, strokes.size) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val before = strokes.toList()

                    if (activeTool == NoteTool.Move) {
                        var nextPan = pan
                        var nextZoom = zoom
                        while (true) {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.isEmpty()) break
                            if (pressed.size >= 2) {
                                val zoomDelta = calculateZoomDelta(pressed)
                                nextZoom = (nextZoom * zoomDelta).coerceIn(0.5f, 4f)
                                nextPan += calculatePanDelta(pressed)
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

                    var nextPan = pan
                    var nextZoom = zoom
                    val points = mutableListOf<Offset>()
                    var erased = before
                    var transformed = false

                    if (!penOnlyMode || down.type.isStylusInput()) {
                        points.add(toNotePoint(down.position))
                    }

                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break

                        if (pressed.size >= 2) {
                            transformed = true
                            onLiveStrokeChange(null)
                            val zoomDelta = calculateZoomDelta(pressed)
                            nextZoom = (nextZoom * zoomDelta).coerceIn(0.5f, 4f)
                            nextPan += calculatePanDelta(pressed)
                            onZoomChange(nextZoom)
                            onPanChange(nextPan)
                            continue
                        }

                        if (transformed) continue
                        val change = pressed.firstOrNull { it.id == down.id } ?: pressed.first()
                        if (penOnlyMode && !change.type.isStylusInput()) continue
                        val notePoint = (change.position - nextPan) / nextZoom
                        val drawingTool = if (change.type == PointerType.Eraser) NoteTool.Eraser else activeTool

                        if (drawingTool == NoteTool.Pen) {
                            points.add(notePoint)
                            onLiveStrokeChange(InkStroke(selectedColor, strokeWidth, stabilizePoints(points)))
                        } else {
                            erased = eraseNear(erased, notePoint, max(24f, strokeWidth * 2.5f) / nextZoom)
                        }
                    }

                    if (!transformed && points.isNotEmpty()) {
                        if (activeTool == NoteTool.Pen) {
                            onStrokeCommitted(before, InkStroke(selectedColor, strokeWidth, stabilizePoints(points)))
                        } else if (before != erased) {
                            onEraseCommitted(before, erased)
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
            strokes.forEach { drawInkStroke(it) }
            liveStroke?.let { drawInkStroke(it) }
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
            .width(placement.widthDp.dp)
            .height(placement.heightDp.dp)
            .pointerInput(placement.fixed, zoom) {
                detectDragGestures { _, dragAmount ->
                    if (!placement.fixed) onMoved(placement.todoId, dragAmount.x / zoom, dragAmount.y / zoom)
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
                        detectDragGestures { _, dragAmount ->
                            if (!placement.fixed) {
                                with(density) {
                                    onResized(todo.id, dragAmount.x.toDp().value, dragAmount.y.toDp().value)
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

private fun calculatePanDelta(changes: List<PointerInputChange>): Offset {
    val current = changes.map { it.position }.averageOffset()
    val previous = changes.map { it.previousPosition }.averageOffset()
    return current - previous
}

private fun calculateZoomDelta(changes: List<PointerInputChange>): Float {
    val currentCentroid = changes.map { it.position }.averageOffset()
    val previousCentroid = changes.map { it.previousPosition }.averageOffset()
    val currentDistance = changes.map { distance(it.position, currentCentroid) }.average().toFloat()
    val previousDistance = changes.map { distance(it.previousPosition, previousCentroid) }.average().toFloat()
    if (previousDistance <= 0.5f || currentDistance <= 0.5f) return 1f
    return (currentDistance / previousDistance).coerceIn(0.85f, 1.18f)
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

private fun stabilizePoints(raw: List<Offset>): List<Offset> {
    if (raw.size <= 2) return raw
    val filtered = raw.fold(mutableListOf<Offset>()) { acc, point ->
        if (acc.isEmpty() || distance(acc.last(), point) >= 1.4) acc.add(point)
        acc
    }
    if (filtered.size <= 3) return filtered

    val stable = filtered.toMutableList()
    val last = stable.last()
    val prev = stable[stable.lastIndex - 1]
    val beforePrev = stable[stable.lastIndex - 2]
    val v1 = prev - beforePrev
    val v2 = last - prev
    val lastLength = distance(prev, last)
    val dot = v1.x * v2.x + v1.y * v2.y
    if (lastLength < 8.0 || (dot < 0f && lastLength < 18.0)) {
        stable.removeAt(stable.lastIndex)
    }
    return stable
}

private fun eraseNear(strokes: List<InkStroke>, point: Offset, radius: Float): List<InkStroke> {
    val radiusSquared = radius * radius
    return strokes.filterNot { stroke ->
        stroke.points.any { strokePoint ->
            val delta = strokePoint - point
            delta.x * delta.x + delta.y * delta.y <= radiusSquared
        }
    }
}

private fun DrawScope.drawInkStroke(stroke: InkStroke) {
    if (stroke.points.isEmpty()) return
    if (stroke.points.size == 1) {
        drawCircle(stroke.color, stroke.width / 2f, stroke.points.first())
        return
    }

    val path = Path().apply {
        moveTo(stroke.points.first().x, stroke.points.first().y)
        for (index in 1 until stroke.points.lastIndex) {
            val point = stroke.points[index]
            val next = stroke.points[index + 1]
            val mid = Offset((point.x + next.x) / 2f, (point.y + next.y) / 2f)
            quadraticBezierTo(point.x, point.y, mid.x, mid.y)
        }
        val last = stroke.points.last()
        lineTo(last.x, last.y)
    }

    drawPath(path = path, color = stroke.color, style = Stroke(width = stroke.width))
}
