package hub.core.android.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hub.core.android.R
import hub.core.android.generated.FontTokens
import hub.core.android.nav.Navigator
import hub.core.android.nav.Route
import hub.core.android.ui.kit.Badge
import hub.core.android.ui.kit.BadgeTone
import hub.core.android.ui.kit.ButtonKind
import hub.core.android.ui.kit.ConfirmDialog
import hub.core.android.ui.kit.ControlSize
import hub.core.android.ui.kit.HubButton
import hub.core.android.ui.kit.HubDialog
import hub.core.android.ui.kit.HubMenu
import hub.core.android.ui.kit.HubSheet
import hub.core.android.ui.kit.HubTextField
import hub.core.android.ui.kit.ItemShape
import hub.core.android.ui.kit.Lucide
import hub.core.android.ui.kit.LucideIcon
import hub.core.android.ui.kit.MenuDivider
import hub.core.android.ui.kit.MenuItem
import hub.core.android.ui.theme.LocalTokens
import hub.core.client.model.ChannelConversation
import hub.core.client.model.Session
import hub.core.client.model.SessionCategory
import hub.core.client.model.SessionCategoryInput

/*
 * The drawer's groups (ChatGroups.kt): a category's header with its menu (rename, colour, move
 * up/down, delete), a channel's header with its conversations, the list's end (hidden chats,
 * older conversations, a new category), and the dialogs they open. Chats move into a category
 * from their long-press menu («Move to category»), since the phone has no drag between groups.
 */

private val contentStyle = TextStyle(textDirection = TextDirection.Content)

/** A colour's name, for TalkBack (the web's sessions.categories.colours.*). */
@Composable
private fun colourName(id: String): String = stringResource(
    when (id) {
        "blue" -> R.string.cat_colours_blue
        "green" -> R.string.cat_colours_green
        "amber" -> R.string.cat_colours_amber
        "red" -> R.string.cat_colours_red
        "purple" -> R.string.cat_colours_purple
        "pink" -> R.string.cat_colours_pink
        "teal" -> R.string.cat_colours_teal
        else -> R.string.cat_colours_slate
    },
)

/** A category's colour as drawn, or null for none. */
fun categoryColour(hex: String?): Color? =
    if (ChatGroupsRules.validColour(hex)) Color(android.graphics.Color.parseColor(hex)) else null

/** The dialogs a group or a row may open, kept by the list so each opens once. */
class ChatGroupsDialogs {
    var creating by mutableStateOf<String?>(null)
    var renaming by mutableStateOf<SessionCategory?>(null)
    var colouring by mutableStateOf<SessionCategory?>(null)
    var deleting by mutableStateOf<SessionCategory?>(null)
    var moving by mutableStateOf<Session?>(null)
    var deletingConversation by mutableStateOf<ChannelConversation?>(null)
    /** A chat waiting for the category being made, so it moves in once it exists. */
    var moveAfterCreate by mutableStateOf<Session?>(null)
}

@Composable
fun rememberChatGroupsDialogs(): ChatGroupsDialogs = remember { ChatGroupsDialogs() }

/**
 * The groups under the pinned section: each header, then its rows unless folded. [row] draws a
 * hub chat (Shell's ChatRow); conversations are drawn here.
 */
fun LazyListScope.chatGroups(
    groups: List<ChatGroup>,
    extras: ChatExtrasState,
    categories: List<SessionCategory>,
    shell: ShellViewModel,
    nav: Navigator,
    dialogs: ChatGroupsDialogs,
    badges: Boolean,
    isAdmin: Boolean,
    folded: Boolean,
    onOpen: () -> Unit,
    /** A hub chat's row: the chat, its group's key, its key in the list, and its group's chats in order (for drag and Move up/down). */
    row: @Composable (Session, String, Any, List<String>) -> Unit,
) {
    groups.forEach { group ->
        val open = group is ChatGroup.Rest || !folded || group.key !in extras.collapsed
        when (group) {
            is ChatGroup.Category -> {
                item(key = group.key) {
                    CategoryHeader(group.category, group.items.size, open, categories, shell, dialogs, badges)
                }
                if (open) {
                    if (group.items.isEmpty()) item(key = group.key + ":empty") { EmptyGroupHint() }
                    val shown = group.items.map { it.id }
                    items(group.items, key = { group.key + ":" + it.id }) { row(it, group.key, group.key + ":" + it.id, shown) }
                }
            }
            is ChatGroup.Channel -> {
                item(key = group.key) { ChannelHeader(group.channel, group.items.size + group.conversations.size, open, shell) }
                if (open) {
                    val shown = group.items.map { it.id }
                    items(group.items, key = { group.key + ":" + it.id }) { row(it, group.key, group.key + ":" + it.id, shown) }
                    items(group.conversations, key = { group.key + ":c:" + it.id }) { c ->
                        ConversationRow(c, badges, shell, nav, dialogs, isAdmin, onOpen)
                    }
                }
            }
            is ChatGroup.Rest -> {
                if (group.items.isNotEmpty()) {
                    item(key = group.key) { GroupLabel(stringResource(R.string.chats_recent, group.items.size)) }
                    val shown = group.items.map { it.id }
                    items(group.items, key = { it.id }) { row(it, group.key, it.id, shown) }
                }
            }
        }
    }
}

