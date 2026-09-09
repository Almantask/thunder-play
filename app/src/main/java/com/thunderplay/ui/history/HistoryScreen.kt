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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thunderplay.library.Insights
import com.thunderplay.library.PromptInsights
import com.thunderplay.library.TermKind
import com.thunderplay.library.TermStat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(viewModel: HistoryViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    var tab by remember { mutableStateOf(HistoryTab.Listening) }

    Scaffold(topBar = { TopAppBar(title = { Text("History") }) }) { insets ->
        Column(
            Modifier
                .padding(insets)
                .fillMaxSize(),
        ) {
            SingleChoiceSegmentedButtonRow(
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                HistoryTab.entries.forEachIndexed { index, entry ->
                    SegmentedButton(
                        selected = tab == entry,
                        onClick = { tab = entry },
                        shape = SegmentedButtonDefaults.itemShape(index, HistoryTab.entries.size),
                    ) { Text(entry.label) }
                }
            }

            if (tab == HistoryTab.Prompts) {
                PromptInsightsView(viewModel.insights.collectAsStateWithLifecycle().value)
                return@Column
            }

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

/** The two questions the screen answers: what was played, and what the prompts are worth. */
private enum class HistoryTab(val label: String) {
    Listening("Listening"),
    Prompts("Prompts"),
}

/**
 * Which prompt words earn stars.
 *
 * The averages are over rated tracks only, so the guidance below has to say what is missing:
 * without ratings there is nothing to correlate against, and a term needs a few of them before
 * its average means anything.
 */
@Composable
private fun PromptInsightsView(insights: Insights) {
    var kind by remember { mutableStateOf(TermKind.Word) }

    Text(
        summaryOf(insights),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )

    if (insights.describedTracks == 0) {
        Guidance(
            "No prompts read yet",
            "Refresh the library and the generator's prompts are read out of each WAV header.",
        )
        return
    }

    SingleChoiceSegmentedButtonRow(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        TermKind.entries.forEachIndexed { index, entry ->
            SegmentedButton(
                selected = kind == entry,
                onClick = { kind = entry },
                shape = SegmentedButtonDefaults.itemShape(index, TermKind.entries.size),
            ) { Text(entry.label + "s") }
        }
    }

    val best = insights.best(TERM_LIMIT, kind)
    val worst = insights.worst(TERM_LIMIT, kind)
    if (best.isEmpty()) {
        Guidance(
            "Not enough ratings yet",
            "A term is ranked once ${PromptInsights.MIN_RATED} of the tracks using it are rated.",
        )
        return
    }

    LazyColumn(Modifier.fillMaxSize()) {
        item { SectionHeader("Rates highest") }
        items(best, key = { "best-" + it.kind + it.term }) { stat ->
            TermRow(stat, insights.overallAverage)
        }

        // With few terms the two lists would be the same rows in reverse, which reads as a bug.
        val overlapping = best.map { it.term }.intersect(worst.map { it.term }.toSet())
        if (worst.isNotEmpty() && overlapping.size < worst.size) {
            item { HorizontalDivider() }
            item { SectionHeader("Rates lowest") }
            items(
                worst.filterNot { it.term in overlapping },
                key = { "worst-" + it.kind + it.term },
            ) { stat ->
                TermRow(stat, insights.overallAverage)
            }
        }
    }
}

@Composable
private fun TermRow(stat: TermStat, overall: Double?) {
    val delta = stat.deltaFrom(overall)
    ListItem(
        headlineContent = {
            Text(
                stat.term.replaceFirstChar(Char::uppercase),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            val record = if (stat.abJudged > 0) {
                " · A/B ${stat.abWins}-${stat.abLosses}"
            } else {
                ""
            }
            Text("${stat.ratedTracks} rated of ${stat.tracks}$record")
        },
        trailingContent = {
            Text(
                buildString {
                    append(format1(stat.averageRating))
                    append("★")
                    if (delta != null && kotlin.math.abs(delta) >= 0.05) {
                        append(if (delta > 0) "  +" else "  ")
                        append(format1(delta))
                    }
                },
                style = MaterialTheme.typography.labelLarge,
                color = when {
                    delta == null -> MaterialTheme.colorScheme.onSurfaceVariant
                    delta > 0 -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        },
    )
}

@Composable
private fun Guidance(title: String, body: String) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(body, style = MaterialTheme.typography.bodyMedium)
    }
}

private const val TERM_LIMIT = 12

private fun summaryOf(insights: Insights): String = when {
    insights.describedTracks == 0 -> "No prompts indexed yet"
    insights.ratedTracks == 0 -> "${insights.describedTracks} tracks described, none rated yet"
    else -> "${insights.describedTracks} described · ${insights.ratedTracks} rated · " +
        "library average ${format1(insights.overallAverage)}★"
}

private fun format1(value: Double?): String =
    if (value == null) "-" else String.format(Locale.UK, "%.1f", value)

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
