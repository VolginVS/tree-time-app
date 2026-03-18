package com.treetime.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.treetime.app.focusblock.FocusBlockRepository
import com.treetime.app.focusblock.FocusSessionState
import com.treetime.app.ui.theme.TreeTimeTheme
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.ceil
import kotlin.math.sqrt
import androidx.compose.foundation.shape.CircleShape
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TreeTimeTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val vm: MainViewModel = viewModel(
                        factory = MainViewModel.factory(FocusBlockRepository(this)),
                    )
                    MainScreen(
                        state = vm.uiState.collectAsState().value,
                        onStart = vm::startFocus,
                        onStartWorkday = vm::startWorkday,
                        onStop = vm::stopFocus,
                        onStartPause = vm::startPause,
                        onStopPause = vm::stopPause,
                        onOpenAccessibilitySettings = ::openAccessibilitySettings,
                        onMonthChange = vm::setMonth,
                    )
                }
            }
        }
    }

    private fun openAccessibilitySettings() {
        com.treetime.app.ui.platform.PlatformIntents.openAccessibilitySettings(this)
    }
}

data class MainUiState(
    val now: LocalDate = LocalDate.now(),
    val selectedMonth: YearMonth = YearMonth.now(),
    val minutesByDay: Map<Int, Int> = emptyMap(),
    val session: FocusSessionState = FocusSessionState(),
    val blockEnabled: Boolean = true,
)

class MainViewModel(
    private val repo: FocusBlockRepository,
) : ViewModel() {
    private val _selectedMonth = repo.selectedMonth
    val uiState = repo.uiState(_selectedMonth)

    fun startFocus(minutes: Int) {
        repo.startFocus(minutes)
    }

    fun startWorkday() {
        repo.startWorkdayFocus()
    }

    fun stopFocus() {
        repo.stopFocus()
    }

    fun startPause() {
        repo.startPause()
    }

    fun stopPause() {
        repo.stopPause()
    }

    fun setMonth(month: YearMonth) {
        repo.setSelectedMonth(month)
    }

    companion object {
        fun factory(repo: FocusBlockRepository) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = MainViewModel(repo) as T
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(
    state: MainUiState,
    onStart: (Int) -> Unit,
    onStartWorkday: () -> Unit,
    onStop: () -> Unit,
    onStartPause: () -> Unit,
    onStopPause: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onMonthChange: (YearMonth) -> Unit,
) {
    // When focus session ends, return to the "start timer" view and refresh grid for today.
    var wasFocusActive by remember { mutableIntStateOf(0) } // 0/1 to keep saveable-free
    val isFocusActiveNow = if (state.session.focus != null) 1 else 0
    LaunchedEffect(isFocusActiveNow) {
        if (wasFocusActive == 1 && isFocusActiveNow == 0) {
            onMonthChange(YearMonth.now())
        }
        wasFocusActive = isFocusActiveNow
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("TreeTime") },
            actions = {
                TextButton(
                    onClick = onOpenAccessibilitySettings,
                    modifier = Modifier.padding(end = 8.dp),
                ) {
                    Text(
                        text = "block",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FocusButtonCard(
                session = state.session,
                blockEnabled = state.blockEnabled,
                onStart = onStart,
                onStartWorkday = onStartWorkday,
                onStop = onStop,
                onStartPause = onStartPause,
                onStopPause = onStopPause,
            )

            MonthHeader(
                month = state.selectedMonth,
                onPrev = { onMonthChange(state.selectedMonth.minusMonths(1)) },
                onNext = { onMonthChange(state.selectedMonth.plusMonths(1)) },
            )

            Spacer(modifier = Modifier.height(16.dp))

            MonthGrid(
                month = state.selectedMonth,
                minutesByDay = state.minutesByDay,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
        }
    }
}

@Composable
private fun FocusButtonCard(
    session: FocusSessionState,
    blockEnabled: Boolean,
    onStart: (Int) -> Unit,
    onStartWorkday: () -> Unit,
    onStop: () -> Unit,
    onStartPause: () -> Unit,
    onStopPause: () -> Unit,
) {
    var minutes by rememberSaveable { mutableIntStateOf(25) }
    var showStopConfirm by remember { mutableStateOf(false) }

    val focus = session.focus
    val pause = session.pause
    val isFocusActiveRaw = focus != null
    val isPauseRunning = pause?.isRunning == true
    val isFocusPaused = focus?.isPaused == true
    val focusEndEpochMillis = focus?.endEpochMillis
    val pauseEndEpochMillis = pause?.endEpochMillis

    var focusRemainingSeconds by remember { mutableIntStateOf(0) }
    var pauseRemainingSeconds by remember { mutableIntStateOf(0) }

    LaunchedEffect(focusEndEpochMillis, focus?.remainingMs, isFocusPaused) {
        if (focus == null) {
            focusRemainingSeconds = 0
            return@LaunchedEffect
        }
        val fixed = focus?.remainingMs
        if (fixed != null) {
            focusRemainingSeconds = (fixed / 1_000L).toInt().coerceAtLeast(0)
            return@LaunchedEffect
        }
        val end = focusEndEpochMillis ?: run {
            focusRemainingSeconds = 0
            return@LaunchedEffect
        }
        while (true) {
            val secs = ((end - System.currentTimeMillis()) / 1000L).toInt().coerceAtLeast(0)
            focusRemainingSeconds = secs
            if (secs <= 0) break
            delay(1_000L)
        }
    }

    LaunchedEffect(pauseEndEpochMillis, pause?.remainingMs, isPauseRunning) {
        if (pause == null) {
            pauseRemainingSeconds = 0
            return@LaunchedEffect
        }
        val fixed = pause?.remainingMs
        if (fixed != null) {
            pauseRemainingSeconds = (fixed / 1_000L).toInt().coerceAtLeast(0)
            return@LaunchedEffect
        }
        val end = pauseEndEpochMillis ?: run {
            pauseRemainingSeconds = 0
            return@LaunchedEffect
        }
        while (true) {
            val secs = ((end - System.currentTimeMillis()) / 1000L).toInt().coerceAtLeast(0)
            pauseRemainingSeconds = secs
            if (secs <= 0) break
            delay(1_000L)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val isFocusActive = isFocusActiveRaw && focusRemainingSeconds > 0

        if (isFocusActive) {
            Text(
                text = "Фокус: ${formatHhMmSs(focusRemainingSeconds)}${if (isFocusPaused) " (пауза)" else ""}",
                fontWeight = FontWeight.SemiBold,
            )
        } else {
            Text(
                text = "Выбери длительность (1–60 мин)",
                fontWeight = FontWeight.SemiBold,
            )
        }

        if (pause != null) {
            val pauseLabel = if (pause.isRunning) "Пауза блокировки" else "Пауза (остановлена)"
            Text(
                text = "$pauseLabel: ${formatHhMmSs(pauseRemainingSeconds)}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (isFocusActiveRaw) {
            Text(
                text = "Пауза банк: ${formatHhMmSs((session.pauseBudgetMs / 1_000L).toInt())}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (!isFocusActive) {
            Slider(
                value = minutes.toFloat(),
                onValueChange = { minutes = it.toInt().coerceIn(1, 60) },
                valueRange = 1f..60f,
                steps = 58,
            )
            Text(text = "$minutes минут")
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = {
                    if (isFocusActive) {
                        showStopConfirm = true
                    } else {
                        onStart(minutes)
                    }
                },
                modifier = Modifier.weight(1f),
            ) {
                Text(if (isFocusActive) "Прервать" else "Старт")
            }

            if (!isFocusActive) {
                Button(
                    onClick = onStartWorkday,
                    modifier = Modifier.weight(1f),
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFD32F2F),
                        contentColor = Color.White,
                    ),
                ) {
                    Text("Старт работа")
                }
            }

            if (isFocusActive && blockEnabled) {
                if (isPauseRunning) {
                    OutlinedButton(
                        onClick = onStopPause,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Стоп пауза")
                    }
                } else {
                    OutlinedButton(
                        onClick = onStartPause,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Пауза")
                    }
                }
            }
        }

        if (showStopConfirm) {
            AlertDialog(
                onDismissRequest = { showStopConfirm = false },
                title = { Text("Подтверждение") },
                text = { Text("Уверены что хотите прервать?") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showStopConfirm = false
                            onStop()
                        },
                    ) {
                        Text("Да")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showStopConfirm = false }) {
                        Text("Нет")
                    }
                },
            )
        }
    }
}

@Composable
private fun MonthHeader(
    month: YearMonth,
    onPrev: () -> Unit,
    onNext: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        OutlinedButton(onClick = onPrev) { Text("←") }
        Text(text = "${month.month.name.lowercase().replaceFirstChar { it.titlecase() }} ${month.year}")
        OutlinedButton(onClick = onNext) { Text("→") }
    }
}

