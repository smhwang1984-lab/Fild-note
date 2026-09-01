package com.fieldnote.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.fieldnote.MainViewModel
import com.fieldnote.NoteTodoPlacement
import com.fieldnote.TodoItem
import com.fieldnote.ui.navigation.ScreenFrame

@Composable
fun TodoScreen(viewModel: MainViewModel, tabletMode: Boolean) {
    var title by remember { mutableStateOf("") }
    var dueDayText by remember { mutableStateOf("") }

    ScreenFrame(
        title = "할 일",
        subtitle = if (tabletMode) "태블릿 할 일 작업 공간" else "할 일 작업 공간"
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Card(
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth(if (tabletMode) 0.75f else 1f)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(12.dp)
                ) {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text("할 일") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = dueDayText,
                        onValueChange = { value -> dueDayText = value.filter { it.isDigit() }.take(2) },
                        label = { Text("일") },
                        singleLine = true,
                        modifier = Modifier.width(88.dp)
                    )
                    Button(
                        onClick = {
                            viewModel.addTodo(title, dueDayText.toIntOrNull())
                            title = ""
                            dueDayText = ""
                        }
                    ) { Text("추가") }
                }
            }

            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(items = viewModel.todos, key = { it.id }) { item ->
                    val placement = viewModel.noteTodoPlacements.firstOrNull { it.todoId == item.id }
                    TodoRow(
                        item = item,
                        placement = placement,
                        onCheckedChanged = { viewModel.toggleTodo(item.id) },
                        onDueDayChanged = { viewModel.updateTodoDueDay(item.id, it) },
                        onAddToNote = { viewModel.addTodoToNote(item.id) },
                        onRemoveFromNote = { viewModel.removeNoteTodo(item.id) },
                        modifier = Modifier.fillMaxWidth(if (tabletMode) 0.75f else 1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun TodoRow(
    item: TodoItem,
    placement: NoteTodoPlacement?,
    onCheckedChanged: () -> Unit,
    onDueDayChanged: (Int?) -> Unit,
    onAddToNote: () -> Unit,
    onRemoveFromNote: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = modifier
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(12.dp)
        ) {
            Checkbox(checked = item.completed, onCheckedChange = { onCheckedChanged() })
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium,
                    textDecoration = if (item.completed) TextDecoration.LineThrough else TextDecoration.None
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(onClick = { onDueDayChanged(null) }, label = { Text("날짜 없음") })
                    AssistChip(onClick = { onDueDayChanged(2) }, label = { Text("9월 2일") })
                    AssistChip(onClick = { onDueDayChanged(15) }, label = { Text("9월 15일") })
                    if (item.dueDay != null) AssistChip(onClick = {}, label = { Text("9월 ${item.dueDay}일") })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onAddToNote, enabled = placement == null) { Text("노트에 추가") }
                    OutlinedButton(onClick = onRemoveFromNote, enabled = placement != null && !placement.fixed) { Text("수동 제거") }
                    Text(
                        text = when {
                            placement == null -> "노트 미배치"
                            placement.fixed -> "노트 고정됨"
                            else -> "노트 이동 가능"
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}
