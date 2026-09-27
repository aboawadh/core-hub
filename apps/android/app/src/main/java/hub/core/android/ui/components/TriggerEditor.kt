package hub.core.android.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hub.core.android.R
import hub.core.android.data.HubError
import hub.core.android.data.hubCall
import hub.core.android.generated.FontTokens
import hub.core.android.ui.kit.ButtonKind
import hub.core.android.ui.kit.Chip
import hub.core.android.ui.kit.ControlSize
import hub.core.android.ui.kit.GroupedList
import hub.core.android.ui.kit.HubButton
import hub.core.android.ui.kit.HubSheet
import hub.core.android.ui.kit.HubTextField
import hub.core.android.ui.kit.Item
import hub.core.android.ui.kit.Lucide
import hub.core.android.ui.kit.Segment
import hub.core.android.ui.kit.Segmented
import hub.core.android.ui.theme.LocalTokens
import hub.core.client.model.ScheduleTrigger
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.delay

/*
 * When something runs (docs/clients/phone-pages.md): a cron expression, every N minutes/hours/days,
 * or once at a date and time, in a time zone — the hub's `ScheduleTrigger`, shared by Schedules
 * and an agent's Jobs. The rules are plain functions ([TriggerRules]); the hub has the last word
 * (`schedules.previewTrigger`), which the editor shows as the next runs when the page passes it.
 */

enum class EveryUnit(val minutes: Int) { Minutes(1), Hours(60), Days(1440) }

/** A trigger as it is being typed: every kind's fields are kept, so switching kind loses nothing. */
data class TriggerDraft(
    val kind: ScheduleTrigger.Kind = ScheduleTrigger.Kind.CRON,
    val cron: String = "0 9 * * *",
    val every: String = "1",
    val unit: EveryUnit = EveryUnit.Hours,
    /** `YYYY-MM-DD`. */
    val date: String = "",
    /** `HH:MM`. */
    val time: String = "09:00",
    val timezone: String = ZoneId.systemDefault().id,
)

enum class TriggerProblem { Cron, Every, When, Zone }

object TriggerRules {
    /** Every hour; daily, weekdays and Mondays at 09:00. */
    val presets = listOf("0 * * * *", "0 9 * * *", "0 9 * * 1-5", "0 9 * * 1")

    /** The draft of a saved trigger (or a new one in [zone]). */
    fun draft(trigger: ScheduleTrigger?, zone: String = ZoneId.systemDefault().id): TriggerDraft {
        if (trigger == null) return TriggerDraft(timezone = zone)
        val (every, unit) = split(trigger.everyMinutes ?: 60)
        val at = trigger.runAt?.let { runCatching { it.atZoneSameInstant(ZoneId.of(trigger.timezone)) }.getOrNull() }
        return TriggerDraft(
            kind = trigger.kind, cron = trigger.expression ?: "0 9 * * *", every = every.toString(), unit = unit,
            date = at?.toLocalDate()?.toString().orEmpty(), time = at?.toLocalTime()?.let { "%02d:%02d".format(java.util.Locale.ROOT, it.hour, it.minute) } ?: "09:00",
            timezone = trigger.timezone,
        )
    }

    /** Minutes as the largest whole unit: 120 → 2 hours, 1440 → 1 day, 90 → 90 minutes. */
    fun split(minutes: Int): Pair<Int, EveryUnit> = EveryUnit.entries.reversed().first { minutes % it.minutes == 0 }.let { minutes / it.minutes to it }

    fun zoneKnown(id: String): Boolean = runCatching { ZoneId.of(id) }.isSuccess

    fun time(text: String): LocalTime? = Regex("""^\s*(\d{1,2}):(\d{2})\s*$""").find(text)?.let { m ->
        runCatching { LocalTime.of(m.groupValues[1].toInt(), m.groupValues[2].toInt()) }.getOrNull()
    }

    fun date(text: String): LocalDate? = runCatching { LocalDate.parse(text.trim()) }.getOrNull()

    fun problem(d: TriggerDraft): TriggerProblem? = when {
        !zoneKnown(d.timezone) -> TriggerProblem.Zone
        d.kind == ScheduleTrigger.Kind.CRON && d.cron.trim().split(Regex("\\s+")).size != 5 -> TriggerProblem.Cron
        d.kind == ScheduleTrigger.Kind.INTERVAL && (d.every.trim().toIntOrNull() ?: 0) < 1 -> TriggerProblem.Every
        d.kind == ScheduleTrigger.Kind.ONCE && (date(d.date) == null || time(d.time) == null) -> TriggerProblem.When
        else -> null
    }