@Composable
private fun GroupLabel(text: String, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
    Row(
        modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text, Modifier.weight(1f), fontSize = FontTokens.sizeXs.sp, fontWeight = FontWeight.SemiBold,
            color = LocalTokens.current.textFaint, maxLines = 1, overflow = TextOverflow.Ellipsis, style = contentStyle,
        )
        trailing()
    }
}

@Composable
private fun EmptyGroupHint() {
    Text(
        stringResource(R.string.cat_empty_group), fontSize = FontTokens.sizeXs.sp, color = LocalTokens.current.textFaint,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
    )
}

/** A category's header: its dot, name and count; a tap folds it, the ⋯ holds its actions. */
@Composable
private fun CategoryHeader(
    category: SessionCategory,
    count: Int,
    open: Boolean,
    categories: List<SessionCategory>,
    shell: ShellViewModel,
    dialogs: ChatGroupsDialogs,
    badges: Boolean,
) {
    val t = LocalTokens.current
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp).clip(ItemShape).clickable { shell.extras.toggleCollapsed("category:${category.id}") }
                .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 2.dp).testTag("category.${category.id}"),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            LucideIcon(if (open) Lucide.ChevronDown else Lucide.ChevronRight, null, size = 12.dp, tint = t.textFaint)
            categoryColour(category.color)?.let { Box(Modifier.size(8.dp).background(it, CircleShape)) }
            Text(
                category.name, Modifier.weight(1f), fontSize = FontTokens.sizeXs.sp, fontWeight = FontWeight.SemiBold,
                color = t.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, style = contentStyle,
            )
            if (badges) Badge(shell.profileName(category.profile), tone = BadgeTone.Accent)
            Text(count.toString(), fontSize = FontTokens.sizeXs.sp, color = t.textFaint)
            Box {
                hub.core.android.ui.kit.HubIconButton(
                    Lucide.Ellipsis, stringResource(R.string.cat_more, category.name), { menu = true }, size = 28.dp, iconSize = 14.dp,
                    modifier = Modifier.testTag("category.${category.id}.more"),
                )
                HubMenu(menu, { menu = false }) {
                    MenuItem(stringResource(R.string.cat_rename), { menu = false; dialogs.renaming = category }, icon = Lucide.Pencil)
                    MenuItem(stringResource(R.string.cat_colour), { menu = false; dialogs.colouring = category }, icon = Lucide.Palette)
                    if (category.position > 0) {
                        MenuItem(
                            stringResource(R.string.cat_move_up),
                            { menu = false; shell.extras.write({ it.updateCategory(category, SessionCategoryInput(position = category.position - 1)) }) },
                            icon = Lucide.ChevronUp,
                        )
                    }
                    if (category.position < ChatGroupsRules.lastPosition(categories, category)) {
                        MenuItem(
                            stringResource(R.string.cat_move_down),
                            { menu = false; shell.extras.write({ it.updateCategory(category, SessionCategoryInput(position = category.position + 1)) }) },
                            icon = Lucide.ChevronDown,
                        )
                    }
                    MenuDivider()
                    MenuItem(
                        stringResource(R.string.cat_delete), { menu = false; dialogs.deleting = category },
                        Modifier.testTag("category.${category.id}.delete"), icon = Lucide.Trash, danger = true,
                    )
                }
            }
        }
    }
}

