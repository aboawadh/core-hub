package hub.core.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hub.core.android.R
import hub.core.android.data.HubError
import hub.core.android.generated.FontTokens
import hub.core.android.graph
import hub.core.android.nav.Route
import hub.core.android.ui.components.ErrorNotice
import hub.core.android.ui.kit.ButtonKind
import hub.core.android.ui.kit.ControlSize
import hub.core.android.ui.kit.EmptyState
import hub.core.android.ui.kit.GroupedList
import hub.core.android.ui.kit.HubButton
import hub.core.android.ui.kit.HubIconButton
import hub.core.android.ui.kit.Item
import hub.core.android.ui.kit.Lucide
import hub.core.android.ui.kit.Segment
import hub.core.android.ui.kit.Segmented
import hub.core.android.ui.kit.Spinner
import hub.core.android.ui.kit.StatusDot
import hub.core.android.ui.theme.LocalTokens
import hub.core.client.model.Notice
import hub.core.client.model.ResourceRef
import kotlinx.coroutines.launch

/**
 * Settings → Notifications: the inbox — All / Unread, newest first, unread ones marked with a dot; a
 * tap marks one read and opens what it is about, the button beside it reads or unreads it, «Mark all
 * read» empties the count — and, beside it, the notification settings. iOS's NotificationsPage.swift
 * is the twin.
 */
