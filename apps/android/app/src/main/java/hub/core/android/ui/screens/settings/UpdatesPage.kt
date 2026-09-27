package hub.core.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hub.core.android.R
import hub.core.android.data.HubError
import hub.core.android.data.apiBase
import hub.core.android.data.hubCall
import hub.core.android.generated.FontTokens
import hub.core.android.graph
import hub.core.android.ui.components.ConfirmDeleteDialog
import hub.core.android.ui.components.ErrorNotice
import hub.core.android.ui.components.FormRules
import hub.core.android.ui.components.Load
import hub.core.android.ui.components.LoadView
import hub.core.android.ui.components.RowAction
import hub.core.android.ui.components.RowActionsButton
import hub.core.android.ui.components.rememberConfirmDelete
import hub.core.android.ui.components.rememberLoad
import hub.core.android.ui.kit.Badge
import hub.core.android.ui.kit.BadgeTone
import hub.core.android.ui.kit.ButtonKind
import hub.core.android.ui.kit.ControlSize
import hub.core.android.ui.kit.Custom
import hub.core.android.ui.kit.EmptyState
import hub.core.android.ui.kit.GroupedList
import hub.core.android.ui.kit.HubButton
import hub.core.android.ui.kit.HubCard
import hub.core.android.ui.kit.HubTextField
import hub.core.android.ui.kit.Lucide
import hub.core.android.ui.kit.SectionTitle
import hub.core.android.ui.kit.Segment
import hub.core.android.ui.kit.Segmented
import hub.core.android.ui.kit.ToggleRow
import hub.core.android.ui.theme.LocalTokens
import hub.core.client.api.UpdatesApi
import hub.core.client.model.ClientPlatform
import hub.core.client.model.Release
import hub.core.client.model.ReleaseChannel
import hub.core.client.model.UpdateSettings
import hub.core.client.model.UpdateSettingsSource
import hub.core.client.model.UpdateSettingsWrite
import hub.core.client.model.UpdateSettingsWriteSource
import kotlin.math.roundToLong
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/*
 * Settings → Updates (the web's UpdatesTab): the hub's shelf of phone and desktop builds — where
 * the shelf reads releases from (uploaded by hand, or a GitHub repository with an optional token
 * written once), the default channel, publishing to the test channel on its own, and the builds on
 * the shelf, each removable. Admins only (the destination's role); the phone's own update check is
 * This device. Native since 2026-09-27 (every section of the app works on its own).
 */

/** The calls the page makes (`updates.*`, `x-scope: global`). */
class UpdatesOps(private val api: () -> UpdatesApi) {
    suspend fun settings(): Result<UpdateSettings> = hubCall { api().updatesGetSettings() }
    suspend fun save(write: UpdateSettingsWrite): Result<UpdateSettings> = hubCall { api().updatesSetSettings(write) }
    suspend fun releases(): Result<List<Release>> = hubCall { api().updatesListReleases(limit = UpdatesRules.PAGE).items }
    suspend fun delete(id: String): Result<Unit> = hubCall { api().updatesDeleteRelease(id) }

    companion object {
        fun of(hub: String, client: OkHttpClient) = UpdatesOps { UpdatesApi(apiBase(hub), client) }
    }
}

/** The page's plain rules, checked by SelfSufficientTest. */
object UpdatesRules {
    /** The web reads the latest 50 builds. */
    const val PAGE = 50

    private val REPO = Regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")

    /** `owner/repo`, as the hub takes it. */
    fun repoValid(repo: String): Boolean = REPO.matches(repo.trim())

    fun fromSource(settings: UpdateSettings): Boolean = settings.source.kind == UpdateSettingsSource.Kind.GITHUB_RELEASE

    fun tokenStored(settings: UpdateSettings): Boolean = settings.source.token != null

    /** Switching the source; the repository and token already written are kept. */
    fun source(on: Boolean): UpdateSettingsWrite = UpdateSettingsWrite(
        source = UpdateSettingsWriteSource(kind = if (on) UpdateSettingsWriteSource.Kind.GITHUB_RELEASE else UpdateSettingsWriteSource.Kind.MANUAL),
    )

