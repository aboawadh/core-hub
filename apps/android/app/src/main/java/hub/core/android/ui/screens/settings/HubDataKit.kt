package hub.core.android.ui.screens

import hub.core.android.data.apiBase
import hub.core.android.data.hubCall
import hub.core.android.ui.components.ListPage
import hub.core.client.api.AuditApi
import hub.core.client.api.JobsApi
import hub.core.client.api.KnowledgeApi
import hub.core.client.api.NotifyApi
import hub.core.client.api.PluginsApi
import hub.core.client.model.ActiveAgent
import hub.core.client.model.HubPlugin
import hub.core.client.model.Job
import hub.core.client.model.JobStatus
import hub.core.client.model.KnowledgeItem
import hub.core.client.model.NotifyListWebhookEvents200ResponseItemsInner
import hub.core.client.model.SkillUsageReport
import hub.core.client.model.SkillUsageReportByDayInner
import hub.core.client.model.Webhook
import hub.core.client.model.WebhookDelivery
import hub.core.client.model.WebhookEventName
import hub.core.client.model.WebhookWrite
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.URI
import java.security.SecureRandom
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.OkHttpClient

/*
 * Settings → Knowledge, Skills usage, Plugins and Webhooks (apps batch 10): the calls those four
 * pages make and the plain rules they follow, kept apart from the Compose so KnowledgeReportsTest
 * checks them against a scripted hub. iOS's HubDataRules.swift is the twin.
 */

/**
 * The generated clients these pages call. The notify webhooks are `x-scope: global` in the contract
 * (no profile parameter), yet the hub keeps each webhook in the profile the request names, as the web
 * sends it; so their client carries the page's profile as a header of every call.
 */
class HubDataApis(hub: String, client: OkHttpClient, profile: String) {
    private val base = apiBase(hub)
    private val scoped = client.newBuilder()
        .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header(PROFILE_HEADER, profile).build()) }
        .build()
    val knowledge = KnowledgeApi(base, client)
    val plugins = PluginsApi(base, client)
    val audit = AuditApi(base, client)
    val notify = NotifyApi(base, scoped)
    val jobs = JobsApi(base, client)

    companion object {
        const val PROFILE_HEADER = "X-Hub-Profile"
    }
}

class HubDataOps(private val profile: String, private val apis: () -> HubDataApis) {
    suspend fun knowledge(kind: String?, query: String?, cursor: String?): Result<ListPage<KnowledgeItem>> = hubCall {
        val page = apis().knowledge.knowledgeListItems(profile, KnowledgeRules.kind(kind), KnowledgeRules.query(query.orEmpty()), cursor, 50)
        ListPage(page.items, page.nextCursor)
    }

    suspend fun plugins(): Result<List<HubPlugin>> = hubCall { apis().plugins.pluginsList().items }

    /** One report: every profile the person may enter ([everyProfile]) or the one named, one agent or all. */
    suspend fun skillUsage(days: Int, scope: String, everyProfile: Boolean, agent: String?, offset: Int = SkillsUsageRules.utcOffset()): Result<SkillUsageReport> =
        hubCall {
            apis().audit.auditGetSkillUsage(
                scope, days, if (everyProfile) AuditApi.ProfilesAuditGetSkillUsage.ALL else null, agent, offset,
            )
        }

    suspend fun webhooks(): Result<List<Webhook>> = hubCall { apis().notify.notifyListWebhooks().items }
    suspend fun events(): Result<List<NotifyListWebhookEvents200ResponseItemsInner>> = hubCall { apis().notify.notifyListWebhookEvents().items }
    suspend fun create(body: WebhookWrite): Result<Webhook> = hubCall { apis().notify.notifyCreateWebhook(body) }
    suspend fun update(id: String, body: WebhookWrite): Result<Webhook> = hubCall { apis().notify.notifyUpdateWebhook(id, body) }
    suspend fun setEnabled(id: String, on: Boolean): Result<Webhook> = update(id, WebhookWrite(enabled = on, maxRetries = null))
    suspend fun delete(id: String): Result<Unit> = hubCall { apis().notify.notifyDeleteWebhook(id) }
    suspend fun deliveries(id: String): Result<List<WebhookDelivery>> = hubCall { apis().notify.notifyListWebhookDeliveries(id, 10).items }
    suspend fun redeliver(id: String, delivery: String): Result<WebhookDelivery> = hubCall { apis().notify.notifyRedeliverWebhookDelivery(id, delivery) }