/** A channel's header: Telegram, WhatsApp… and how many chats and conversations it holds. */
@Composable
private fun ChannelHeader(channel: String, count: Int, open: Boolean, shell: ShellViewModel) {
    val t = LocalTokens.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp).clip(ItemShape).clickable { shell.extras.toggleCollapsed("channel:$channel") }
            .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 2.dp).testTag("channel.group.$channel"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        LucideIcon(if (open) Lucide.ChevronDown else Lucide.ChevronRight, null, size = 12.dp, tint = t.textFaint)
        LucideIcon(Lucide.MessagesSquare, null, size = 12.dp, tint = t.textFaint)
        Text(
            channelName(channel), Modifier.weight(1f), fontSize = FontTokens.sizeXs.sp, fontWeight = FontWeight.SemiBold,
            color = t.textMuted, maxLines = 1,
        )
        Text(count.toString(), fontSize = FontTokens.sizeXs.sp, color = t.textFaint)
    }
}

/** A conversation Hermes keeps for a channel: tap opens its transcript; a long press hides or deletes it. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationRow(
    conversation: ChannelConversation,
    badge: Boolean,
    shell: ShellViewModel,
    nav: Navigator,
    dialogs: ChatGroupsDialogs,
    isAdmin: Boolean,
    onOpen: () -> Unit,
) {
    val t = LocalTokens.current
    var menu by remember { mutableStateOf(false) }
    val selected = (nav.current as? Route.ChannelChat)?.conversationId == conversation.id
    val assistant = stringResource(R.string.chcv_assistant)
    Box {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp).clip(ItemShape)
                .background(if (selected) t.surface2 else Color.Transparent, ItemShape)
                .combinedClickable(
                    onClick = { nav.go(Route.ChannelChat(conversation.id, conversation.profile)); onOpen() },
                    onLongClick = { menu = true },
                )
                .padding(horizontal = 8.dp, vertical = 7.dp).testTag("conversation.row.${conversation.id}"),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.size(24.dp).background(t.surface2, CircleShape).border(0.5.dp, t.border, CircleShape), contentAlignment = Alignment.Center) {
                LucideIcon(if (conversation.hidden == true) Lucide.EyeOff else Lucide.MessagesSquare, null, size = 13.dp, tint = t.textMuted)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    conversationTitle(conversation), fontSize = FontTokens.sizeSm.sp, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, style = contentStyle,
                    color = if (conversation.hidden == true) t.textMuted else t.text,
                )
                ChatGroupsRules.preview(conversation, assistant).takeIf { it.isNotBlank() }?.let {
                    Text(it, fontSize = FontTokens.sizeXs.sp, color = t.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, style = contentStyle)
                }
            }
            if (badge) Badge(shell.profileName(conversation.profile), tone = BadgeTone.Accent)
        }
        HubMenu(menu, { menu = false }) {
            if (conversation.hidden == true) {
                MenuItem(stringResource(R.string.chcv_unhide), { menu = false; shell.extras.write({ it.unhide(conversation) }) }, icon = Lucide.Eye)
            } else {
                MenuItem(
                    stringResource(R.string.chcv_hide), { menu = false; shell.extras.write({ it.hide(conversation) }) },
                    Modifier.testTag("conversation.hide"), icon = Lucide.EyeOff,
                )
            }
            if (isAdmin) {
                MenuDivider()
                MenuItem(
                    stringResource(R.string.chcv_delete), { menu = false; dialogs.deletingConversation = conversation },
                    Modifier.testTag("conversation.delete"), icon = Lucide.Trash, danger = true,
                )
            }
        }
    }
}

/** The list's end: hidden conversations, older ones, Hermes out of reach, and New category. */
fun LazyListScope.chatGroupsFooter(extras: ChatExtrasState, archive: ArchiveFilter, shell: ShellViewModel, dialogs: ChatGroupsDialogs, newCategoryProfile: String) {
    val hidden = ChatGroupsRules.hiddenCount(extras.conversations, archive)
    item(key = "groups.footer") {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (extras.unreachable && archive != ArchiveFilter.ARCHIVED) {
                Text(stringResource(R.string.chcv_unreachable), fontSize = FontTokens.sizeXs.sp, color = LocalTokens.current.warningSoftText)
            }
            if (extras.hasMoreConversations && archive != ArchiveFilter.ARCHIVED && extras.channelLimit < ChatGroupsRules.CHANNEL_MAX) {
                HubButton(stringResource(R.string.chcv_show_older), shell.extras::older, kind = ButtonKind.Ghost, size = ControlSize.Sm, icon = Lucide.ClockArrowUp)
            }
            if (hidden > 0 || extras.showHidden) {
                HubButton(
                    if (extras.showHidden) stringResource(R.string.chcv_hide_hidden) else stringResource(R.string.chcv_show_hidden, hidden.toString()),
                    shell.extras::toggleHidden, kind = ButtonKind.Ghost, size = ControlSize.Sm,
                    icon = if (extras.showHidden) Lucide.EyeOff else Lucide.Eye, modifier = Modifier.testTag("chats.show_hidden"),
                )
            }
            if (archive != ArchiveFilter.ARCHIVED) {
                HubButton(
                    stringResource(R.string.cat_new), { dialogs.creating = newCategoryProfile }, kind = ButtonKind.Ghost, size = ControlSize.Sm,
                    icon = Lucide.Tag, modifier = Modifier.testTag("chats.category.new"),
                )
            }
        }
    }
}

