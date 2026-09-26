package hub.core.android.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Interceptor
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer

/**
 * The contract lists every field of a `ScheduleTrigger` and of a `ScheduleTarget` as required, some
 * of them `null` (`expression` of an interval, `model` of a prompt). The generated JSON leaves every
 * null out (`explicitNulls = false`, so a PATCH does not clear what it did not mean to touch), and
 * the hub checks bodies against the contract: without them it refuses the preview, the create and
 * the edit (400 "must have required property"). This puts each missing one back as `null` before
 * the request leaves. iOS's ScheduleBodies.swift is its twin.
 */
object ScheduleBodies : Interceptor {
    private val json = Json { ignoreUnknownKeys = true }
    private val triggerKinds = setOf("cron", "interval", "once")
    private val triggerFields = listOf("expression", "every_minutes", "run_at")
    private val targetKinds = setOf("agent_prompt", "workflow")
    private val targetFields = listOf("agent_id", "prompt", "model", "provider", "workflow_id", "input")

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val body = request.body ?: return chain.proceed(request)
        if (body.contentType()?.subtype != "json") return chain.proceed(request)
        val text = Buffer().also { body.writeTo(it) }.readUtf8()
        val filled = complete(text) ?: return chain.proceed(request)
        return chain.proceed(request.newBuilder().method(request.method, filled.toRequestBody(body.contentType())).build())
    }

    /** The body with the missing fields as `null`; null when nothing was missing or it is not a JSON object. */
    fun complete(body: String): String? {
        val obj = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
        var changed = false
        fun fill(key: String, kinds: Set<String>, fields: List<String>, extra: Map<String, kotlinx.serialization.json.JsonElement> = emptyMap()): JsonObject? {
            val part = obj[key] as? JsonObject ?: return null
            val kind = (part["kind"] as? JsonPrimitive)?.contentOrNull ?: return null
            if (kind !in kinds) return null
            val missing = fields.filter { it !in part }.associateWith { JsonNull } + extra.filterKeys { it !in part }
            if (missing.isEmpty()) return null
            changed = true
            return JsonObject(part + missing)
        }
        val trigger = fill("trigger", triggerKinds, triggerFields)
        val target = fill("target", targetKinds, targetFields, mapOf("skills" to JsonArray(emptyList())))
        if (!changed) return null
        return JsonObject(obj + listOfNotNull(trigger?.let { "trigger" to it }, target?.let { "target" to it })).toString()
    }
}