@Composable
private fun MonthGrid(
    month: YearMonth,
    minutesByDay: Map<Int, Int>,
    modifier: Modifier = Modifier,
) {
    val daysInMonth = month.lengthOfMonth()
    val columns = remember(daysInMonth) { maxOf(4, ceil(sqrt(daysInMonth.toDouble())).toInt()) }
    val days = remember(month) { (1..daysInMonth).toList() }

    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surface),
    ) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(days) { day ->
                DayCell(day = day, minutes = minutesByDay[day] ?: 0)
            }
        }
    }
}

@Composable
private fun DayCell(day: Int, minutes: Int) {
    Column(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = day.toString(), fontWeight = FontWeight.SemiBold)
        CoinsRow(minutes = minutes)
        Text(text = "${minutes}м", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CoinsRow(minutes: Int) {
    val coins = remember(minutes) { coinsForMinutes(minutes) }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        coins.forEach { coin ->
            CoinDot(coin = coin)
        }
        if (coins.isEmpty()) {
            Spacer(modifier = Modifier.height(0.dp))
        }
    }
}

private enum class Coin { Bronze, Silver, Gold }

private fun coinsForMinutes(minutes: Int): List<Coin> {
    return when {
        minutes >= 720 -> listOf(Coin.Gold, Coin.Gold, Coin.Gold)
        minutes >= 600 -> listOf(Coin.Gold, Coin.Gold)
        minutes >= 480 -> listOf(Coin.Gold)
        minutes >= 360 -> listOf(Coin.Silver, Coin.Silver, Coin.Silver)
        minutes >= 300 -> listOf(Coin.Silver, Coin.Silver)
        minutes >= 240 -> listOf(Coin.Silver)
        minutes >= 180 -> listOf(Coin.Bronze, Coin.Bronze, Coin.Bronze)
        minutes >= 120 -> listOf(Coin.Bronze, Coin.Bronze)
        minutes >= 60 -> listOf(Coin.Bronze)
        else -> emptyList()
    }
}

@Composable
private fun CoinDot(coin: Coin) {
    val color = when (coin) {
        Coin.Bronze -> Color(0xFFB87333)
        Coin.Silver -> Color(0xFFBFC3C7)
        Coin.Gold -> Color(0xFFFFD54F)
    }
    Box(
        modifier = Modifier
            .size(14.dp)
            .clip(CircleShape)
            .background(color),
    )
}

private fun formatHhMmSs(totalSeconds: Int): String {
    val s = totalSeconds.coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

