package hub.core.android.ui.screens

import androidx.compose.runtime.compositionLocalOf
import hub.core.client.model.Preferences
import hub.core.client.model.RunCreate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/*
 * The display preferences the hub keeps for the person (`auth.getPreferences`), applied on the
 * phone — not only saved from Settings → Display (they were saved and never read before
 * 2026-09-27). Text size scales every text of the app; reasoning and tool calls show or hide in a
 * reply; compact tightens the conversation; Send while the agent works follows `busy_input_mode`
 * (`RunCreate.when`, as the web sends it); and links to this hub's own pages open in the app, or
 * in the browser when the person chose the browser.
 */

/** What a conversation draws, from the preferences. */
data class ChatDisplay(
    val showReasoning: Boolean = true,
    val showToolCalls: Boolean = true,
    val compact: Boolean = false,
)

val LocalChatDisplay = compositionLocalOf { ChatDisplay() }

object HubDisplay {
    private val _prefs = MutableStateFlow<Preferences?>(null)
    /** The person's preferences as last read or saved; null until read (the defaults apply meanwhile). */
    val prefs: StateFlow<Preferences?> = _prefs.asStateFlow()

    fun set(preferences: Preferences?) {
        _prefs.value = preferences
    }

    /** Text size: the hub's 0.85–1.45, 1 when unknown. */
    fun textScale(p: Preferences?): Float = p?.textScale?.toFloat()?.coerceIn(0.85f, 1.45f) ?: 1f

    fun chat(p: Preferences?): ChatDisplay = if (p == null) ChatDisplay() else ChatDisplay(p.showReasoning, p.showToolCalls, p.compact)

    /** What Send asks of the hub while a run is going (`RunCreate.when`); queue when unknown. */
    fun busyWhen(p: Preferences?): RunCreate.When = when (p?.busyInputMode) {
        Preferences.BusyInputMode.NEXT -> RunCreate.When.NEXT
        Preferences.BusyInputMode.INTERRUPT -> RunCreate.When.INTERRUPT
        else -> RunCreate.When.QUEUE
    }

    /** Whether a link to one of this hub's pages opens inside the app (true unless the browser was chosen). */
    fun linksInApp(p: Preferences?): Boolean = p?.linkTarget != Preferences.LinkTarget.BROWSER
}
