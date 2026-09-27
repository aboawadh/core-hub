package hub.core.android.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import hub.core.android.data.hubCall
import hub.core.android.generated.FontTokens
import hub.core.android.generated.RadiusTokens
import hub.core.android.ui.kit.ButtonKind
import hub.core.android.ui.kit.Chip
import hub.core.android.ui.kit.ControlSize
import hub.core.android.ui.kit.EmptyState
import hub.core.android.ui.kit.HubButton
import hub.core.android.ui.kit.HubIconButton
import hub.core.android.ui.kit.HubMenu
import hub.core.android.ui.kit.HubTextField
import hub.core.android.ui.kit.Lucide
import hub.core.android.ui.kit.LucideIcon
import hub.core.android.ui.kit.MenuItem
import hub.core.android.ui.kit.Spinner
import hub.core.android.ui.theme.LocalTokens
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/*
 * The list scaffold every list page on the phone uses (docs/clients/phone-pages.md): an optional
 * search field and filter chips, pull to refresh, the next page loaded near the end (or by «Load
 * more»), the empty and failed states, and a row's actions by swiping or a long press.
 */

/** One page of a list answer (`{ items, next_cursor }`). */
data class ListPage<T>(val items: List<T>, val next: String?)

/**
 * A list read page by page. [refresh] starts again from the first page; [loadMore] adds the next
 * one while there is one. A failure keeps what was loaded and says why ([error]).
 */
@Stable
class PagedList<T>(
    private val scope: CoroutineScope,
    private val key: (T) -> Any,
    private val fetch: suspend (cursor: String?) -> ListPage<T>,
) {
    var items by mutableStateOf<List<T>>(emptyList()); private set
    var next by mutableStateOf<String?>(null); private set
    /** True until the first page came back (or failed). */
    var loading by mutableStateOf(true); private set
    var refreshing by mutableStateOf(false); private set
    var loadingMore by mutableStateOf(false); private set
    var error by mutableStateOf<HubError?>(null); private set
    val hasMore: Boolean get() = next != null
    private var job: Job? = null

    fun refresh() {
        job?.cancel()
        refreshing = !loading
        job = scope.launch {
            hubCall { fetch(null) }
                .onSuccess { page -> items = page.items.distinctBy(key); next = page.next; error = null }
                .onFailure { error = it as HubError }
            loading = false
            refreshing = false
        }
    }

    fun loadMore() {
        val cursor = next ?: return
        if (loadingMore || refreshing || loading) return
        loadingMore = true
        job = scope.launch {
            hubCall { fetch(cursor) }
                .onSuccess { page -> items = (items + page.items).distinctBy(key); next = page.next; error = null }
                .onFailure { error = it as HubError }
            loadingMore = false
        }
    }

    /** Takes one row out at once (after a delete), without reading the list again. */
    fun remove(item: T) {
        items = items.filter { key(it) != key(item) }
    }
}

/** A [PagedList] loaded when first drawn and again whenever [keys] change (a filter, the profile). */
@Composable
fun <T> rememberPagedList(vararg keys: Any?, key: (T) -> Any, fetch: suspend (cursor: String?) -> ListPage<T>): PagedList<T> {
    val scope = rememberCoroutineScope()
    val list = remember(*keys) { PagedList(scope, key, fetch) }
    LaunchedEffect(list) { list.refresh() }
    return list
}

/** An action on one row: from the menu of a long press, or a swipe when it is a [ListScaffold] swipe action. */
data class RowAction(val label: String, val icon: Int, val danger: Boolean = false, val onClick: () -> Unit)

/** A filter chip over the list; `null` [value] is «All». */
data class ListFilter(val value: String?, val label: String)