    fun repo(repo: String): UpdateSettingsWrite = UpdateSettingsWrite(
        source = UpdateSettingsWriteSource(kind = UpdateSettingsWriteSource.Kind.GITHUB_RELEASE, repo = repo.trim()),
    )

    /** A new token replaces the stored one; an empty field sends nothing. */
    fun token(token: String): UpdateSettingsWrite? = token.trim().takeIf { it.isNotEmpty() }?.let {
        UpdateSettingsWrite(source = UpdateSettingsWriteSource(kind = UpdateSettingsWriteSource.Kind.GITHUB_RELEASE, token = it))
    }

    /** Whole megabytes, Latin digits, never «0 MB» for a real file. */
    fun size(bytes: Long): String = "${(bytes / 1_048_576.0).roundToLong().coerceAtLeast(if (bytes > 0) 1 else 0)} MB"

    /** Newest first. */
    fun ordered(releases: List<Release>): List<Release> = releases.sortedByDescending { it.publishedAt }
}

@Composable
internal fun platformLabel(platform: ClientPlatform): String = stringResource(
    when (platform) {
        ClientPlatform.ANDROID -> R.string.upd_platform_android
        ClientPlatform.IOS -> R.string.upd_platform_ios
        ClientPlatform.MACOS -> R.string.upd_platform_macos
        ClientPlatform.WINDOWS -> R.string.upd_platform_windows
        ClientPlatform.LINUX -> R.string.upd_platform_linux
    },
)

@Composable
internal fun channelLabel(channel: ReleaseChannel): String =
    stringResource(if (channel == ReleaseChannel.TEST) R.string.upd_channel_test else R.string.upd_channel_stable)

@Composable
private fun rememberUpdatesOps(): UpdatesOps {
    val graph = LocalContext.current.graph
    val hub = graph.store.current?.hub.orEmpty()
    return remember(hub) { UpdatesOps.of(hub, graph.http.authed) }
}

@Composable
private fun UpdatesPage() {
    val ops = rememberUpdatesOps()
    val settings = rememberLoad("updates.settings") { ops.settings().getOrThrow() }
    val releases = rememberLoad("updates.releases") { ops.releases().getOrThrow() }
    val deleting = rememberConfirmDelete<Release>()
    LazyColumn(Modifier.testTag("updates.page"), contentPadding = settingsPagePadding, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text(stringResource(R.string.upd_intro), fontSize = FontTokens.sizeXs.sp, color = LocalTokens.current.textMuted) }
        item { LoadView(settings) { current -> UpdateSourceCard(current, ops, onSaved = settings.reload) } }
        item { SectionTitle(stringResource(R.string.upd_shelf)) }
        item {
            LoadView(releases) { list ->
                if (list.isEmpty()) {
                    EmptyState(
                        stringResource(R.string.upd_empty), Modifier.testTag("updates.empty"),
                        body = stringResource(R.string.upd_empty_body), icon = Lucide.CircleArrowDown,
                    )
                }
            }
        }
        val list = UpdatesRules.ordered((releases.state as? Load.Ready)?.value.orEmpty())
        items(list, key = { it.id }) { release -> ReleaseCard(release, onDelete = { deleting.ask(release) }) }
    }
    ConfirmDeleteDialog(
        deleting,
        title = { stringResource(R.string.upd_delete_title, it.version) },
        onDelete = { ops.delete(it.id) },
        onDeleted = { releases.reload() },
        body = stringResource(R.string.upd_delete_body),
    )
}

