package hub.core.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hub.core.android.R
import hub.core.android.data.HubError
import hub.core.android.data.hubCall
import hub.core.android.generated.FontTokens
import hub.core.android.graph
import hub.core.android.terminal.TerminalEvent
import hub.core.android.terminal.TerminalKeys
import hub.core.android.terminal.TerminalLink
import hub.core.android.terminal.TerminalScreen
import hub.core.android.ui.components.ErrorNotice
import hub.core.android.ui.components.Notice
import hub.core.android.ui.components.Tone
import hub.core.android.ui.kit.ButtonKind
import hub.core.android.ui.kit.Chip
import hub.core.android.ui.kit.ControlSize
import hub.core.android.ui.kit.EmptyState
import hub.core.android.ui.kit.HubButton
import hub.core.android.ui.kit.HubIconButton
import hub.core.android.ui.kit.HubTextField
import hub.core.android.ui.kit.ItemShape
import hub.core.android.ui.kit.Lucide
import hub.core.android.ui.kit.Spinner
import hub.core.android.ui.theme.LocalTokens
import hub.core.client.api.TerminalApi
import hub.core.client.model.TerminalStatus
import kotlinx.coroutines.launch

/*
 * Settings → Terminal (DECISIONS §70; the web's TerminalTool): a shell on the hub's host, for the
 * owner only, when the hub runs with COREHUB_WEB_TERMINAL=1 — the entry shows only when
 * `GET /terminal` answers 200. Several terminals as chips; each is a session on the hub, so leaving
 * the page and coming back attaches again and repaints from what the hub kept. The phone types a
 * line at a time (Enter sends it), with the keys a phone keyboard lacks (Tab, arrows, Esc, Ctrl-C,
 * Ctrl-D) and Paste; the screen is a small emulator (terminal/TerminalScreen.kt). The warning is
 * always on the page: what runs here runs on the server with the hub account's permissions.
 * Native since 2026-09-27 (it was web-only).
 */

/** Whether the owner's Terminal row shows: the hub answered `GET /terminal` with 200. */
object TerminalAvailability {
    private val known = mutableMapOf<String, Boolean>()

    fun cached(hub: String): Boolean = known[hub] == true

    suspend fun check(hub: String, api: TerminalApi): Boolean {
        val on = hubCall { api.terminalGet() }.isSuccess
        known[hub] = on
        return on
    }
}

private class Tab(val id: String, val n: Int, screen: TerminalScreen) {
    var screen by mutableStateOf(screen)
    var ended by mutableStateOf<String?>(null)
    var version by androidx.compose.runtime.mutableLongStateOf(0L)
}

