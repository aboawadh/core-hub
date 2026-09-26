package hub.core.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import hub.core.android.R
import hub.core.android.data.hubCall
import hub.core.android.graph
import hub.core.android.nav.Route
import hub.core.android.ui.components.LoadView
import hub.core.android.ui.components.rememberLoad
import hub.core.android.ui.kit.ButtonKind
import hub.core.android.ui.kit.ControlSize
import hub.core.android.ui.kit.EmptyState
import hub.core.android.ui.kit.GroupedList
import hub.core.android.ui.kit.HubButton
import hub.core.android.ui.kit.Item
import hub.core.android.ui.kit.Lucide
import hub.core.android.ui.kit.SectionTitle
import hub.core.android.ui.kit.Segment
import hub.core.android.ui.kit.Segmented
import hub.core.android.ui.kit.StatusDot
import hub.core.android.ui.theme.LocalTokens
import hub.core.client.model.NotifyMarkAllReadRequest
import hub.core.client.model.NotifyUpdateNoticeRequest
import hub.core.client.model.ResourceRef
import kotlinx.coroutines.launch

/** The notifications inbox: newest first, unread ones marked with a dot; a tap marks one read and opens what it is about. */
@Composable
private fun NotificationsPage(onOpen: (Route) -> Unit, profile: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val t = LocalTokens.current
    // The inbox, and the settings table beside it (which notice comes in the app and as a push).
    var settings by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    val notices = rememberLoad { context.graph.apis(context.graph.store.current!!).notify.notifyListNotices(limit = 100).items }
    LazyColumn(Modifier.fillMaxSize().testTag("notices.list"), contentPadding = settingsPagePadding, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Segmented(
                listOf(Segment(false, stringResource(R.string.notify_inbox), tag = "notices.tab.inbox"), Segment(true, stringResource(R.string.notify_settings), tag = "notices.tab.settings")),
                settings, { settings = it }, Modifier.fillMaxWidth(), size = ControlSize.Sm,
            )
        }
        if (settings) {
            item { NotificationSettings(profile) }
            return@LazyColumn
        }
        // Whether this phone can show them at all comes first.
        item { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { hub.core.android.phone.NotificationRows() } }
        item {
            SectionTitle(term("notifications")) {
                HubButton(
                    stringResource(R.string.notices_mark_all), {
                        scope.launch {
                            hubCall { context.graph.apis(context.graph.store.current!!).notify.notifyMarkAllRead(NotifyMarkAllReadRequest()) }
                            notices.reload()
                        }
                    },
                    kind = ButtonKind.Ghost, size = ControlSize.Sm, icon = Lucide.Check,
                )
            }
        }
        item {
            LoadView(notices) { list ->
                if (list.isEmpty()) EmptyState(stringResource(R.string.notices_empty), icon = Lucide.BellOff)
                else GroupedList {
                    list.forEach { notice ->
                        Item(
                            notice.title,
                            subtitle = listOfNotNull(notice.body, localTime(notice.createdAt)).joinToString(" · "),
                            tag = "notice.${notice.id}",
                            trailing = {
                                // Unread is a dot, not a word (its word is for TalkBack).
                                if (notice.readAt == null) StatusDot(t.accent, stringResource(R.string.notices_new))
                            },
                            onClick = {
                                scope.launch {
                                    hubCall { context.graph.apis(context.graph.store.current!!).notify.notifyUpdateNotice(notice.id, NotifyUpdateNoticeRequest(read = true)) }
                                    notices.reload()
                                }
                                NoticeLinks.route(notice.resource, notice.profile)?.let(onOpen)
                            },
                        )
                    }
                }
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