    /** Queues the test delivery and follows its job to the end (as the web polls it). */
    suspend fun test(id: String, pause: suspend () -> Unit = { kotlinx.coroutines.delay(700) }, tries: Int = 60): Result<NotifyWebhookRules.TestOutcome> = hubCall {
        val jobId = apis().notify.notifyTestWebhook(id).jobId
        var outcome: NotifyWebhookRules.TestOutcome? = null
        var left = tries
        while (outcome == null && left-- > 0) {
            outcome = NotifyWebhookRules.outcome(apis().jobs.jobsGet(profile, jobId))
            if (outcome == null) pause()
        }
        outcome ?: NotifyWebhookRules.TestOutcome(false, 0, null)
    }
}

object KnowledgeRules {
    /** The kind chips, «All» first (the web's order). */
    val KINDS: List<String?> = listOf(null, "journal", "note", "file")

    fun kind(value: String?): KnowledgeApi.KindKnowledgeListItems? =
        value?.let { v -> KnowledgeApi.KindKnowledgeListItems.entries.firstOrNull { it.value == v } }

    /** The search as sent: trimmed, and none at all when only spaces were typed. */
    fun query(typed: String): String? = typed.trim().takeIf { it.isNotEmpty() }

    /** The day a row belongs to: a journal entry's own date, else the day it was written. */
    fun day(item: KnowledgeItem): LocalDate = item.date ?: item.createdAt.toLocalDate()
}

object SkillsUsageRules {
    /** The periods the page offers (the contract takes 1–365), as on the web. */
    val PERIODS = listOf(7, 30, 90, 365)

    /** Minutes east of UTC now, so a day on the page is the person's own calendar day. */
    fun utcOffset(zone: ZoneId = ZoneId.systemDefault(), at: Instant = Instant.now()): Int = zone.rules.getOffset(at).totalSeconds / 60

    /** The days anything was loaded, newest first (the chart as a compact list). */
    fun activeDays(report: SkillUsageReport): List<SkillUsageReportByDayInner> = report.byDay.filter { it.uses > 0 }.reversed()

    /** A day's skills, most used first, then «other» (the skills outside the top series) when there were any. */
    fun daySkills(day: SkillUsageReportByDayInner): List<Pair<String?, Int>> =
        day.skills.entries.filter { it.value > 0 }.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map<Map.Entry<String, Int>, Pair<String?, Int>> { it.key to it.value } + (if (day.other > 0) listOf(null to day.other) else emptyList())

    /** A bar's length beside the busiest day, 0 to 1. */
    fun fraction(uses: Int, most: Int): Float = if (most <= 0 || uses <= 0) 0f else (uses.toFloat() / most).coerceAtMost(1f)

    /** A share (0.125) as the web writes it: at most one decimal, Latin digits («12.5%»). */
    fun percent(share: BigDecimal): String =
        share.multiply(BigDecimal(100)).setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().let {
            (if (it.scale() < 0) it.setScale(0) else it).toPlainString() + "%"
        }

    /**
     * The agents offered: every agent active in the period, and the chosen one when the period no
     * longer has it (so the choice can be taken back rather than silently dropped).
     */
    fun agentChoices(agents: List<ActiveAgent>, chosen: String?): List<ActiveAgent> =
        if (chosen == null || agents.any { it.agentId == chosen }) agents else agents + ActiveAgent(chosen, false, null)
}

object NotifyWebhookRules {
    const val DEFAULT_RETRIES = 5
    const val MAX_RETRIES = 10

    /** The header a receiver checks (`derived.webhookSignatureHeader` in the contract package). */
    const val SIGNATURE_HEADER = "X-CoreHub-Signature"

    enum class SecretChoice { KEEP, NEW, NONE }

    /** What the add/edit sheet holds. */
    data class Draft(
        val name: String = "",
        val url: String = "",
        val events: Set<String> = emptySet(),
        val allProfiles: Boolean = true,
        val profiles: Set<String> = emptySet(),
        val enabled: Boolean = true,
        val includeContent: Boolean = false,
        val allowPrivate: Boolean = false,
        val retries: String = DEFAULT_RETRIES.toString(),
        val secret: SecretChoice = SecretChoice.NEW,
    )

    /** The sheet for [hook], or a new one: signed unless somebody says otherwise. */
    fun draft(hook: Webhook?): Draft = if (hook == null) Draft() else Draft(
        name = hook.name, url = hook.url.toString(), events = hook.events.toSet(), allProfiles = hook.profiles.isEmpty(),
        profiles = hook.profiles.toSet(), enabled = hook.enabled, includeContent = hook.includeContent,
        allowPrivate = hook.allowPrivateNetwork, retries = hook.maxRetries.toString(),
        secret = if (hook.secret != null) SecretChoice.KEEP else SecretChoice.NONE,
    )