@Composable
private fun reasonText(reason: String): String = stringResource(
    when (reason) {
        "closed" -> R.string.terminal_reason_closed
        "idle" -> R.string.terminal_reason_idle
        "shutdown" -> R.string.terminal_reason_shutdown
        "gone" -> R.string.terminal_gone
        else -> R.string.terminal_reason_exited
    },
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TerminalPage(profile: String) {
    val context = LocalContext.current
    val graph = context.graph
    val t = LocalTokens.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val session = graph.store.current ?: return
    var status by remember { mutableStateOf<TerminalStatus?>(null) }
    var statusError by remember { mutableStateOf<HubError?>(null) }
    LaunchedEffect(Unit) {
        hubCall { TerminalApi(hub.core.android.data.apiBase(session.hub), graph.http.authed).terminalGet() }
            .onSuccess { status = it }.onFailure { statusError = it as HubError }
    }
    val st = status
    if (st == null) {
        if (statusError == null) Spinner(20.dp, t.textMuted, Modifier.padding(16.dp))
        else if (statusError?.status == 403) EmptyState(stringResource(R.string.terminal_disabled), body = stringResource(R.string.terminal_disabled_body), icon = Lucide.SquareTerminal)
        else ErrorNotice(statusError, Modifier.padding(16.dp))
        return
    }
    val link = remember(session.hub, profile) { TerminalLink(session.hub, session.accessToken, profile, graph.http.plain) }
    val tabs = remember { mutableStateListOf<Tab>() }
    var active by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var size by remember { mutableStateOf(80 to 24) }
    val connected by link.connected.collectAsState()
    DisposableEffect(link) {
        link.connect()
        onDispose { link.disconnect() }
    }
    // Output and ends, to the tab they belong to.
    LaunchedEffect(link) {
        link.events.collect { e ->
            val tab = tabs.firstOrNull { it.id == e.id } ?: return@collect
            when (e) {
                is TerminalEvent.Output -> { tab.screen.feed(e.data); tab.version = tab.screen.version }
                is TerminalEvent.Exited -> tab.ended = e.reason
            }
        }
    }
    // Once connected: attach to what is live (a return to the page repaints it from the backlog).
    LaunchedEffect(connected) {
        if (!connected) return@LaunchedEffect
        // A socket that came back hears a session's output only once attached again: repaint from the backlog.
        tabs.filter { it.ended == null }.forEach { tab ->
            val ack = link.attach(tab.id)
            if (ack.ok) {
                tab.screen = TerminalScreen(size.first, size.second).also { screen -> ack.backlog?.let(screen::feed) }
                tab.version = tab.screen.version + 1
            } else if (ack.code == "not_found") tab.ended = "gone"
        }
        st.sessions.forEach { live ->
            if (tabs.any { it.id == live.id }) return@forEach
            val ack = link.attach(live.id)
            if (ack.ok) {
                val tab = Tab(live.id, (tabs.maxOfOrNull { it.n } ?: 0) + 1, TerminalScreen(size.first, size.second))
                ack.backlog?.let { tab.screen.feed(it) }
                tab.version = tab.screen.version
                tabs += tab
                if (active == null) active = tab.id
                link.resize(tab.id, size.first, size.second)
            }
        }
    }
    fun openNew() {
        scope.launch {
            val ack = link.open(size.first, size.second)
            val s = ack.session
            if (ack.ok && s != null) {
                val tab = Tab(s.id, (tabs.maxOfOrNull { it.n } ?: 0) + 1, TerminalScreen(size.first, size.second))
                tabs += tab
                active = tab.id
                error = null
            } else {
                error = if (ack.reason == "terminal_limit") "limit" else (ack.error ?: ack.code ?: "")
            }
        }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp).testTag("terminal.page"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Notice(stringResource(R.string.terminal_warning), Tone.WARNING)
        Text(stringResource(R.string.terminal_warning_detail), fontSize = FontTokens.sizeXs.sp, color = t.textMuted)
        if (!st.pty) Notice(stringResource(R.string.terminal_no_pty), Tone.INFO)
        Text(
            stringResource(R.string.terminal_idle, (st.idleTimeoutSeconds / 60).toString(), st.maxSessions.toString()),
            fontSize = FontTokens.sizeXs.sp, color = t.textMuted,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            tabs.forEach { tab ->
                Chip(stringResource(R.string.terminal_tab, tab.n.toString()), active == tab.id, { active = tab.id }, size = ControlSize.Sm, modifier = Modifier.testTag("terminal.tab.${tab.n}"))
            }
            HubButton(
                stringResource(R.string.terminal_new), ::openNew, kind = ButtonKind.Secondary, size = ControlSize.Sm, icon = Lucide.Plus,
                enabled = connected, modifier = Modifier.testTag("terminal.new"),
            )
        }
        error?.let {
            Notice(if (it == "limit") stringResource(R.string.terminal_limit, st.maxSessions.toString()) else it, Tone.DANGER)
        }
        if (!connected) Text(stringResource(R.string.terminal_connecting), fontSize = FontTokens.sizeXs.sp, color = t.textMuted)
        val tab = tabs.firstOrNull { it.id == active }
        if (tab == null) {
            EmptyState(stringResource(R.string.terminal_empty), body = stringResource(R.string.terminal_empty_body, profile), icon = Lucide.SquareTerminal)
            return@Column
        }
        TerminalView(tab, Modifier.weight(1f)) { cols, rows ->
            if (size != cols to rows) {
                size = cols to rows
                tabs.forEach { it.screen.resize(cols, rows); link.resize(it.id, cols, rows) }
            }
        }
        tab.ended?.let { Notice(stringResource(R.string.terminal_ended, reasonText(it)), Tone.INFO) }
        var typed by remember(tab.id) { mutableStateOf("") }
        val live = tab.ended == null && connected
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            HubTextField(
                typed, { typed = it }, Modifier.weight(1f), mono = true, size = ControlSize.Md, enabled = live,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { link.input(tab.id, TerminalKeys.line(typed)); typed = "" }),
                fieldTag = "terminal.input",
            )
            HubIconButton(Lucide.Send, stringResource(R.string.terminal_send), { link.input(tab.id, TerminalKeys.line(typed)); typed = "" }, enabled = live, modifier = Modifier.testTag("terminal.send"))
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                "Tab" to TerminalKeys.TAB, "↑" to TerminalKeys.UP, "↓" to TerminalKeys.DOWN, "←" to TerminalKeys.LEFT, "→" to TerminalKeys.RIGHT,
                "Esc" to TerminalKeys.ESC, "Ctrl-C" to TerminalKeys.CTRL_C, "Ctrl-D" to TerminalKeys.CTRL_D, "Ctrl-L" to TerminalKeys.CTRL_L,
            ).forEach { (label, bytes) ->
                HubButton(label, { link.input(tab.id, bytes) }, kind = ButtonKind.Secondary, size = ControlSize.Sm, enabled = live, modifier = Modifier.testTag("terminal.key.$label"))
            }
            HubButton(
                stringResource(R.string.terminal_paste), { clipboard.getText()?.text?.let { link.input(tab.id, it) } },
                kind = ButtonKind.Secondary, size = ControlSize.Sm, icon = Lucide.ClipboardPaste, enabled = live,
            )
            HubButton(
                stringResource(R.string.terminal_copy), { clipboard.setText(androidx.compose.ui.text.AnnotatedString(tab.screen.text().joinToString("\n"))) },
                kind = ButtonKind.Ghost, size = ControlSize.Sm, icon = Lucide.Copy,
            )
            HubButton(
                stringResource(R.string.terminal_close, stringResource(R.string.terminal_tab, tab.n.toString())),
                {
                    if (tab.ended == null) link.close(tab.id)
                    tabs.remove(tab)
                    active = tabs.lastOrNull()?.id
                },
                kind = ButtonKind.Danger, size = ControlSize.Sm, icon = Lucide.X, modifier = Modifier.testTag("terminal.close"),
            )
        }
    }
}

