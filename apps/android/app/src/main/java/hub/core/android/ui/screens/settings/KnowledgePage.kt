package hub.core.android.ui.screens

import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hub.core.android.R
import hub.core.android.generated.FontTokens
import hub.core.android.graph
import hub.core.android.ui.components.ListFilter
import hub.core.android.ui.components.ListScaffold
import hub.core.android.ui.components.rememberPagedList
import hub.core.android.ui.kit.Badge
import hub.core.android.ui.kit.BadgeTone
import hub.core.android.ui.kit.HubCard
import hub.core.android.ui.kit.Lucide
import hub.core.android.ui.theme.LocalTokens
import hub.core.client.model.KnowledgeItem
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * Settings → Knowledge (the web's KnowledgeTab): the profile's journal, notes and files in one list,
 * newest first, with the kind as chips and a search. Nothing is written here: the rows come from
 * conversations and agents (the contract has no create, upload or delete for them). iOS's
 * KnowledgePage.swift is the twin.
 */
@Composable
private fun KnowledgePage(profile: String) {
    val ops = rememberHubDataOps(profile)
    var kind by rememberSaveable(profile) { mutableStateOf<String?>(null) }
    var typed by rememberSaveable(profile) { mutableStateOf("") }
    var query by remember(profile) { mutableStateOf(typed) }
    // The hub is asked once the typing pauses, not at every letter.
    LaunchedEffect(typed) {
        if (typed != query) delay(300)
        query = typed
    }
    val list = rememberPagedList<KnowledgeItem>(profile, kind, KnowledgeRules.query(query), key = { it.id }) { cursor ->
        ops.knowledge(kind, query, cursor).getOrThrow()
    }
    val labels = knowledgeKindLabels()
    var reading by remember { mutableStateOf<KnowledgeItem?>(null) }
    ListScaffold(
        list, key = { it.id },
        query = typed, onQuery = { typed = it }, searchHint = stringResource(R.string.knowledge_search),
        filters = KnowledgeRules.KINDS.map { ListFilter(it, labels.getValue(it)) }, filter = kind, onFilter = { kind = it },
        emptyTitle = stringResource(R.string.knowledge_empty), emptyBody = stringResource(R.string.knowledge_empty_body), emptyIcon = Lucide.BookOpen,
        header = {
            Text(stringResource(R.string.knowledge_intro), fontSize = FontTokens.sizeXs.sp, color = LocalTokens.current.textMuted)
        },
        tag = "knowledge.list",
    ) { item -> KnowledgeRow(item, labels[item.kind.value] ?: item.kind.value) { reading = item } }
    // A tap reads the whole entry (the row shows three lines); the contract lists them read-only.
    reading?.let { item ->
        hub.core.android.ui.kit.HubSheet({ reading = null }, Modifier.testTag("knowledge.read"), title = item.title ?: stringResource(R.string.knowledge_untitled)) {
            Text(
                "${labels[item.kind.value] ?: item.kind.value} · ${KnowledgeRules.day(item).format(dayFormat)}",
                fontSize = FontTokens.sizeXs.sp, color = LocalTokens.current.textMuted,
            )
            androidx.compose.foundation.layout.Column(
                Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item.content?.takeIf { it.isNotBlank() }?.let { hub.core.android.ui.components.MarkdownView(it) }
                if (item.tags.isNotEmpty()) Text(item.tags.joinToString(" · "), fontSize = FontTokens.sizeXs.sp, color = LocalTokens.current.textMuted)
            }
        }
    }
}

@Composable
internal fun knowledgeKindLabels(): Map<String?, String> = mapOf(
    null to stringResource(R.string.knowledge_filter_all), "journal" to stringResource(R.string.knowledge_filter_journal),
    "note" to stringResource(R.string.knowledge_filter_note), "file" to stringResource(R.string.knowledge_filter_file),
)

private val dayFormat: DateTimeFormatter get() = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault())

/** One row: the kind, the title, the day, the text (three lines) and its tags. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun KnowledgeRow(item: KnowledgeItem, kindLabel: String, onOpen: (() -> Unit)? = null) {
    val t = LocalTokens.current
    HubCard(Modifier.testTag("knowledge.item.${item.id}"), onClick = onOpen, padding = 12.dp) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Badge(
                kindLabel,
                tone = when (item.kind) {
                    KnowledgeItem.Kind.FILE -> BadgeTone.Info
                    KnowledgeItem.Kind.JOURNAL -> BadgeTone.Accent
                    else -> BadgeTone.Neutral
                },
            )
            Text(
                item.title ?: stringResource(R.string.knowledge_untitled), Modifier.weight(1f),
                fontSize = FontTokens.sizeSm.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Text(KnowledgeRules.day(item).format(dayFormat), fontSize = FontTokens.sizeXs.sp, color = t.textMuted)
        }
        item.content?.takeIf { it.isNotBlank() }?.let {
            Text(it, fontSize = FontTokens.sizeXs.sp, color = t.textMuted, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        if (item.tags.isNotEmpty() || (item.attachmentIds.isNotEmpty() && item.kind != KnowledgeItem.Kind.FILE)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                item.tags.forEach { Badge(it) }
                if (item.attachmentIds.isNotEmpty() && item.kind != KnowledgeItem.Kind.FILE) {
                    Badge(stringResource(R.string.knowledge_files_count, item.attachmentIds.size), tone = BadgeTone.Info)
                }
            }
        }
    }
}

/** The four pages' calls, in [profile] (HubDataKit.kt). */
@Composable
internal fun rememberHubDataOps(profile: String): HubDataOps {
    val graph = LocalContext.current.graph
    val hub = graph.store.current?.hub.orEmpty()
    return remember(profile, hub) {
        val apis by lazy { HubDataApis(hub, graph.http.authed) }
        HubDataOps(profile) { apis }
    }
}

internal val knowledgePage = SettingsPageEntry("knowledge") { KnowledgePage(it.session.profile) }
