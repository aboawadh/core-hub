package hub.core.android.ui.screens

import hub.core.android.data.HubApis
import hub.core.android.data.hubCall
import hub.core.client.model.AgentKind
import hub.core.client.model.AgentSettingsPatch
import hub.core.client.model.AppToken
import hub.core.client.model.AvatarInput
import hub.core.client.model.ChannelIdentity
import hub.core.client.model.ChannelLinkCode
import hub.core.client.model.Notice
import hub.core.client.model.NotifyListNotices200Response
import hub.core.client.model.NotifyMarkAllReadRequest
import hub.core.client.model.NotifyUpdateNoticeRequest
import hub.core.client.model.PasswordChange
import hub.core.client.model.Preferences
import hub.core.client.model.SettingsField
import hub.core.client.model.SettingsSection
import hub.core.client.model.User
import hub.core.client.model.UserSelfPatch
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.OffsetDateTime
import java.util.Base64
import kotlinx.serialization.json.JsonPrimitive

/*
 * The person's own settings (batch 4: inbox, account, display, privacy): the plain rules the pages
 * follow, and the calls they make, kept apart from the Compose so OwnSettingsTest checks them against
 * a scripted hub. iOS's OwnSettingsRules.swift is the twin.
 */
object OwnSettingsRules {
    /** The most the hub keeps for a display name (`UserSelfPatch.display_name`). */
    const val NAME_MAX = 80
    /** The shortest new password the hub takes (`PasswordChange.new_password`). */
    const val PASSWORD_MIN = 8

    /** The name to send, trimmed; null when there is nothing to save (empty, too long, or unchanged). */
    fun nameToSave(typed: String, current: String): String? {
        val name = typed.trim()
        return name.takeIf { it.isNotEmpty() && it.length <= NAME_MAX && it != current }
    }

    enum class PasswordProblem { SHORT, MISMATCH }

    /** What is wrong with the new password as typed so far (nothing is said about an empty field). */
    fun passwordProblem(new: String, again: String): PasswordProblem? = when {
        new.isNotEmpty() && new.length < PASSWORD_MIN -> PasswordProblem.SHORT
        again.isNotEmpty() && again != new -> PasswordProblem.MISMATCH
        else -> null
    }

    /** Change password is offered once all three are filled and agree. */
    fun canChangePassword(current: String, new: String, again: String): Boolean =
        current.isNotEmpty() && new.length >= PASSWORD_MIN && again == new

    /** The most the hub keeps for a picture (512 KB decoded). */
    const val AVATAR_MAX_BYTES = 512 * 1024
    /** The longest side a picture is sent at. */
    const val AVATAR_SIDE = 512

    /** The size a picture is drawn at before it is sent: longest side at most [AVATAR_SIDE], never larger. */
    fun avatarSize(width: Int, height: Int): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return 0 to 0
        val scale = minOf(1.0, AVATAR_SIDE.toDouble() / maxOf(width, height))
        return Math.round(width * scale).toInt() to Math.round(height * scale).toInt()
    }

    /** The `data:` URL the hub takes for a JPEG, or null when it is empty or too big. */
    fun avatarDataUrl(jpeg: ByteArray): String? =
        if (jpeg.isEmpty() || jpeg.size > AVATAR_MAX_BYTES) null
        else "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(jpeg)

    /** The letters shown when there is no picture: the first of the first two words. */
    fun initials(name: String): String {
        val letters = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.take(2)
            .joinToString("") { String(Character.toChars(it.codePointAt(0))) }
        return letters.ifEmpty { "?" }.uppercase()
    }

    /** A code is waiting and the list grew: the account it was sent from is linked now. */
    fun linked(waiting: Boolean, before: Int?, now: Int): Boolean = waiting && before != null && now > before

    /** The inbox as the page holds it. */
    data class Inbox(val notices: List<Notice>, val unread: Int)

    /**
     * One notice read or unread here at once, the unread count moved with it (the hub says the
     * same on the next read). A notice already in that state changes nothing.
     */
    fun marking(inbox: Inbox, id: String, read: Boolean, now: OffsetDateTime = OffsetDateTime.now()): Inbox {
        val index = inbox.notices.indexOfFirst { it.id == id }
        if (index < 0) return inbox
        val wasRead = inbox.notices[index].readAt != null
        if (wasRead == read) return inbox
        val list = inbox.notices.toMutableList()
        list[index] = list[index].copy(readAt = if (read) now else null)
        return Inbox(list, maxOf(0, inbox.unread + if (read) -1 else 1))
    }

    /** Every notice read. In the «Unread» view the list empties. */
    fun allRead(notices: List<Notice>, unreadOnly: Boolean, now: OffsetDateTime = OffsetDateTime.now()): List<Notice> =
        if (unreadOnly) emptyList() else notices.map { if (it.readAt == null) it.copy(readAt = now) else it }

    /** A token a paired device holds, or one an integration holds. */
    fun isDevice(token: AppToken): Boolean = token.deviceId != null

    /** The web's and iOS's text-size range, in 5% steps. */
    val textScales: List<BigDecimal> = (85..145 step 5).map { BigDecimal(it).movePointLeft(2) }

    /** A saved text size on the nearest step of the range. */
    fun textScale(value: BigDecimal): BigDecimal {
        val clamped = value.max(textScales.first()).min(textScales.last())
        val steps = clamped.subtract(textScales.first()).divide(BigDecimal("0.05"), 0, RoundingMode.HALF_UP)
        return textScales.first().add(steps.multiply(BigDecimal("0.05"))).setScale(2)
    }

    /** Hermes's «hide ids from the model» switch in a profile's settings, when the hub reads it. */
    fun redactField(sections: List<SettingsSection>): Pair<SettingsSection, SettingsField>? {
        val section = sections.firstOrNull { it.key == "privacy" } ?: return null
        val field = section.fields.firstOrNull { it.key == "redact_pii" } ?: return null
        return section to field
    }
}