    /** The secret choices the sheet offers: keep/new/stop for a signed one, new/none otherwise. */
    fun secretChoices(hook: Webhook?): List<SecretChoice> =
        if (hook?.secret != null) listOf(SecretChoice.KEEP, SecretChoice.NEW, SecretChoice.NONE) else listOf(SecretChoice.NEW, SecretChoice.NONE)

    /** A number for the retries field, within the contract's 0–10. */
    fun retries(typed: String): Int = typed.trim().toIntOrNull()?.coerceIn(0, MAX_RETRIES) ?: DEFAULT_RETRIES

    fun urlOk(url: String): Boolean = Regex("^https?://\\S+$", RegexOption.IGNORE_CASE).matches(url) && runCatching { URI(url) }.isSuccess

    /** Nothing is said about an empty address; a typed one must be http(s). */
    fun badUrl(url: String): Boolean = url.isNotEmpty() && !urlOk(url)

    /** «Only these profiles» with none chosen. */
    fun noProfile(draft: Draft): Boolean = !draft.allProfiles && draft.profiles.isEmpty()

    fun ready(draft: Draft): Boolean = draft.name.isNotBlank() && draft.name.trim().length <= 80 && urlOk(draft.url) && !noProfile(draft)

    /** A new HMAC key: 32 random bytes as hex, with a prefix that says what it is. */
    fun newSecret(random: (ByteArray) -> Unit = { SecureRandom().nextBytes(it) }): String {
        val bytes = ByteArray(32).also(random)
        return "whsec_" + bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    /**
     * The body to save. Events the hub no longer sends are dropped (it refuses them); «every profile»
     * is the empty list; the secret is left out to keep it, set to the new one, or sent as `null` to stop signing.
     */
    fun write(draft: Draft, known: List<String>?, secret: String?): WebhookWrite = WebhookWrite(
        name = draft.name.trim(),
        url = URI(draft.url.trim()),
        events = draft.events.filter { known == null || it in known }.sorted().mapNotNull { WebhookEventName.decode(it) },
        profiles = if (draft.allProfiles) emptyList() else draft.profiles.sorted(),
        enabled = draft.enabled,
        secret = if (draft.secret == SecretChoice.NEW) secret else null,
        includeContent = draft.includeContent,
        allowPrivateNetwork = draft.allowPrivate,
        maxRetries = retries(draft.retries),
        sendNull = if (draft.secret == SecretChoice.NONE) setOf(WebhookWrite.Clearable.SECRET) else emptySet(),
    )

    /** Whether a delivery may be sent again: it is over and did not arrive (one waiting for its retry is sent anyway). */
    fun canRedeliver(delivery: WebhookDelivery): Boolean =
        delivery.status == WebhookDelivery.Status.DEAD || (delivery.status == WebhookDelivery.Status.FAILED && delivery.nextAttemptAt == null)

    /** The deliveries table follows the hub while something waits to be sent. */
    fun waiting(deliveries: List<WebhookDelivery>): Boolean =
        deliveries.any { it.status == WebhookDelivery.Status.QUEUED || it.nextAttemptAt != null }

    /** What the test job reports when it is done. */
    data class TestOutcome(val delivered: Boolean, val status: Int, val error: String?)

    /** The outcome of a finished test job, or null while it runs. */
    fun outcome(job: Job): TestOutcome? {
        if (job.status == JobStatus.QUEUED || job.status == JobStatus.RUNNING) return null
        val result = job.result
        if (job.status == JobStatus.SUCCEEDED && result != null) {
            val delivered = (result["delivered"] as? JsonPrimitive)?.booleanOrNull ?: false
            val status = (result["status"] as? JsonPrimitive)?.intOrNull ?: 0
            val error = (result["error"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            return TestOutcome(delivered, status, error)
        }
        return TestOutcome(false, 0, job.error?.error)
    }

    /** The hub's reason for refusing an address, as one of the sheet's own sentences; null for anything else. */
    enum class UrlRefusal { SCHEME, UNRESOLVABLE, PRIVATE }

    fun urlRefusal(reason: String?): UrlRefusal? = when (reason) {
        "url_scheme" -> UrlRefusal.SCHEME
        "url_unresolvable" -> UrlRefusal.UNRESOLVABLE
        "url_private" -> UrlRefusal.PRIVATE
        else -> null
    }

    /** The catalogue narrowed by [needle] (name or description, any case). */
    fun filterEvents(events: List<NotifyListWebhookEvents200ResponseItemsInner>, needle: String, arabic: Boolean): List<NotifyListWebhookEvents200ResponseItemsInner> {
        val n = needle.trim().lowercase()
        if (n.isEmpty()) return events
        return events.filter { e -> e.name.lowercase().contains(n) || (if (arabic) e.description.ar else e.description.en).lowercase().contains(n) }
    }
}
