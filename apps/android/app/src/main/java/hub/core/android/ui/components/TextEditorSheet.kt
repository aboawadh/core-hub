package hub.core.android.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hub.core.android.R
import hub.core.android.data.HubError
import hub.core.android.generated.FontTokens
import hub.core.android.ui.kit.BadgeTone
import hub.core.android.ui.kit.ButtonKind
import hub.core.android.ui.kit.ControlSize
import hub.core.android.ui.kit.HubButton
import hub.core.android.ui.kit.HubSheet
import hub.core.android.ui.kit.HubTextField
import hub.core.android.ui.kit.Lucide
import hub.core.android.ui.kit.NoticeBox
import hub.core.android.ui.kit.Segment
import hub.core.android.ui.kit.Segmented
import hub.core.android.ui.theme.LocalTokens
import kotlinx.coroutines.launch

/*
 * The text/Markdown editor (docs/clients/phone-pages.md), taken from the agents' config files
 * page: skills, memory, room memory and profile files edit their text the same way.
 */

/**
 * The editing field of a document: Markdown in the reading font, in its own direction; anything
 * else (JSON, TOML, YAML, code) left to right in monospace.
 */
@Composable
fun DocumentField(
    text: String,
    onText: (String) -> Unit,
    markdown: Boolean,
    modifier: Modifier = Modifier,
    tag: String = "editor.text",
    minLines: Int = 12,
    maxLines: Int = 40,
) {
    HubTextField(
        text, onText, modifier, singleLine = false, minLines = minLines, maxLines = maxLines,
        mono = !markdown, size = ControlSize.Md, fieldTag = tag,
    )
}

/** Whether a save was refused because the text changed elsewhere since it was read (`409 changed`). */
fun changedElsewhere(error: Throwable?): Boolean = error is HubError && error.status == 409 && error.code == "changed"

/**
 * A sheet that edits [initial] and saves it with [onSave] (which sends the revision it read, so a
 * change made elsewhere is refused, not overwritten). Markdown gets an Edit/Preview switch. When a
 * save is refused as changed elsewhere, [onReload] (if given) reads the text again. Tags:
 * `<tag>.text`, `<tag>.save`, `<tag>.preview`.
 */
@Composable
fun TextEditorSheet(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onSave: suspend (String) -> Result<*>,
    markdown: Boolean = true,
    subtitle: String? = null,
    onReload: (() -> Unit)? = null,
    tag: String = "editor",
) {
    HubSheet(onDismiss = onDismiss, title = title) {
        TextEditorBody(initial, onDismiss, onSave, markdown, subtitle, onReload, tag)
    }
}

@Composable
fun TextEditorBody(
    initial: String,
    onDone: () -> Unit,
    onSave: suspend (String) -> Result<*>,
    markdown: Boolean = true,
    subtitle: String? = null,
    onReload: (() -> Unit)? = null,
    tag: String = "editor",
) {
    val t = LocalTokens.current
    val scope = rememberCoroutineScope()
    var text by remember(initial) { mutableStateOf(initial) }
    var preview by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        subtitle?.let { Text(it, fontSize = FontTokens.sizeXs.sp, color = t.textMuted) }
        error?.let { e ->
            if (changedElsewhere(e)) {
                NoticeBox(stringResource(R.string.kit_changed_elsewhere), BadgeTone.Danger) {
                    if (onReload != null) HubButton(stringResource(R.string.kit_reload), { error = null; onReload() }, kind = ButtonKind.Secondary, size = ControlSize.Sm)
                }
            } else if (e is HubError) ErrorNotice(e) else Notice(e.message ?: "—")
        }
        if (markdown) {
            Segmented(
                listOf(Segment(false, stringResource(R.string.kit_edit)), Segment(true, stringResource(R.string.kit_preview), tag = "$tag.preview")),
                preview, { preview = it }, Modifier.fillMaxWidth(), size = ControlSize.Sm,
            )
        }
        if (preview) {
            Column(Modifier.fillMaxWidth().heightIn(min = 200.dp, max = 480.dp).verticalScroll(rememberScrollState())) {
                InContentDirection(text) { MarkdownView(text) }
            }
        } else {
            DocumentField(text, { text = it }, markdown, Modifier.heightIn(min = 200.dp), tag = "$tag.text", minLines = 8, maxLines = 16)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HubButton(
                stringResource(R.string.save), {
                    saving = true
                    scope.launch {
                        onSave(text).onSuccess { error = null; onDone() }.onFailure { error = it }
                        saving = false
                    }
                },
                size = ControlSize.Md, icon = Lucide.Check, loading = saving, enabled = text != initial, modifier = Modifier.testTag("$tag.save"),
            )
            HubButton(stringResource(R.string.kit_revert), { text = initial }, kind = ButtonKind.Ghost, size = ControlSize.Md, enabled = text != initial)
        }
    }
}