/** The screen: monospace lines, left to right whatever the UI's direction, following the end. */
@Composable
private fun TerminalView(tab: Tab, modifier: Modifier, onSize: (cols: Int, rows: Int) -> Unit) {
    val measurer = rememberTextMeasurer()
    val style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 15.sp, color = Color(0xFFE6E6E6), textDirection = TextDirection.Ltr)
    val density = LocalDensity.current
    androidx.compose.runtime.CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        BoxWithConstraints(modifier.fillMaxWidth().clip(ItemShape).background(Color(0xFF111418)).padding(8.dp).testTag("terminal.screen")) {
            val cell = remember(style) { measurer.measure("M", style) }
            val cols = with(density) { (maxWidth.toPx() / cell.size.width).toInt() }.coerceAtLeast(20)
            val rows = with(density) { (maxHeight.toPx() / cell.size.height).toInt() }.coerceAtLeast(6)
            LaunchedEffect(cols, rows) { onSize(cols, rows) }
            val lines = remember(tab.version, tab.id) { tab.screen.text() }
            val list = rememberLazyListState()
            LaunchedEffect(lines.size, tab.version) { if (lines.isNotEmpty()) list.scrollToItem(lines.lastIndex) }
            LazyColumn(Modifier.fillMaxSize(), state = list) {
                itemsIndexed(lines) { _, line -> Text(line.ifEmpty { " " }, style = style, softWrap = false, maxLines = 1) }
            }
        }
    }
}

internal val terminalPage = SettingsPageEntry("terminal") { TerminalPage(it.session.profile) }
