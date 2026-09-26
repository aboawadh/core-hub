package hub.core.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import hub.core.android.ui.components.LoadView
import hub.core.android.ui.components.rememberLoad
import hub.core.android.ui.kit.BadgeTone
import hub.core.android.ui.kit.ButtonKind
import hub.core.android.ui.kit.ControlSize
import hub.core.android.ui.kit.EmptyState
import hub.core.android.ui.kit.HubButton
import hub.core.android.ui.components.DocumentField
import hub.core.android.ui.kit.Lucide
import hub.core.android.ui.kit.NoticeBox
import hub.core.android.ui.kit.Segment
import hub.core.android.ui.kit.Segmented
import hub.core.android.ui.theme.LocalTokens
import hub.core.client.model.Agent
import hub.core.client.model.ConfigFile
import kotlinx.coroutines.launch

/**
 * A coding agent's own config files (§78), one set for the hub: a file at a time in a plain
 * editor — Markdown in the reading font with its own direction, JSON and TOML left to right in
 * monospace — saved with its revision so a change made elsewhere is not overwritten.
 */
@Composable
private fun ConfigFilesPage(agent: Agent, profile: String) {
    val ops = rememberOps(agent, profile)
    val scope = rememberCoroutineScope()
    val t = LocalTokens.current
    val list = rememberLoad(agent.id, profile) { ops.configFiles().getOrThrow() }
    var chosen by remember { mutableStateOf<String?>(null) }
    LoadView(list) { files ->
        if (files.isEmpty()) {
            EmptyState(stringResource(R.string.agent_nothing), icon = Lucide.FileCog)
            return@LoadView
        }
        val key = chosen ?: files.first().key
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).testTag("agent.config_files"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (files.size > 1) {
                Segmented(files.map { Segment(it.key, agentText(it.label), tag = "config.tab.${it.key}") }, key, { chosen = it }, Modifier.fillMaxWidth())
            }
            val file = rememberLoad(agent.id, key) { ops.configFile(key).getOrThrow() }
            LoadView(file) { f ->
                var text by remember(f.key, f.revision) { mutableStateOf(f.content.orEmpty()) }
                var error by remember(f.key) { mutableStateOf<HubError?>(null) }
                var saved by remember(f.key) { mutableStateOf(false) }
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(f.path, fontSize = FontTokens.sizeXs.sp, color = t.textMuted)
                    NoticeBox(stringResource(R.string.config_shared), BadgeTone.Info)
                    if (!f.exists) Text(stringResource(R.string.config_new_file), fontSize = FontTokens.sizeXs.sp, color = t.textMuted)
                    error?.let { e ->
                        NoticeBox(
                            if (e.status == 409 && e.code == "changed") stringResource(R.string.config_changed) else hub.core.android.ui.components.errorText(e), BadgeTone.Danger,
                        ) {
                            if (e.status == 409) HubButton(stringResource(R.string.config_reload), { file.reload() }, kind = ButtonKind.Secondary, size = ControlSize.Sm)
                        }
                    }
                    if (saved) NoticeBox(stringResource(R.string.config_saved), BadgeTone.Success)
                    DocumentField(text, { text = it; saved = false }, markdown = f.language == ConfigFile.Language.MARKDOWN, tag = "config.editor")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        HubButton(stringResource(R.string.save), {
                            scope.launch {
                                ops.saveConfigFile(f, text).onSuccess { error = null; saved = true; file.reload() }.onFailure { error = it as HubError }
                            }
                        }, size = ControlSize.Md, icon = Lucide.Check, enabled = text != f.content.orEmpty(), modifier = Modifier.testTag("config.save"))
                        HubButton(stringResource(R.string.config_revert), { text = f.content.orEmpty() }, kind = ButtonKind.Ghost, size = ControlSize.Md, enabled = text != f.content.orEmpty())
                    }
                }
            }
        }
    }
}

internal val agentConfigFilesPage = AgentPageEntry("agent_config_files") { agent, profile -> ConfigFilesPage(agent, profile) }