/** Every dialog the groups open: new, rename, colour, delete a category; move a chat; delete a conversation. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChatGroupsDialogsView(dialogs: ChatGroupsDialogs, categories: List<SessionCategory>, shell: ShellViewModel, nav: Navigator) {
    dialogs.creating?.let { profile ->
        NameDialog(
            title = stringResource(R.string.cat_new), label = stringResource(R.string.cat_new_label), initial = "",
            hint = stringResource(R.string.cat_new_in_profile, shell.profileName(profile)), confirm = stringResource(R.string.cat_create),
            onDismiss = { dialogs.creating = null; dialogs.moveAfterCreate = null },
        ) { name ->
            val chat = dialogs.moveAfterCreate
            dialogs.creating = null
            dialogs.moveAfterCreate = null
            shell.extras.write({ ops ->
                val made = ops.createCategory(profile, name)
                val category = made.getOrNull()
                if (chat != null && category != null) ops.move(chat, category.id) ?: made else made
            }, after = { if (chat != null) shell.reloadChats() })
        }
    }
    dialogs.renaming?.let { category ->
        NameDialog(
            title = stringResource(R.string.cat_rename), label = stringResource(R.string.cat_new_label), initial = category.name,
            confirm = stringResource(R.string.peers_save), onDismiss = { dialogs.renaming = null },
        ) { name ->
            dialogs.renaming = null
            if (name != category.name) shell.extras.write({ it.updateCategory(category, SessionCategoryInput(name = name)) })
        }
    }
    dialogs.colouring?.let { category ->
        val t = LocalTokens.current
        HubDialog({ dialogs.colouring = null }, stringResource(R.string.cat_colour_title, category.name)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ChatGroupsRules.COLOURS.forEach { (id, hex) ->
                    val chosen = category.color.equals(hex, ignoreCase = true)
                    val name = colourName(id)
                    Box(
                        Modifier.size(36.dp).clip(CircleShape).background(categoryColour(hex)!!, CircleShape)
                            .semantics { contentDescription = name }
                            .border(if (chosen) 3.dp else 0.dp, t.text, CircleShape)
                            .clickable {
                                dialogs.colouring = null
                                shell.extras.write({ it.updateCategory(category, SessionCategoryInput(color = hex)) })
                            }
                            .testTag("category.colour.$id"),
                        contentAlignment = Alignment.Center,
                    ) { if (chosen) LucideIcon(Lucide.Check, null, size = 16.dp, tint = Color.White) }
                }
            }
            HubButton(
                stringResource(R.string.cat_colour_none),
                {
                    dialogs.colouring = null
                    shell.extras.write({ it.updateCategory(category, SessionCategoryInput(sendNull = setOf(SessionCategoryInput.Clearable.COLOR))) })
                },
                kind = ButtonKind.Secondary, size = ControlSize.Md, modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
    dialogs.deleting?.let { category ->
        ConfirmDialog(
            title = stringResource(R.string.cat_confirm_delete, category.name),
            body = stringResource(R.string.cat_confirm_delete_body),
            confirm = stringResource(R.string.cat_delete),
            onConfirm = { dialogs.deleting = null; shell.extras.write({ it.deleteCategory(category) }, after = shell::reloadChats) },
            onDismiss = { dialogs.deleting = null },
            danger = true,
        )
    }
    dialogs.deletingConversation?.let { conversation ->
        ConfirmDialog(
            title = stringResource(R.string.chcv_delete_title, conversationTitle(conversation)),
            body = stringResource(R.string.chcv_delete_body, channelName(conversation.channel)),
            confirm = stringResource(R.string.chcv_delete_confirm),
            onConfirm = {
                dialogs.deletingConversation = null
                shell.extras.write({ it.delete(conversation) }, after = {
                    if ((nav.current as? Route.ChannelChat)?.conversationId == conversation.id) nav.go(Route.NewChat)
                })
            },
            onDismiss = { dialogs.deletingConversation = null },
            danger = true,
        )
    }
    dialogs.moving?.let { chat -> MoveSheet(chat, categories, shell, dialogs) }
}

/** «Move to category»: the categories of the chat's own profile, «No category», and a new one. */
@Composable
private fun MoveSheet(chat: Session, categories: List<SessionCategory>, shell: ShellViewModel, dialogs: ChatGroupsDialogs) {
    val t = LocalTokens.current
    val choices = ChatGroupsRules.movable(categories, chat.profile)
    HubSheet({ dialogs.moving = null }, title = stringResource(R.string.cat_move_title, chat.title ?: stringResource(R.string.term_new_chat))) {
        Text(stringResource(R.string.cat_move_hint, shell.profileName(chat.profile)), fontSize = FontTokens.sizeXs.sp, color = t.textMuted)
        fun move(id: String?) {
            dialogs.moving = null
            shell.extras.write({ it.move(chat, id) }, after = shell::reloadChats)
        }
        MoveChoice(stringResource(R.string.cat_none), chat.categoryId == null, null, "move.none") { move(null) }
        choices.forEach { c -> MoveChoice(c.name, chat.categoryId == c.id, categoryColour(c.color), "move.${c.id}") { move(c.id) } }
        HubButton(
            stringResource(R.string.cat_new_and_move),
            { dialogs.moving = null; dialogs.moveAfterCreate = chat; dialogs.creating = chat.profile },
            kind = ButtonKind.Ghost, size = ControlSize.Md, icon = Lucide.Plus, modifier = Modifier.testTag("move.new"),
        )
    }
}

