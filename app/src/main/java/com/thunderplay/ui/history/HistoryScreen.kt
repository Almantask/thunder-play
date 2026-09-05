package com.thunderplay.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(viewModel: HistoryViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(topBar = { TopAppBar(title = { Text("History") }) }) { insets ->
        Column(
            Modifier
                .padding(insets)
                .fillMaxSize(),
        ) {
            SingleChoiceSegmentedButtonRow(
                Modifier
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                HistoryWindow.entries.forEachIndexed { index, window ->
                    SegmentedButton(
                        selected = state.window == window,
                        onClick = { viewModel.setWindow(window) },
                        shape = SegmentedButtonDefaults.itemShape(index, HistoryWindow.entries.size),
                    ) { Text(window.label) }
                }
            }

            Text(
                state.summary,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )

            if (state.top.isEmpty() && state.recent.isEmpty()) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Nothing played yet", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "A play is recorded after 30 seconds, or half a short track.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                return@Column
            }

            LazyColumn(Modifier.fillMaxSize()) {
                if (state.top.isNotEmpty()) {
                    item { SectionHeader("Most played") }
                    items(state.top, key = { "top-" + it.trackId }) { row ->
                        ListItem(
                            headlineContent = {
                                Text(row.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            },
                            supportingContent = { Text(row.category) },
                            trailingContent = {
                                Text(
                                    "${row.plays}x · ${formatDuration(row.totalMs)}",
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            },
                        )
                    }
                    item { HorizontalDivider() }
                }

                if (state.recent.isNotEmpty()) {
                    item { SectionHeader("Recent") }
                    items(state.recent, key = { it.id }) { row ->
                        ListItem(
                            headlineContent = {
                                Text(row.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            },
                            supportingContent = { Text(formatWhen(row.startedAt)) },
                            trailingContent = {
                                Text(
                                    formatDuration(row.msPlayed),
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
    )
}

private fun formatDuration(ms: Long): String {
    val minutes = TimeUnit.MILLISECONDS.toMinutes(ms)
    if (minutes < 60) return "${minutes}m"
    val hours = minutes / 60
    return "${hours}h ${minutes % 60}m"
}

private fun formatWhen(epochMs: Long): String {
    val elapsed = System.currentTimeMillis() - epochMs
    return when {
        elapsed < TimeUnit.MINUTES.toMillis(1) -> "just now"
        elapsed < TimeUnit.HOURS.toMillis(1) ->
            "${TimeUnit.MILLISECONDS.toMinutes(elapsed)} min ago"
        elapsed < TimeUnit.DAYS.toMillis(1) ->
            "${TimeUnit.MILLISECONDS.toHours(elapsed)} h ago"
        else -> SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(epochMs))
    }
}
