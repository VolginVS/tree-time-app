package com.treetime.app.focusblock

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.treetime.app.MainUiState
import com.treetime.app.ui.platform.PlatformIntents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.YearMonth

private val Context.dataStore by preferencesDataStore(name = "treetime")

data class FocusTimerState(
    val endEpochMillis: Long? = null,
    val remainingMs: Long? = null,
    val isPaused: Boolean,
)

data class PauseTimerState(
    val endEpochMillis: Long? = null,
    val remainingMs: Long? = null,
    val isRunning: Boolean,
)

data class FocusSessionState(
    val focus: FocusTimerState? = null,
    val pause: PauseTimerState? = null,
    val pauseBudgetMs: Long = 0L,
)

data class MonthKey(val year: Int, val month: Int)
data class YearMonthDay(val year: Int, val month: Int, val day: Int)

class FocusBlockRepository(private val appContext: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val selectedMonthFlow = MutableStateFlow(YearMonth.now())
    val selectedMonth: Flow<YearMonth> = selectedMonthFlow

    fun setSelectedMonth(month: YearMonth) {
        selectedMonthFlow.value = month
    }

    private fun keyForDay(date: LocalDate) = intPreferencesKey("minutes_${date}")

    private val focusEndKey = longPreferencesKey("focus_end_epoch_ms")
    private val focusActiveKey = longPreferencesKey("focus_active_1") // 1 = active, 0 = inactive (stored as long)
    private val focusPausedKey = longPreferencesKey("focus_paused_1") // 1 = paused, 0 = running (stored as long)
    private val focusRemainingMsKey = longPreferencesKey("focus_remaining_ms")
    private val focusStartEpochMsKey = longPreferencesKey("focus_start_epoch_ms")
    private val focusTotalDurationMsKey = longPreferencesKey("focus_total_duration_ms")
    private val focusProfileKey = longPreferencesKey("focus_profile_1") // 0 = normal, 1 = workday
    private val plannedMinutesKey = intPreferencesKey("focus_planned_minutes")

    private val pauseEndKey = longPreferencesKey("pause_end_epoch_ms")
    private val pauseActiveKey = longPreferencesKey("pause_active_1") // 1 = active, 0 = inactive (stored as long)
    private val pauseRemainingMsKey = longPreferencesKey("pause_remaining_ms")
    private val pauseBudgetMsKey = longPreferencesKey("pause_budget_ms")
    private val pauseGrantedHoursKey = longPreferencesKey("pause_granted_hours")
    private val pauseBonusGrantedKey = longPreferencesKey("pause_bonus_granted_1") // 1 = granted, 0 = not

    private val blockEnabledKey = longPreferencesKey("block_enabled_1") // 1 = enabled, 0 = disabled (stored as long)

    fun uiState(monthFlow: Flow<YearMonth>) =
        combine(
            monthFlow.distinctUntilChanged(),
            appContext.dataStore.data,
        ) { month, prefs ->
            val minutesByDay = buildMinutesMapForMonth(month, prefs)
            val session = sessionFromPrefs(prefs)
            val blockEnabled = (prefs[blockEnabledKey] ?: 1L) == 1L
            MainUiState(
                now = LocalDate.now(),
                selectedMonth = month,
                minutesByDay = minutesByDay,
                session = session,
                blockEnabled = blockEnabled,
            )
        }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), MainUiState())

    fun setBlockEnabled(enabled: Boolean) {
        scope.launch {
            appContext.dataStore.edit { prefs ->
                prefs[blockEnabledKey] = if (enabled) 1L else 0L
            }
        }
    }

    fun startFocus(minutes: Int) {
        val clamped = minutes.coerceIn(1, 60)
        val start = System.currentTimeMillis()
        val totalMs = clamped * 60_000L
        val end = start + totalMs
        scope.launch {
            appContext.dataStore.edit { prefs ->
                // Starting focus clears pause timer state.
                prefs[pauseActiveKey] = 0L
                prefs.remove(pauseEndKey)
                prefs.remove(pauseRemainingMsKey)
                prefs.remove(pauseBudgetMsKey)
                prefs.remove(pauseGrantedHoursKey)
                prefs.remove(pauseBonusGrantedKey)
                prefs[focusEndKey] = end
                prefs[focusActiveKey] = 1L
                prefs[focusPausedKey] = 0L
                prefs.remove(focusRemainingMsKey)
                prefs[focusStartEpochMsKey] = start
                prefs[focusTotalDurationMsKey] = totalMs
                prefs[focusProfileKey] = 0L
                prefs[plannedMinutesKey] = clamped

                // Start grants 1 minute of pause budget.
                prefs[pauseBudgetMsKey] = 60_000L
            }
        }
        FocusBlockState.setActive(true)
        scheduleCompletion(end)
    }

    fun startWorkdayFocus() {
        val start = System.currentTimeMillis()
        val totalMs = 8L * 60L * 60L * 1_000L
        val end = start + totalMs
        scope.launch {
            appContext.dataStore.edit { prefs ->
                prefs[pauseActiveKey] = 0L
                prefs.remove(pauseEndKey)
                prefs.remove(pauseRemainingMsKey)
                // Start grants 1 minute of pause budget.
                prefs[pauseBudgetMsKey] = 60_000L
                prefs[pauseGrantedHoursKey] = 0L
                prefs[pauseBonusGrantedKey] = 0L

                prefs[focusEndKey] = end
                prefs[focusActiveKey] = 1L
                prefs[focusPausedKey] = 0L
                prefs.remove(focusRemainingMsKey)
                prefs[focusStartEpochMsKey] = start
                prefs[focusTotalDurationMsKey] = totalMs
                prefs[focusProfileKey] = 1L
                prefs[plannedMinutesKey] = 480 // still used for saving when completed
            }
        }
        FocusBlockState.setActive(true)
        scheduleCompletion(end)
    }

    fun stopFocus() {
        scope.launch {
            appContext.dataStore.edit { prefs ->
                prefs[focusActiveKey] = 0L
                prefs[focusPausedKey] = 0L
                prefs.remove(focusEndKey)
                prefs.remove(focusRemainingMsKey)
                prefs.remove(focusStartEpochMsKey)
                prefs.remove(focusTotalDurationMsKey)
                prefs.remove(focusProfileKey)
                prefs.remove(plannedMinutesKey)

                prefs[pauseActiveKey] = 0L
                prefs.remove(pauseEndKey)
                prefs.remove(pauseRemainingMsKey)
                prefs.remove(pauseBudgetMsKey)
                prefs.remove(pauseGrantedHoursKey)
                prefs.remove(pauseBonusGrantedKey)
            }
        }
        FocusBlockState.setActive(false)
        PlatformIntents.bringAppToFront(appContext)
    }

    fun startPause() {
        scope.launch {
            val prefsNow = appContext.dataStore.data.first()
            val focusActive = (prefsNow[focusActiveKey] ?: 0L) == 1L
            val focusPaused = (prefsNow[focusPausedKey] ?: 0L) == 1L
            if (!focusActive || focusPaused) return@launch

            val blockEnabled = (prefsNow[blockEnabledKey] ?: 1L) == 1L
            if (!blockEnabled) return@launch

            val focusEnd = prefsNow[focusEndKey] ?: return@launch
            val focusRemainingMs = (focusEnd - System.currentTimeMillis()).coerceAtLeast(0L)
            val budget = (prefsNow[pauseBudgetMsKey] ?: 0L).coerceAtLeast(0L)
            val chunkMs = 60_000L
            if (budget < chunkMs) return@launch
            val pauseEnd = System.currentTimeMillis() + chunkMs

            appContext.dataStore.edit { prefs ->
                // Pause only interrupts focus; it should resume afterwards.
                prefs[focusPausedKey] = 1L
                prefs[focusRemainingMsKey] = focusRemainingMs
                prefs.remove(focusEndKey)

                prefs[pauseActiveKey] = 1L
                prefs[pauseEndKey] = pauseEnd

                prefs[pauseBudgetMsKey] = (budget - chunkMs).coerceAtLeast(0L)
            }

            FocusBlockState.setActive(false)
            schedulePauseCompletion(pauseEnd)
        }
    }

    fun stopPause() {
        scope.launch {
            val prefsNow = appContext.dataStore.data.first()
            val pauseActive = (prefsNow[pauseActiveKey] ?: 0L) == 1L
            if (pauseActive) {
                val pauseEnd = prefsNow[pauseEndKey] ?: 0L
                val remaining = (pauseEnd - System.currentTimeMillis()).coerceAtLeast(0L)
                val budget = (prefsNow[pauseBudgetMsKey] ?: 0L).coerceAtLeast(0L)
                appContext.dataStore.edit { prefs ->
                    prefs[pauseActiveKey] = 0L
                    prefs.remove(pauseEndKey)
                    // Refund unused seconds back into budget.
                    prefs[pauseBudgetMsKey] = budget + remaining
                    prefs.remove(pauseRemainingMsKey)
                }
            }
            resumeFocusFromPause()
        }
    }

    private fun scheduleCompletion(expectedEndEpochMillis: Long) {
        scope.launch {
            val delayMs = (expectedEndEpochMillis - System.currentTimeMillis()).coerceAtLeast(0L)
            delay(delayMs)

            // Re-check state before completing (user may have stopped/restarted).
            val prefs = appContext.dataStore.data.stateIn(this, SharingStarted.Eagerly, null).value ?: return@launch
            val active = (prefs[focusActiveKey] ?: 0L) == 1L
            val paused = (prefs[focusPausedKey] ?: 0L) == 1L
            val end = prefs[focusEndKey] ?: 0L
            if (active && !paused && end == expectedEndEpochMillis && System.currentTimeMillis() >= end) {
                PlatformIntents.bringAppToFront(appContext)
                addPlannedMinutesToTodayAndClear()
            }
        }
    }

    private fun schedulePauseCompletion(expectedEndEpochMillis: Long) {
        scope.launch {
            val delayMs = (expectedEndEpochMillis - System.currentTimeMillis()).coerceAtLeast(0L)
            delay(delayMs)

            val prefs = appContext.dataStore.data.first()
            val active = (prefs[pauseActiveKey] ?: 0L) == 1L
            val end = prefs[pauseEndKey] ?: 0L
            if (active && end == expectedEndEpochMillis && System.currentTimeMillis() >= end) {
                clearPauseAndResetRemaining()
                resumeFocusFromPause()
            }
        }
    }

    private suspend fun clearPauseAndResetRemaining() {
        appContext.dataStore.edit { prefs ->
            prefs[pauseActiveKey] = 0L
            prefs.remove(pauseEndKey)
            prefs.remove(pauseRemainingMsKey)
        }
    }

    private suspend fun resumeFocusFromPause() {
        val prefsNow = appContext.dataStore.data.first()
        val focusActive = (prefsNow[focusActiveKey] ?: 0L) == 1L
        val focusPaused = (prefsNow[focusPausedKey] ?: 0L) == 1L
        if (!focusActive || !focusPaused) return

        val remainingMs = (prefsNow[focusRemainingMsKey] ?: 0L).coerceAtLeast(0L)
        if (remainingMs <= 0L) return

        val blockEnabled = (prefsNow[blockEnabledKey] ?: 1L) == 1L
        val newEnd = System.currentTimeMillis() + remainingMs
        appContext.dataStore.edit { prefs ->
            prefs[focusPausedKey] = 0L
            prefs.remove(focusRemainingMsKey)
            prefs[focusEndKey] = newEnd
        }
        val pauseActive = (prefsNow[pauseActiveKey] ?: 0L) == 1L
        FocusBlockState.setActive(blockEnabled && !pauseActive)
        scheduleCompletion(newEnd)
    }

    private suspend fun addPlannedMinutesToTodayAndClear() {
        val today = LocalDate.now()
        appContext.dataStore.edit { prefs ->
            val planned = prefs[plannedMinutesKey] ?: 0
            val dayKey = keyForDay(today)
            val current = prefs[dayKey] ?: 0
            if (planned > 0) {
                prefs[dayKey] = current + planned
            }
            prefs[focusActiveKey] = 0L
            prefs[focusPausedKey] = 0L
            prefs.remove(focusEndKey)
            prefs.remove(focusRemainingMsKey)
            prefs.remove(plannedMinutesKey)

            prefs[pauseActiveKey] = 0L
            prefs.remove(pauseEndKey)
            prefs.remove(pauseRemainingMsKey)
        }
        FocusBlockState.setActive(false)
    }

    private fun sessionFromPrefs(prefs: Preferences): FocusSessionState {
        // Workday: grant pause budget based on elapsed active focus time.
        val profile = prefs[focusProfileKey] ?: 0L
        if (profile == 1L) {
            scope.launch { maybeGrantWorkdayPauseBudget() }
        }

        val focusActive = (prefs[focusActiveKey] ?: 0L) == 1L
        val focusPaused = (prefs[focusPausedKey] ?: 0L) == 1L

        val focus: FocusTimerState? = if (!focusActive) {
            null
        } else if (focusPaused) {
            val remainingMs = (prefs[focusRemainingMsKey] ?: 0L).coerceAtLeast(0L)
            FocusTimerState(endEpochMillis = null, remainingMs = remainingMs, isPaused = true)
        } else {
            val end = prefs[focusEndKey] ?: 0L
            val remainingSeconds = ((end - System.currentTimeMillis()) / 1000L).toInt().coerceAtLeast(0)
            if (remainingSeconds <= 0) {
                scope.launch { addPlannedMinutesToTodayAndClear() }
                null
            } else {
                FocusTimerState(endEpochMillis = end, remainingMs = null, isPaused = false)
            }
        }

        val pauseActive = (prefs[pauseActiveKey] ?: 0L) == 1L
        val pause: PauseTimerState? = if (pauseActive) {
            val end = prefs[pauseEndKey] ?: 0L
            val remainingSeconds = ((end - System.currentTimeMillis()) / 1000L).toInt().coerceAtLeast(0)
            if (remainingSeconds <= 0) {
                scope.launch { clearPauseAndResetRemaining(); resumeFocusFromPause() }
                null
            } else {
                PauseTimerState(endEpochMillis = end, remainingMs = null, isRunning = true)
            }
        } else {
            val remainingMs = (prefs[pauseRemainingMsKey] ?: 0L).coerceAtLeast(0L)
            if (remainingMs > 0L) {
                PauseTimerState(endEpochMillis = null, remainingMs = remainingMs, isRunning = false)
            } else {
                null
            }
        }

        val budget = (prefs[pauseBudgetMsKey] ?: 0L).coerceAtLeast(0L)
        return FocusSessionState(focus = focus, pause = pause, pauseBudgetMs = budget)
    }

    private fun buildMinutesMapForMonth(month: YearMonth, prefs: Preferences): Map<Int, Int> {
        val map = LinkedHashMap<Int, Int>(month.lengthOfMonth())
        for (day in 1..month.lengthOfMonth()) {
            val date = month.atDay(day)
            val key = keyForDay(date)
            map[day] = prefs[key] ?: 0
        }
        return map
    }

    init {
        // Ensure focus flag in memory matches persisted state after process restart.
        scope.launch {
            appContext.dataStore.data.collect { prefs ->
                if ((prefs[focusProfileKey] ?: 0L) == 1L) {
                    maybeGrantWorkdayPauseBudget()
                }

                val active = (prefs[focusActiveKey] ?: 0L) == 1L
                val paused = (prefs[focusPausedKey] ?: 0L) == 1L
                val end = prefs[focusEndKey] ?: 0L
                val shouldComplete = active && !paused && end > 0L && System.currentTimeMillis() >= end
                if (shouldComplete) {
                    addPlannedMinutesToTodayAndClear()
                } else {
                    val pauseActive = (prefs[pauseActiveKey] ?: 0L) == 1L
                    val blockEnabled = (prefs[blockEnabledKey] ?: 1L) == 1L
                    FocusBlockState.setActive(blockEnabled && active && !paused && !pauseActive)
                }
            }
        }
    }

    private suspend fun maybeGrantWorkdayPauseBudget() {
        val prefs = appContext.dataStore.data.first()
        val focusActive = (prefs[focusActiveKey] ?: 0L) == 1L
        if (!focusActive) return
        val totalMs = (prefs[focusTotalDurationMsKey] ?: 0L).coerceAtLeast(0L)
        if (totalMs <= 0L) return

        val paused = (prefs[focusPausedKey] ?: 0L) == 1L
        val remainingMsCurrent = if (paused) {
            (prefs[focusRemainingMsKey] ?: 0L).coerceAtLeast(0L)
        } else {
            val end = prefs[focusEndKey] ?: return
            (end - System.currentTimeMillis()).coerceAtLeast(0L)
        }
        val elapsedActiveMs = (totalMs - remainingMsCurrent).coerceAtLeast(0L)

        val alreadyGrantedHours = (prefs[pauseGrantedHoursKey] ?: 0L).coerceAtLeast(0L)
        val shouldBeGrantedHours = (elapsedActiveMs / (60L * 60L * 1_000L)).coerceAtLeast(0L)
        val deltaHours = (shouldBeGrantedHours - alreadyGrantedHours).coerceAtLeast(0L)

        val bonusAlready = (prefs[pauseBonusGrantedKey] ?: 0L) == 1L
        val shouldGrantBonus = !bonusAlready && elapsedActiveMs >= 4L * 60L * 60L * 1_000L

        if (deltaHours == 0L && !shouldGrantBonus) return

        val currentBudget = (prefs[pauseBudgetMsKey] ?: 0L).coerceAtLeast(0L)
        val addFromHours = deltaHours * 60_000L
        val addBonus = if (shouldGrantBonus) 15L * 60_000L else 0L

        appContext.dataStore.edit { editPrefs ->
            editPrefs[pauseBudgetMsKey] = currentBudget + addFromHours + addBonus
            editPrefs[pauseGrantedHoursKey] = shouldBeGrantedHours
            if (shouldGrantBonus) {
                editPrefs[pauseBonusGrantedKey] = 1L
            }
        }
    }
}

object FocusBlockState {
    private val active = MutableStateFlow(false)

    fun isActive(): Boolean = active.value

    fun setActive(value: Boolean) {
        active.value = value
    }
}