@Composable
private fun NotificationsPage(onOpen: (Route) -> Unit, profile: String) {
    val context = LocalContext.current
    val graph = context.graph
    val scope = rememberCoroutineScope()
    val t = LocalTokens.current
    val ops = remember { OwnSettingsOps { graph.apis(graph.store.current!!) } }
    // The inbox, and the settings table beside it (which notice comes in the app and as a push).
    var settings by rememberSaveable { mutableStateOf(false) }
    var unreadOnly by rememberSaveable { mutableStateOf(false) }
    var inbox by remember { mutableStateOf<OwnSettingsRules.Inbox?>(null) }
    var next by remember { mutableStateOf<String?>(null) }
    var loadingMore by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<HubError?>(null) }

    LaunchedEffect(unreadOnly) {
        ops.notices(unreadOnly)
            .onSuccess { page -> inbox = OwnSettingsRules.Inbox(page.items, page.unreadCount); next = page.nextCursor; error = null }
            .onFailure { error = it as HubError; if (inbox == null) inbox = OwnSettingsRules.Inbox(emptyList(), 0) }
    }

    fun set(notice: Notice, read: Boolean) {
        val now = inbox ?: return
        val marked = OwnSettingsRules.marking(now, notice.id, read)
        if (marked == now) return
        inbox = marked
        scope.launch {
            ops.mark(notice.id, read).onFailure { refused ->
                // Put back what the hub has, and keep saying why.
                ops.notices(unreadOnly).onSuccess { page -> inbox = OwnSettingsRules.Inbox(page.items, page.unreadCount); next = page.nextCursor }
                error = refused as HubError
            }
        }
    }

    LazyColumn(Modifier.fillMaxSize().testTag("notices.list"), contentPadding = settingsPagePadding, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Segmented(
                listOf(Segment(false, stringResource(R.string.notify_inbox), tag = "notices.tab.inbox"), Segment(true, stringResource(R.string.notify_settings), tag = "notices.tab.settings")),
                settings, { settings = it }, Modifier.fillMaxWidth(), size = ControlSize.Sm,
            )
        }
        if (settings) {
            // Whether this phone can show them at all comes first.
            item { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { hub.core.android.phone.NotificationRows() } }
            item { NotificationSettings(profile) }
            return@LazyColumn
        }
        item {
            Segmented(
                listOf(
                    Segment(false, stringResource(R.string.own_settings_inbox_all), tag = "notices.filter.all"),
                    Segment(true, stringResource(R.string.own_settings_inbox_unread), tag = "notices.filter.unread"),
                ),
                unreadOnly, { unreadOnly = it }, Modifier.fillMaxWidth(), size = ControlSize.Sm,
            )
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val unread = inbox?.unread ?: 0
                if (unread > 0) {
                    Text(
                        stringResource(R.string.own_settings_inbox_unread_count, unread.toString()),
                        fontSize = FontTokens.sizeXs.sp, fontWeight = FontWeight.Medium, color = t.accent,
                        modifier = Modifier.weight(1f).testTag("notices.unread"),
                    )
                } else {
                    Row(Modifier.weight(1f)) {}
                }
                HubButton(
                    stringResource(R.string.own_settings_inbox_mark_all), {
                        scope.launch {
                            ops.markAll()
                                .onSuccess {
                                    inbox = inbox?.let { OwnSettingsRules.Inbox(OwnSettingsRules.allRead(it.notices, unreadOnly), 0) }
                                    if (unreadOnly) next = null
                                    error = null
                                }
                                .onFailure { error = it as HubError }
                        }
                    },
                    kind = ButtonKind.Ghost, size = ControlSize.Sm, icon = Lucide.Check, enabled = unread > 0,
                    modifier = Modifier.testTag("notices.mark_all"),
                )
            }
        }
        error?.let { item { ErrorNotice(it) } }
        val current = inbox
        item {
            when {
                current == null -> Spinner()
                current.notices.isEmpty() -> EmptyState(
                    stringResource(if (unreadOnly) R.string.own_settings_inbox_none_unread else R.string.own_settings_inbox_none),
                    Modifier.testTag("notices.empty"), body = stringResource(R.string.own_settings_inbox_none_body), icon = Lucide.Bell,
                )
                else -> GroupedList {
                    current.notices.forEach { notice ->
                        val unread = notice.readAt == null
                        Item(
                            notice.title,
                            subtitle = listOfNotNull(notice.body, localTime(notice.createdAt)).joinToString(" · "),
                            tag = "notice.${notice.id}",
                            trailing = {
                                // Unread is a dot, not a word (its word is for TalkBack).
                                if (unread) StatusDot(t.accent, stringResource(R.string.own_settings_inbox_is_unread))
                                HubIconButton(
                                    if (unread) Lucide.Check else Lucide.Mail,
                                    stringResource(if (unread) R.string.own_settings_inbox_mark_read else R.string.own_settings_inbox_mark_unread),
                                    { set(notice, read = unread) },
                                    modifier = Modifier.testTag("notice.${notice.id}.toggle"), size = 32.dp, iconSize = 16.dp,
                                )
                            },
                            onClick = {
                                if (unread) set(notice, read = true)
                                NoticeLinks.route(notice.resource, notice.profile)?.let(onOpen)
                            },
                        )
                    }
                }
            }
        }
        if (next != null) {
            item {
                HubButton(
                    stringResource(R.string.kit_load_more), {
                        val cursor = next ?: return@HubButton
                        loadingMore = true
                        scope.launch {
                            ops.notices(unreadOnly, cursor).onSuccess { page ->
                                val known = inbox?.notices.orEmpty()
                                val ids = known.map { it.id }.toSet()
                                inbox = OwnSettingsRules.Inbox(known + page.items.filterNot { it.id in ids }, page.unreadCount)
                                next = page.nextCursor
                            }.onFailure { error = it as HubError }
                            loadingMore = false
                        }
                    },
                    kind = ButtonKind.Secondary, size = ControlSize.Md, loading = loadingMore, fill = true,
                    modifier = Modifier.fillMaxWidth().testTag("notices.more"),
                )
            }
        }
    }
}

/** Where a notice leads: its conversation, the board, or Schedules. */
object NoticeLinks {
    fun route(resource: ResourceRef?, profile: String?): Route? = when (resource?.kind) {
        ResourceRef.Kind.SESSION -> profile?.let { Route.Chat(resource.id, it) }
        ResourceRef.Kind.TASK, ResourceRef.Kind.PROJECT -> Route.Tasks
        ResourceRef.Kind.SCHEDULE, ResourceRef.Kind.SCHEDULE_RUN, ResourceRef.Kind.WORKFLOW_RUN -> Route.Schedules
        else -> null
    }
}

internal val notificationsPage = SettingsPageEntry("notifications") { NotificationsPage(it.onOpen, it.session.profile) }
