package hub.core.android.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hub.core.android.R
import hub.core.android.data.HubError
import hub.core.android.generated.FontTokens
import hub.core.android.ui.kit.ButtonKind
import hub.core.android.ui.kit.ControlSize
import hub.core.android.ui.kit.HubButton
import hub.core.android.ui.kit.HubDialog
import hub.core.android.ui.theme.LocalTokens
import kotlinx.coroutines.launch

/**
 * «Delete this?» before anything is deleted (docs/clients/phone-pages.md): a page keeps one of these,
 * calls [ask] from its Delete action (a swipe, a menu item), and draws [ConfirmDeleteDialog].
 */
@Stable
class ConfirmDelete<T> {
    var pending by mutableStateOf<T?>(null); internal set
    fun ask(item: T) { pending = item }
    fun dismiss() { pending = null }
}

@Composable
fun <T> rememberConfirmDelete(): ConfirmDelete<T> = remember { ConfirmDelete() }

/**
 * Asks while [state] holds an item: [title] names it («Delete “Daily report”?»), [body] says what
 * follows (by default that it cannot be undone). Delete runs [onDelete]; a refusal stays in the
 * dialog in the hub's words, a success closes it and calls [onDeleted]. The button is tagged
 * `dialog.confirm`, as [hub.core.android.ui.kit.ConfirmDialog]'s.
 */
@Composable
fun <T> ConfirmDeleteDialog(
    state: ConfirmDelete<T>,
    title: @Composable (T) -> String,
    onDelete: suspend (T) -> Result<*>,
    onDeleted: (T) -> Unit = {},
    body: String? = stringResource(R.string.kit_delete_body),
    confirm: String = stringResource(R.string.kit_delete),
) {
    val item = state.pending ?: return
    val scope = rememberCoroutineScope()
    var busy by remember(item) { mutableStateOf(false) }
    var error by remember(item) { mutableStateOf<HubError?>(null) }
    HubDialog({ if (!busy) state.dismiss() }, title(item)) {
        if (body != null) Text(body, fontSize = FontTokens.sizeSm.sp, color = LocalTokens.current.textMuted)
        ErrorNotice(error)
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            HubButton(stringResource(R.string.cancel), { state.dismiss() }, kind = ButtonKind.Secondary, size = ControlSize.Md, enabled = !busy)
            HubButton(
                confirm, {
                    busy = true
                    scope.launch {
                        onDelete(item)
                            .onSuccess { state.dismiss(); onDeleted(item) }
                            .onFailure { error = it as? HubError ?: HubError(-1, null, it.message) }
                        busy = false
                    }
                },
                kind = ButtonKind.Danger, size = ControlSize.Md, loading = busy, modifier = Modifier.testTag("dialog.confirm"),
            )
        }
    }
}

/** The usual title: «Delete “name”?». */
@Composable
fun deleteTitle(name: String): String = stringResource(R.string.kit_delete_confirm, "“$name”")
