package com.fieldnote.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fieldnote.TodoItem
import com.fieldnote.ui.navigation.ScreenFrame

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CalendarScreen(todos: List<TodoItem>, tabletMode: Boolean) {
    val todosByDay = todos.filter { it.dueDay != null }.groupBy { it.dueDay }

    ScreenFrame(
        title = "달력",
        subtitle = "월간 일정"
    ) {
        Card(
            shape = RoundedCornerShape(8.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(16.dp)) {
                Text(text = "2026년 9월", style = MaterialTheme.typography.titleLarge)
                FlowRow(
                    maxItemsInEachRow = 7,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    repeat(30) { index ->
                        val day = index + 1
                        val dayTodos = todosByDay[day].orEmpty()
                        Box(
                            contentAlignment = Alignment.TopStart,
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(if (tabletMode) 1.25f else 1f)
                                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
                                .padding(8.dp)
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(text = day.toString(), style = MaterialTheme.typography.bodyMedium)
                                dayTodos.take(2).forEach { todo ->
                                    Text(
                                        text = todo.title,
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