    /** The trigger to send, or null while [problem] says why not. `run_at` goes in UTC. */
    fun build(d: TriggerDraft): ScheduleTrigger? {
        if (problem(d) != null) return null
        return when (d.kind) {
            ScheduleTrigger.Kind.CRON -> ScheduleTrigger(kind = d.kind, timezone = d.timezone, expression = d.cron.trim().split(Regex("\\s+")).joinToString(" "))
            ScheduleTrigger.Kind.INTERVAL -> ScheduleTrigger(kind = d.kind, timezone = d.timezone, everyMinutes = d.every.trim().toInt() * d.unit.minutes)
            ScheduleTrigger.Kind.ONCE -> ScheduleTrigger(
                kind = d.kind, timezone = d.timezone,
                runAt = LocalDateTime.of(date(d.date)!!, time(d.time)!!).atZone(ZoneId.of(d.timezone)).toOffsetDateTime().withOffsetSameInstant(ZoneOffset.UTC),
            )
        }
    }

    /** A one-off an hour from [now], or tomorrow at 09:00, in the draft's zone. */
    fun inAnHour(d: TriggerDraft, now: ZonedDateTime = ZonedDateTime.now(ZoneId.of(d.timezone))): TriggerDraft =
        now.plusHours(1).let { d.copy(date = it.toLocalDate().toString(), time = "%02d:%02d".format(java.util.Locale.ROOT, it.hour, it.minute)) }

    fun tomorrowAtNine(d: TriggerDraft, now: ZonedDateTime = ZonedDateTime.now(ZoneId.of(d.timezone))): TriggerDraft =
        d.copy(date = now.toLocalDate().plusDays(1).toString(), time = "09:00")
}

@Composable
private fun problemText(p: TriggerProblem): String = stringResource(
    when (p) {
        TriggerProblem.Cron -> R.string.kit_trigger_bad_cron
        TriggerProblem.Every -> R.string.kit_trigger_bad_every
        TriggerProblem.When -> R.string.kit_trigger_bad_when
        TriggerProblem.Zone -> R.string.kit_trigger_bad_zone
    },
)

/**
 * Edits [draft]; [onChange] gets every change. With [preview] (the page's call to
 * `schedules.previewTrigger`), the next runs show under it as the person types. Tags:
 * `<tag>.kind.<cron|interval|once>`, `<tag>.cron`, `<tag>.every`, `<tag>.date`, `<tag>.time`, `<tag>.zone`, `<tag>.next`.
 */
