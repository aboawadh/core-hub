package hub.core.android.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import hub.core.android.R
import hub.core.android.data.HubError
import hub.core.android.data.hubCall
import hub.core.android.generated.FontTokens
import hub.core.android.graph
import hub.core.android.ui.components.ErrorNotice
import hub.core.android.ui.components.FormRules
import hub.core.android.ui.components.Notice
import hub.core.android.ui.components.Tone
import hub.core.android.ui.kit.ButtonKind
import hub.core.android.ui.kit.ControlSize
import hub.core.android.ui.kit.HubButton
import hub.core.android.ui.kit.HubRadio
import hub.core.android.ui.kit.ItemShape
import hub.core.android.ui.kit.Lucide
import hub.core.android.ui.kit.Spinner
import hub.core.android.ui.theme.LocalTokens
import hub.core.client.model.ChannelLoginRequest
import hub.core.client.model.Job
import hub.core.client.model.JobStatus
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/*
 * Pairing a platform that links by QR (WhatsApp, ADR 0015) on the phone itself — before
 * 2026-09-27 the sheet only sent the person to the web. The person first says how the number will
 * be used (a bot's number, or their own «Message yourself»); then one `channel_login` job draws the
 * code here, to scan with WhatsApp on the phone that has that number (Settings → Linked devices →
 * Link a device). Leaving before it links cancels the job, and the hub tells Hermes to forget it.
 */

/** What a `channel_login` job's result says (`{status, qr, expires_at, account_name, account_phone, mode, applies}`). */
data class PairState(
    val qr: String? = null,
    val expiresAt: java.time.OffsetDateTime? = null,
    val accountName: String? = null,
    val accountPhone: String? = null,
    val mode: String? = null,
    val appliesNow: Boolean = false,
)