/**
 * The list page. [row] draws one item; [actions] are its long-press menu; [swipeAction] (usually
 * Delete, which then asks) is what a swipe from the end does. Search and filters show only when
 * given. Tags: `<tag>`, `<tag>.search`, `<tag>.filter.<value|all>`, `<tag>.more`, `<tag>.empty`.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun <T> ListScaffold(
    list: PagedList<T>,
    key: (T) -> Any,
    modifier: Modifier = Modifier,
    query: String? = null,
    onQuery: ((String) -> Unit)? = null,
    searchHint: String = stringResource(R.string.kit_search_hint),
    filters: List<ListFilter> = emptyList(),
    filter: String? = null,
    onFilter: (String?) -> Unit = {},
    emptyTitle: String = stringResource(R.string.kit_empty),
    emptyBody: String? = null,
    emptyIcon: Int = Lucide.Inbox,
    actions: ((T) -> List<RowAction>)? = null,
    swipeAction: ((T) -> RowAction?)? = null,
    header: (@Composable () -> Unit)? = null,
    tag: String = "list",
    contentPadding: PaddingValues = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
    row: @Composable (T) -> Unit,
) {
    val t = LocalTokens.current
    val pull = rememberPullToRefreshState()
    PullToRefreshBox(
        isRefreshing = list.refreshing, onRefresh = list::refresh, modifier = modifier.fillMaxSize().testTag(tag), state = pull,
        indicator = {
            PullToRefreshDefaults.Indicator(pull, list.refreshing, Modifier.align(Alignment.TopCenter), containerColor = t.surface, color = t.accent)
        },
    ) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (onQuery != null) item(key = "search") {
                HubTextField(query.orEmpty(), onQuery, placeholder = searchHint, leadingIcon = Lucide.Search, size = ControlSize.Md, fieldTag = "$tag.search")
            }
            if (filters.isNotEmpty()) item(key = "filters") {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    filters.forEach { f ->
                        Chip(f.label, f.value == filter, { onFilter(f.value) }, size = ControlSize.Sm, modifier = Modifier.testTag("$tag.filter.${f.value ?: "all"}"))
                    }
                }
            }
            header?.let { h -> item(key = "header") { h() } }
            if (list.error != null && list.items.isNotEmpty()) item(key = "error") { ErrorNotice(list.error) }
            when {
                list.loading -> item(key = "loading") { Loading(Modifier.padding(top = 32.dp)) }
                list.items.isEmpty() && list.error != null -> item(key = "failed") {
                    androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ErrorNotice(list.error)
                        HubButton(stringResource(R.string.retry), list::refresh, kind = ButtonKind.Secondary, size = ControlSize.Md, icon = Lucide.RefreshCw)
                    }
                }
                list.items.isEmpty() -> item(key = "empty") {
                    EmptyState(
                        if (query.isNullOrBlank() && filter == null) emptyTitle else stringResource(R.string.kit_no_matches),
                        Modifier.testTag("$tag.empty"), body = emptyBody.takeIf { query.isNullOrBlank() && filter == null }, icon = emptyIcon,
                    )
                }
            }
            items(list.items, key = key) { item ->
                ActionRow(item, actions?.invoke(item).orEmpty(), swipeAction?.invoke(item)) { row(item) }
            }
            if (list.hasMore) item(key = "more") {
                // The next page comes as the end of the list shows; the button is there for TalkBack and a failure.
                LaunchedEffect(list.items.size) { if (list.error == null) list.loadMore() }
                Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                    if (list.loadingMore) Spinner()
                    else HubButton(stringResource(R.string.kit_load_more), list::loadMore, kind = ButtonKind.Ghost, size = ControlSize.Sm, modifier = Modifier.testTag("$tag.more"))
                }
            }
        }
    }
}

/** A row with its long-press menu and its swipe. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun <T> ActionRow(item: T, actions: List<RowAction>, swipe: RowAction?, content: @Composable () -> Unit) {
    val t = LocalTokens.current
    val scope = rememberCoroutineScope()
    var menu by remember(item) { mutableStateOf(false) }
    val body: @Composable () -> Unit = {
        Box(if (actions.isEmpty()) Modifier else Modifier.combinedClickable(onClick = {}, onLongClick = { menu = true })) {
            content()
            if (actions.isNotEmpty()) {
                HubMenu(menu, { menu = false }) {
                    actions.forEach { a -> MenuItem(a.label, { menu = false; a.onClick() }, icon = a.icon, danger = a.danger) }
                }
            }
        }
    }
    if (swipe == null) {
        body()
        return
    }
    val state = rememberSwipeToDismissBoxState()
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = false,
        onDismiss = { scope.launch { state.reset() }; swipe.onClick() },
        backgroundContent = {
            Row(
                Modifier.fillMaxSize().background(if (swipe.danger) t.danger else t.accent, RoundedCornerShape(RadiusTokens.lg.dp)).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically,
            ) {
                LucideIcon(swipe.icon, null, size = 18.dp, tint = t.accentText)
                Text(swipe.label, fontSize = FontTokens.sizeSm.sp, color = t.accentText)
            }
        },
    ) { body() }
}

/** The «⋯» of a row that also has a long-press menu, for people who do not long-press. */
@Composable
fun RowActionsButton(actions: List<RowAction>, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        HubIconButton(Lucide.Ellipsis, stringResource(R.string.kit_more_actions), { open = true }, size = 32.dp, iconSize = 16.dp)
        HubMenu(open, { open = false }) {
            actions.forEach { a -> MenuItem(a.label, { open = false; a.onClick() }, icon = a.icon, danger = a.danger) }
        }
    }
}