@Composable
private fun MoveChoice(label: String, current: Boolean, colour: Color?, tag: String, onClick: () -> Unit) {
    val t = LocalTokens.current
    Row(
        Modifier.fillMaxWidth().clip(ItemShape).clickable(enabled = !current, onClick = onClick).padding(horizontal = 8.dp, vertical = 10.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (colour != null) Box(Modifier.size(10.dp).background(colour, CircleShape)) else LucideIcon(Lucide.Tag, null, size = 14.dp, tint = t.textFaint)
        Text(label, Modifier.weight(1f), fontSize = FontTokens.sizeMd.sp, style = contentStyle)
        if (current) Text(stringResource(R.string.cat_current), fontSize = FontTokens.sizeXs.sp, color = t.textMuted)
    }
}

@Composable
private fun NameDialog(title: String, label: String, initial: String, confirm: String, onDismiss: () -> Unit, hint: String? = null, onSave: (String) -> Unit) {
    var typed by remember(initial) { mutableStateOf(initial) }
    HubDialog(onDismiss, title) {
        if (hint != null) Text(hint, fontSize = FontTokens.sizeXs.sp, color = LocalTokens.current.textMuted)
        HubTextField(typed, { typed = it.take(ChatGroupsRules.NAME_MAX) }, Modifier.fillMaxWidth(), label = label, size = ControlSize.Md, fieldTag = "category.name")
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            HubButton(stringResource(R.string.cancel), onDismiss, kind = ButtonKind.Secondary, size = ControlSize.Md)
            HubButton(
                confirm, { ChatGroupsRules.name(typed)?.let(onSave) }, size = ControlSize.Md,
                enabled = ChatGroupsRules.name(typed) != null, modifier = Modifier.testTag("category.name.save"),
            )
        }
    }
}