object ChannelPairRules {
    fun state(job: Job?): PairState {
        val r = job?.result ?: return PairState()
        fun str(key: String) = (r[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        return PairState(
            qr = str("qr"),
            expiresAt = str("expires_at")?.let { runCatching { java.time.OffsetDateTime.parse(it) }.getOrNull() },
            accountName = str("account_name"), accountPhone = str("account_phone"), mode = str("mode"),
            appliesNow = str("applies") == "now",
        )
    }

    fun finished(job: Job?): Boolean = job != null && job.status in setOf(JobStatus.SUCCEEDED, JobStatus.FAILED, JobStatus.CANCELLED)

    /** The account in one line: its name, then its number. */
    fun account(state: PairState): String = listOfNotNull(state.accountName, state.accountPhone).joinToString(" · ")

    /** Which «linked» sentence to show (the web's doneKey). */
    fun doneKey(state: PairState, chosen: ChannelLoginRequest.Mode?): String {
        val personal = (state.mode ?: chosen?.value) == ChannelLoginRequest.Mode.SELF_MINUS_CHAT.value
        val account = account(state).isNotEmpty()
        return when {
            state.appliesNow && personal -> if (account) "done_as_self_now" else "done_self_now"
            state.appliesNow -> if (account) "done_as_now" else "done_now"
            else -> if (account) "done_as" else "done"
        }
    }

    /** The code as a picture, black on white. */
    fun qr(text: String, size: Int = 560): Bitmap? = runCatching {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
        Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565).apply {
            for (x in 0 until size) for (y in 0 until size) setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        }
    }.getOrNull()
}

@Composable
internal fun ChannelPairPanel(profile: String, agentId: String, platform: String, label: String, onLinked: () -> Unit) {
    val context = LocalContext.current
    val graph = context.graph
    val scope = rememberCoroutineScope()
    val t = LocalTokens.current
    var choice by remember { mutableStateOf<ChannelLoginRequest.Mode?>(null) }
    var mode by remember { mutableStateOf<ChannelLoginRequest.Mode?>(null) }
    var jobId by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var error by remember { mutableStateOf<HubError?>(null) }
    var attempt by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    fun start(chosen: ChannelLoginRequest.Mode) {
        mode = chosen
        job = null
        jobId = null
        error = null
        attempt++
        scope.launch {
            val s = graph.store.current ?: return@launch
            hubCall { graph.apis(s).agents.agentsLoginChannel(profile, agentId, platform, ChannelLoginRequest(chosen)) }
                .onSuccess { jobId = it.jobId }.onFailure { error = it as HubError }
        }
    }
    // Follow the job every second until it ends.
    LaunchedEffect(jobId, attempt) {
        val id = jobId ?: return@LaunchedEffect
        while (true) {
            val s = graph.store.current ?: break
            hubCall { graph.apis(s).jobs.jobsGet(profile, id) }.onSuccess { job = it }
            if (ChannelPairRules.finished(job)) {
                if (job?.status == JobStatus.SUCCEEDED) onLinked()
                break
            }
            // The next code or the linking wakes this at once (`/rt/jobs`); the poll is the fallback.
            hub.core.android.realtime.JobsFeed.wait(id, 1_000)
        }
    }
    // Leaving before the phone is linked stops the pairing in Hermes too.
    DisposableEffect(Unit) {
        onDispose {
            val id = jobId
            if (id != null && !ChannelPairRules.finished(job)) {
                val s = graph.store.current
                if (s != null) androidx.lifecycle.ProcessLifecycleOwner.get().lifecycleScope.launch {
                    withContext(NonCancellable) { hubCall { graph.apis(s).jobs.jobsCancel(profile, id) } }
                }
            }
        }
    }
    Column(Modifier.fillMaxWidth().testTag("channel.pair"), verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.chpair_title, label), fontSize = FontTokens.sizeLg.sp, color = t.text)
        Text(stringResource(R.string.chpair_note), fontSize = FontTokens.sizeSm.sp, color = t.textMuted)
        val chosen = mode
        if (chosen == null) {
            Text(stringResource(R.string.agents2_ch_mode_title), fontSize = FontTokens.sizeSm.sp, color = t.text, modifier = Modifier.fillMaxWidth())
            listOf(
                Triple(ChannelLoginRequest.Mode.BOT, R.string.agents2_ch_mode_bot, R.string.agents2_ch_mode_bot_hint),
                Triple(ChannelLoginRequest.Mode.SELF_MINUS_CHAT, R.string.agents2_ch_mode_self, R.string.agents2_ch_mode_self_hint),
            ).forEach { (value, title, hint) ->
                Row(
                    Modifier.fillMaxWidth().clip(ItemShape).clickable { choice = value }.padding(8.dp).testTag("channel.pair.mode.${value.value}"),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    HubRadio(choice == value)
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(title), fontSize = FontTokens.sizeMd.sp, color = t.text)
                        Text(stringResource(hint), fontSize = FontTokens.sizeXs.sp, color = t.textMuted)
                    }
                }
            }
            HubButton(
                stringResource(R.string.chpair_continue), { choice?.let(::start) }, size = ControlSize.Md, enabled = choice != null,
                modifier = Modifier.testTag("channel.pair.continue"),
            )
        } else {
            val state = ChannelPairRules.state(job)
            val done = ChannelPairRules.finished(job)
            ErrorNotice(error)
            if (error == null && !done && state.qr == null) Box(Modifier.size(220.dp), contentAlignment = Alignment.Center) { Spinner(24.dp, t.textMuted) }
            if (!done) state.qr?.let { code ->
                val bitmap = remember(code) { ChannelPairRules.qr(code) }
                if (bitmap != null) {
                    Box(Modifier.background(Color.White).padding(12.dp).testTag("channel.pair.qr")) {
                        Image(bitmap.asImageBitmap(), stringResource(R.string.chpair_qr), Modifier.size(240.dp))
                    }
                }
                Text(stringResource(R.string.chpair_other_phone), fontSize = FontTokens.sizeXs.sp, color = t.textMuted)
            }
            if (!done) job?.progress?.message?.let { Text(it, fontSize = FontTokens.sizeSm.sp, color = t.text) }
            if (!done) state.expiresAt?.let { Text(stringResource(R.string.chpair_expires, FormRules.dateText(it)), fontSize = FontTokens.sizeXs.sp, color = t.textMuted) }
            if (!done && chosen != ChannelLoginRequest.Mode.SELF_MINUS_CHAT) Notice(stringResource(R.string.chpair_personal_warning), Tone.WARNING)
            when (job?.status) {
                JobStatus.SUCCEEDED -> {
                    val account = ChannelPairRules.account(state)
                    val text = when (ChannelPairRules.doneKey(state, chosen)) {
                        "done_as_self_now" -> stringResource(R.string.chpair_done_as_self_now, account)
                        "done_self_now" -> stringResource(R.string.chpair_done_self_now)
                        "done_as_now" -> stringResource(R.string.chpair_done_as_now, account)
                        "done_now" -> stringResource(R.string.chpair_done_now)
                        "done_as" -> stringResource(R.string.chpair_done_as, account)
                        else -> stringResource(R.string.chpair_done)
                    }
                    Notice(text, Tone.SUCCESS, Modifier.testTag("channel.pair.done"))
                }
                JobStatus.FAILED -> Notice(job?.error?.error ?: stringResource(R.string.chpair_failed), Tone.DANGER, Modifier.testTag("channel.pair.failed"))
                JobStatus.CANCELLED -> Notice(stringResource(R.string.chpair_cancelled), Tone.INFO)
                else -> Unit
            }
            if (error != null || job?.status == JobStatus.FAILED || job?.status == JobStatus.CANCELLED) {
                HubButton(stringResource(R.string.chpair_again), { start(chosen) }, kind = ButtonKind.Secondary, size = ControlSize.Md, icon = Lucide.RotateCw)
            }
        }
    }
}
