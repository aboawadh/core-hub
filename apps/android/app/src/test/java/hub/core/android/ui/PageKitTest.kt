package hub.core.android.ui

import hub.core.android.data.HubError
import hub.core.android.ui.components.EveryUnit
import hub.core.android.ui.components.FormField
import hub.core.android.ui.components.FormKind
import hub.core.android.ui.components.FormOption
import hub.core.android.ui.components.FormProblem
import hub.core.android.ui.components.FormRules
import hub.core.android.ui.components.ListPage
import hub.core.android.ui.components.PagedList
import hub.core.android.ui.components.TriggerDraft
import hub.core.android.ui.components.TriggerProblem
import hub.core.android.ui.components.TriggerRules
import hub.core.android.ui.components.changedElsewhere
import hub.core.client.model.ScheduleTrigger
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The shared page pieces' rules (docs/clients/phone-pages.md), without drawing them. */
@OptIn(ExperimentalCoroutinesApi::class)
class PageKitTest {
    @Test fun `a form refuses what it cannot save and says why`() {
        val name = FormField("name", "Name", required = true)
        val port = FormField("port", "Port", FormKind.Number, integer = true, min = BigDecimal.ONE, max = BigDecimal(65535))
        val ratio = FormField("ratio", "Ratio", FormKind.Number)
        val mode = FormField("mode", "Mode", FormKind.Choice, options = listOf(FormOption("a", "A"), FormOption("b", "B")))
        val on = FormField("on", "On", FormKind.Toggle, required = true)
        assertEquals(FormProblem.Required, FormRules.problem(name, "  "))
        assertNull(FormRules.problem(name, "x"))
        assertNull("an empty optional field is fine", FormRules.problem(port, ""))
        assertEquals(FormProblem.NotANumber, FormRules.problem(port, "eighty"))
        assertEquals(FormProblem.NotWhole, FormRules.problem(port, "80.5"))
        assertNull("80.0 is whole", FormRules.problem(port, "80.0"))
        assertEquals(FormProblem.TooSmall(BigDecimal.ONE), FormRules.problem(port, "0"))
        assertEquals(FormProblem.TooLarge(BigDecimal(65535)), FormRules.problem(port, "70000"))
        assertNull("a comma is a decimal point", FormRules.problem(ratio, "0,7"))
        assertEquals(BigDecimal("0.7"), FormRules.number("0,7"))
        assertEquals(FormProblem.NotAnOption, FormRules.problem(mode, "c"))
        assertNull("a toggle always has a value", FormRules.problem(on, ""))
        assertEquals(setOf("name", "port"), FormRules.problems(listOf(name, port, ratio), mapOf("port" to "x")).keys)
        assertTrue(FormRules.problems(listOf(name, port), mapOf("name" to "hub", "port" to "8080")).isEmpty())
    }

    @Test fun `a trigger is built from what was typed, and refused with a reason`() {
        val zone = "Asia/Riyadh"
        val cron = TriggerDraft(cron = " 0  9 * * 1-5 ", timezone = zone)
        assertEquals(ScheduleTrigger(kind = ScheduleTrigger.Kind.CRON, timezone = zone, expression = "0 9 * * 1-5"), TriggerRules.build(cron))
        assertEquals(TriggerProblem.Cron, TriggerRules.problem(cron.copy(cron = "0 9 * *")))
        val every = TriggerDraft(kind = ScheduleTrigger.Kind.INTERVAL, every = "2", unit = EveryUnit.Hours, timezone = zone)
        assertEquals(120, TriggerRules.build(every)!!.everyMinutes)
        assertEquals(TriggerProblem.Every, TriggerRules.problem(every.copy(every = "0")))
        val once = TriggerDraft(kind = ScheduleTrigger.Kind.ONCE, date = "2026-10-01", time = "9:30", timezone = zone)
        // 09:30 in Riyadh (UTC+3) is 06:30 UTC, sent in UTC.
        assertEquals(OffsetDateTime.parse("2026-10-01T06:30:00Z"), TriggerRules.build(once)!!.runAt)
        assertEquals(TriggerProblem.When, TriggerRules.problem(once.copy(time = "25:00")))
        assertEquals(TriggerProblem.Zone, TriggerRules.problem(cron.copy(timezone = "Mars/Olympus")))
        assertNull(TriggerRules.build(cron.copy(timezone = "Mars/Olympus")))
    }

    @Test fun `a saved trigger comes back as the draft that makes it`() {
        assertEquals(2 to EveryUnit.Hours, TriggerRules.split(120))
        assertEquals(1 to EveryUnit.Days, TriggerRules.split(1440))
        assertEquals(90 to EveryUnit.Minutes, TriggerRules.split(90))
        val saved = ScheduleTrigger(kind = ScheduleTrigger.Kind.ONCE, timezone = "Asia/Riyadh", runAt = OffsetDateTime.parse("2026-10-01T06:30:00Z"))
        val draft = TriggerRules.draft(saved)
        assertEquals("2026-10-01", draft.date)
        assertEquals("09:30", draft.time)
        assertEquals(saved, TriggerRules.build(draft))
        val every = ScheduleTrigger(kind = ScheduleTrigger.Kind.INTERVAL, timezone = "UTC", everyMinutes = 1440)
        assertEquals(every, TriggerRules.build(TriggerRules.draft(every)))
        val now = ZonedDateTime.of(2026, 9, 27, 23, 10, 0, 0, ZoneId.of("UTC"))
        assertEquals("2026-09-28" to "00:10", TriggerRules.inAnHour(TriggerDraft(timezone = "UTC"), now).let { it.date to it.time })
        assertEquals("2026-09-28" to "09:00", TriggerRules.tomorrowAtNine(TriggerDraft(timezone = "UTC"), now).let { it.date to it.time })
    }

    @Test fun `a paged list adds the next page, keeps rows once, and keeps them on a failure`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        var fail = false
        val pages = mapOf(null to ListPage(listOf("a", "b"), "p2"), "p2" to ListPage(listOf("b", "c"), null))
        val list = PagedList<String>(scope, { it }) { cursor -> if (fail) throw HubError(500, "boom", "down") else pages.getValue(cursor) }
        assertTrue(list.loading)
        list.refresh(); scope.advanceUntilIdle()
        assertEquals(listOf("a", "b"), list.items)
        assertTrue(list.hasMore)
        list.loadMore(); scope.advanceUntilIdle()
        assertEquals(listOf("a", "b", "c"), list.items)
        assertFalse(list.hasMore)
        fail = true
        list.refresh(); scope.advanceUntilIdle()
        assertEquals("a failure keeps what was loaded", listOf("a", "b", "c"), list.items)
        assertEquals(500, list.error?.status)
        list.remove("b")
        assertEquals(listOf("a", "c"), list.items)
    }

    @Test fun `a save refused as changed elsewhere is told apart`() {
        assertTrue(changedElsewhere(HubError(409, "changed", null)))
        assertFalse(changedElsewhere(HubError(409, "conflict", null)))
        // What the hub actually sends (config files, profile files): `conflict` with `details.reason = changed`.
        assertTrue(changedElsewhere(HubError(409, "conflict", null, reason = "changed")))
        assertFalse(changedElsewhere(HubError(409, "conflict", null, reason = "exists")))
        assertFalse(changedElsewhere(null))
    }
}