/** The calls the own-settings pages make, all through the generated client. */
class OwnSettingsOps(private val apis: () -> HubApis) {
    suspend fun me(): Result<User> = hubCall { apis().auth.authGetMe() }
    suspend fun rename(name: String): Result<User> = hubCall { apis().auth.authUpdateMe(UserSelfPatch(displayName = name)) }

    /** A new picture from its `data:` URL, or back to the generated one with null. */
    suspend fun setAvatar(dataUrl: String?): Result<User> = hubCall {
        apis().auth.authUpdateMe(
            UserSelfPatch(avatar = if (dataUrl != null) AvatarInput(AvatarInput.Kind.IMAGE, dataUrl = dataUrl) else AvatarInput(AvatarInput.Kind.GENERATED)),
        )
    }

    suspend fun avatar(userId: String): Result<java.io.File> = hubCall { apis().auth.authGetUserAvatar(userId) }
    suspend fun changePassword(current: String, new: String): Result<Unit> = hubCall { apis().auth.authChangePassword(PasswordChange(current, new)) }

    suspend fun channels(): Result<List<ChannelIdentity>> = hubCall { apis().auth.authListMyChannelIdentities().items }
    suspend fun linkCode(): Result<ChannelLinkCode> = hubCall { apis().auth.authCreateChannelLinkCode() }
    suspend fun unlink(id: String): Result<Unit> = hubCall { apis().auth.authDeleteMyChannelIdentity(id) }

    suspend fun notices(unreadOnly: Boolean, cursor: String? = null): Result<NotifyListNotices200Response> =
        hubCall { apis().notify.notifyListNotices(unread = if (unreadOnly) true else null, cursor = cursor, limit = 50) }
    suspend fun mark(id: String, read: Boolean): Result<Notice> = hubCall { apis().notify.notifyUpdateNotice(id, NotifyUpdateNoticeRequest(read = read)) }
    suspend fun markAll(): Result<Int> = hubCall { apis().notify.notifyMarkAllRead(NotifyMarkAllReadRequest()).updated }

    suspend fun tokens(): Result<List<AppToken>> = hubCall { apis().auth.authListAppTokens().items }
    suspend fun revoke(id: String): Result<Unit> = hubCall { apis().auth.authRevokeAppToken(id) }

    suspend fun preferences(): Result<Preferences> = hubCall { apis().auth.authGetPreferences() }
    suspend fun savePreferences(preferences: Preferences): Result<Preferences> = hubCall { apis().auth.authSetPreferences(preferences) }

    /** The profile's Hermes and its «hide ids» switch, or null where there is none to show. */
    suspend fun redact(profile: String): Result<Pair<String, Pair<SettingsSection, SettingsField>>?> = hubCall {
        val hermes = apis().agents.agentsList(profile).items.firstOrNull { it.kind == AgentKind.HERMES } ?: return@hubCall null
        OwnSettingsRules.redactField(apis().agents.agentsGetSettings(profile, hermes.id).sections)?.let { hermes.id to it }
    }

    suspend fun setRedact(profile: String, agentId: String, on: Boolean): Result<Unit> = hubCall {
        apis().agents.agentsUpdateSettings(profile, agentId, AgentSettingsPatch("privacy", mapOf("redact_pii" to JsonPrimitive(on))))
        Unit
    }
}