/** Where the shelf reads from, and its channels. Each change is saved at once, as on the web. */
@Composable
internal fun UpdateSourceCard(current: UpdateSettings, ops: UpdatesOps?, onSaved: () -> Unit) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<HubError?>(null) }
    var repo by remember(current.source.repo) { mutableStateOf(current.source.repo.orEmpty()) }
    var token by remember { mutableStateOf("") }
    fun save(write: UpdateSettingsWrite, after: () -> Unit = {}) {
        val o = ops ?: return
        busy = true
        scope.launch {
            o.save(write).onSuccess { error = null; after(); onSaved() }.onFailure { error = it as HubError }
            busy = false
        }
    }
    val fromSource = UpdatesRules.fromSource(current)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        GroupedList(Modifier.testTag("updates.source"), title = stringResource(R.string.upd_source)) {
            Custom {
                Text(stringResource(R.string.upd_default_channel), fontSize = FontTokens.sizeSm.sp, color = LocalTokens.current.textMuted)
                Segmented(
                    listOf(
                        Segment(ReleaseChannel.STABLE, stringResource(R.string.upd_channel_stable), tag = "updates.channel.stable"),
                        Segment(ReleaseChannel.TEST, stringResource(R.string.upd_channel_test), tag = "updates.channel.test"),
                    ),
                    current.defaultChannel, { if (it != current.defaultChannel) save(UpdateSettingsWrite(defaultChannel = it)) },
                    Modifier.fillMaxWidth(),
                )
            }
            Custom {
                ToggleRow(
                    stringResource(R.string.upd_from_source), fromSource, { save(UpdatesRules.source(it)) },
                    Modifier.testTag("updates.from_source"), subtitle = stringResource(R.string.upd_from_source_hint), enabled = !busy,
                )
            }
            if (fromSource) {
                Custom {
                    HubTextField(
                        repo, { repo = it }, label = stringResource(R.string.upd_repo), placeholder = "owner/repo", mono = true,
                        error = if (repo.isNotBlank() && !UpdatesRules.repoValid(repo)) stringResource(R.string.upd_repo_hint) else null,
                        size = ControlSize.Md, fieldTag = "updates.repo",
                    )
                    HubButton(
                        stringResource(R.string.upd_save), { save(UpdatesRules.repo(repo)) }, kind = ButtonKind.Secondary, size = ControlSize.Md,
                        enabled = !busy && UpdatesRules.repoValid(repo) && repo.trim() != current.source.repo,
                        modifier = Modifier.testTag("updates.repo.save"),
                    )
                }
                Custom {
                    HubTextField(
                        token, { token = it }, label = stringResource(R.string.upd_token),
                        placeholder = stringResource(if (UpdatesRules.tokenStored(current)) R.string.upd_token_stored else R.string.upd_token_hint),
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        size = ControlSize.Md, fieldTag = "updates.token",
                    )
                    HubButton(
                        stringResource(R.string.upd_save), { UpdatesRules.token(token)?.let { save(it) { token = "" } } }, kind = ButtonKind.Secondary,
                        size = ControlSize.Md, enabled = !busy && UpdatesRules.token(token) != null, modifier = Modifier.testTag("updates.token.save"),
                    )
                }
                Custom {
                    ToggleRow(
                        stringResource(R.string.upd_auto_publish), current.autoPublish, { save(UpdateSettingsWrite(autoPublish = it)) },
                        Modifier.testTag("updates.auto_publish"), subtitle = stringResource(R.string.upd_auto_publish_hint), enabled = !busy,
                    )
                }
            }
        }
        ErrorNotice(error)
    }
}

/** One build on the shelf: its version and build, where it goes, its size and day, and Delete. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReleaseCard(release: Release, onDelete: () -> Unit) {
    val t = LocalTokens.current
    HubCard(Modifier.testTag("release.${release.id}"), padding = 12.dp) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text("${release.version} (${release.build})", fontSize = FontTokens.sizeMd.sp, color = t.text)
                Text(
                    "${UpdatesRules.size(release.sizeBytes)} · ${FormRules.dateText(release.publishedAt)}",
                    fontSize = FontTokens.sizeXs.sp, color = t.textMuted,
                )
            }
            RowActionsButton(
                listOf(RowAction(stringResource(R.string.upd_delete), Lucide.Trash, danger = true, onClick = onDelete)),
                Modifier.testTag("release.${release.id}.actions"),
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Badge(platformLabel(release.platform))
            Badge(channelLabel(release.channel), tone = if (release.channel == ReleaseChannel.TEST) BadgeTone.Warning else BadgeTone.Info)
            if (release.mandatory) Badge(stringResource(R.string.upd_mandatory), tone = BadgeTone.Danger)
        }
    }
}

internal val updatesPage = SettingsPageEntry("updates") { UpdatesPage() }