@Composable
fun TriggerEditor(
    draft: TriggerDraft,
    onChange: (TriggerDraft) -> Unit,
    modifier: Modifier = Modifier,
    preview: (suspend (ScheduleTrigger) -> List<OffsetDateTime>)? = null,
    tag: String = "trigger",
) {
    val t = LocalTokens.current
    var zones by remember { mutableStateOf(false) }
    val problem = TriggerRules.problem(draft)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Segmented(
            listOf(
                Segment(ScheduleTrigger.Kind.CRON, stringResource(R.string.kit_trigger_cron), tag = "$tag.kind.cron"),
                Segment(ScheduleTrigger.Kind.INTERVAL, stringResource(R.string.kit_trigger_every), tag = "$tag.kind.interval"),
                Segment(ScheduleTrigger.Kind.ONCE, stringResource(R.string.kit_trigger_once), tag = "$tag.kind.once"),
            ),
            draft.kind, { onChange(draft.copy(kind = it)) }, Modifier.fillMaxWidth(), size = ControlSize.Md,
        )
        when (draft.kind) {
            ScheduleTrigger.Kind.CRON -> {
                HubTextField(
                    draft.cron, { onChange(draft.copy(cron = it)) }, label = stringResource(R.string.kit_trigger_expression), mono = true, size = ControlSize.Md,
                    error = if (problem == TriggerProblem.Cron) problemText(problem) else null, fieldTag = "$tag.cron",
                )
                Text(stringResource(R.string.kit_trigger_expression_help), fontSize = FontTokens.sizeXs.sp, color = t.textMuted)
                val names = listOf(R.string.kit_trigger_hourly, R.string.kit_trigger_daily, R.string.kit_trigger_weekdays, R.string.kit_trigger_weekly)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TriggerRules.presets.zip(names).forEach { (cron, name) ->
                        Chip(stringResource(name), draft.cron.trim() == cron, { onChange(draft.copy(cron = cron)) }, size = ControlSize.Sm)
                    }
                }
            }
            ScheduleTrigger.Kind.INTERVAL -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HubTextField(
                    draft.every, { onChange(draft.copy(every = it.filter(Char::isDigit))) }, Modifier.weight(1f), size = ControlSize.Md,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), fieldTag = "$tag.every",
                    error = if (problem == TriggerProblem.Every) problemText(problem) else null,
                )
                Segmented(
                    listOf(
                        Segment(EveryUnit.Minutes, stringResource(R.string.kit_trigger_minutes)),
                        Segment(EveryUnit.Hours, stringResource(R.string.kit_trigger_hours)),
                        Segment(EveryUnit.Days, stringResource(R.string.kit_trigger_days)),
                    ),
                    draft.unit, { onChange(draft.copy(unit = it)) }, Modifier.weight(2f), size = ControlSize.Md,
                )
            }
            ScheduleTrigger.Kind.ONCE -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HubTextField(draft.date, { onChange(draft.copy(date = it)) }, Modifier.weight(3f), label = stringResource(R.string.kit_trigger_date), placeholder = "2026-10-01", mono = true, size = ControlSize.Md, fieldTag = "$tag.date")
                    HubTextField(draft.time, { onChange(draft.copy(time = it)) }, Modifier.weight(2f), label = stringResource(R.string.kit_trigger_time), placeholder = "09:00", mono = true, size = ControlSize.Md, fieldTag = "$tag.time")
                }
                if (problem == TriggerProblem.When) Text(problemText(problem), fontSize = FontTokens.sizeXs.sp, color = t.danger)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip(stringResource(R.string.kit_trigger_in_hour), false, { onChange(TriggerRules.inAnHour(draft)) }, size = ControlSize.Sm)
                    Chip(stringResource(R.string.kit_trigger_tomorrow), false, { onChange(TriggerRules.tomorrowAtNine(draft)) }, size = ControlSize.Sm)
                }
            }
        }
        HubButton(
            "${stringResource(R.string.kit_trigger_timezone)}: ${draft.timezone}", { zones = true },
            kind = ButtonKind.Secondary, size = ControlSize.Sm, icon = Lucide.Globe, modifier = Modifier.testTag("$tag.zone"),
        )
        if (problem == TriggerProblem.Zone) Text(problemText(problem), fontSize = FontTokens.sizeXs.sp, color = t.danger)
        if (preview != null) TriggerPreview(TriggerRules.build(draft), preview, "$tag.next")
    }
    if (zones) ZonePicker(draft.timezone, { onChange(draft.copy(timezone = it)); zones = false }, { zones = false })
}

/** The next runs by the hub's own calculation, a moment after the typing stops. */
@Composable
private fun TriggerPreview(trigger: ScheduleTrigger?, preview: suspend (ScheduleTrigger) -> List<OffsetDateTime>, tag: String) {
    val t = LocalTokens.current
    var runs by remember { mutableStateOf<Result<List<OffsetDateTime>>?>(null) }
    LaunchedEffect(trigger) {
        runs = null
        if (trigger == null) return@LaunchedEffect
        delay(400)
        runs = hubCall { preview(trigger) }
    }
    val result = runs ?: return
    Column(Modifier.testTag(tag), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(stringResource(R.string.kit_trigger_next), fontSize = FontTokens.sizeSm.sp, color = t.textMuted)
        result.onFailure { ErrorNotice(it as HubError) }.onSuccess { list ->
            if (list.isEmpty()) Text(stringResource(R.string.kit_trigger_never), fontSize = FontTokens.sizeSm.sp, color = t.textMuted)
            val zone = trigger?.timezone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()
            val format = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            list.forEach { Text(it.atZoneSameInstant(zone).format(format), fontSize = FontTokens.sizeSm.sp) }
        }
    }
}

/** Every time zone the phone knows, searched by any part of its name. */
@Composable
private fun ZonePicker(current: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val all = remember { ZoneId.getAvailableZoneIds().filter { '/' in it && !it.startsWith("Etc/") }.sorted() + "UTC" }
    val shown = all.filter { query.isBlank() || it.contains(query.trim().replace(' ', '_'), ignoreCase = true) }
    HubSheet(onDismiss = onDismiss, title = stringResource(R.string.kit_trigger_timezone)) {
        HubTextField(query, { query = it }, placeholder = stringResource(R.string.kit_trigger_zone_search), leadingIcon = Lucide.Search, size = ControlSize.Md, fieldTag = "zone.search")
        LazyColumn(Modifier.heightIn(max = 420.dp)) {
            item {
                GroupedList {
                    shown.take(200).forEach { zone ->
                        Item(zone.replace('_', ' '), tag = "zone.$zone", onClick = { onPick(zone) }, trailing = if (zone == current) ({ hub.core.android.ui.kit.LucideIcon(Lucide.Check, null, size = 16.dp, tint = LocalTokens.current.accent) }) else null)
                    }
                }
            }
        }
    }
}
