package hub.core.android.ui.screens

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hub.core.android.R
import hub.core.android.data.HubError
import hub.core.android.data.StoredUser
import hub.core.android.generated.FontTokens
import hub.core.android.graph
import hub.core.android.ui.components.LoadView
import hub.core.android.ui.components.Notice
import hub.core.android.ui.components.Tone
import hub.core.android.ui.components.ErrorNotice
import hub.core.android.ui.components.rememberLoad
import hub.core.android.ui.kit.Badge
import hub.core.android.ui.kit.ButtonKind
import hub.core.android.ui.kit.ControlSize
import hub.core.android.ui.kit.GroupedList
import hub.core.android.ui.kit.HubButton
import hub.core.android.ui.kit.HubCard
import hub.core.android.ui.kit.HubTextField
import hub.core.android.ui.kit.Item
import hub.core.android.ui.kit.Lucide
import hub.core.android.ui.kit.SectionTitle
import hub.core.android.ui.theme.LocalTokens
import hub.core.client.model.Avatar
import hub.core.client.model.User
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings → Account: who you are on this hub — your picture, your name, your password, and the
 * messaging accounts that act as you (`auth.updateMe`, `auth.changePassword`, the
 * `auth.*MyChannelIdentit*` calls). iOS's AccountPage.swift is the twin.
 */
@Composable
private fun AccountPage(shell: ShellViewModel) {
    val context = LocalContext.current
    val graph = context.graph
    val ops = remember { OwnSettingsOps { graph.apis(graph.store.current!!) } }
    val me = rememberLoad { ops.me().getOrThrow() }
    LoadView(me) { user ->
        AccountContent(user, ops, shell) { updated ->
            // The drawer's name follows at once.
            graph.store.update { it.copy(user = StoredUser.from(updated)) }
            me.reload()
        }
    }
}

@Composable
private fun AccountContent(user: User, ops: OwnSettingsOps, shell: ShellViewModel, onChanged: (User) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var note by remember { mutableStateOf<Pair<String, Tone>?>(null) }
    var error by remember { mutableStateOf<HubError?>(null) }
    var busy by remember { mutableStateOf(false) }
    var picture by remember { mutableStateOf<ImageBitmap?>(null) }
    var name by remember(user.id) { mutableStateOf(user.displayName) }
    var current by remember { mutableStateOf("") }
    var fresh by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    val saved = stringResource(R.string.own_settings_saved)
    val photoSaved = stringResource(R.string.own_settings_photo_saved)
    val photoFailed = stringResource(R.string.own_settings_photo_too_big)
    val passwordChanged = stringResource(R.string.own_settings_password_changed)

    fun failed(failure: Throwable) {
        note = null
        error = failure as? HubError ?: HubError(-1, null, failure.message)
    }

    fun done(text: String) {
        error = null
        note = text to Tone.SUCCESS
    }

    LaunchedEffect(user.id, user.updatedAt) {
        picture = if (user.avatar.kind == Avatar.Kind.IMAGE) {
            ops.avatar(user.id).getOrNull()?.let { file ->
                withContext(Dispatchers.IO) { BitmapFactory.decodeFile(file.path).also { file.delete() } }?.asImageBitmap()
            }
        } else null
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val url = withContext(Dispatchers.IO) { runCatching { avatarJpeg(context.contentResolver, uri) }.getOrNull() }
                ?.let(OwnSettingsRules::avatarDataUrl)
            if (url == null) { error = null; note = photoFailed to Tone.DANGER }
            else ops.setAvatar(url).onSuccess { done(photoSaved); onChanged(it) }.onFailure(::failed)
            busy = false
        }
    }

    LazyColumn(Modifier.testTag("account.page"), contentPadding = settingsPagePadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        note?.let { (text, tone) -> item { Notice(text, tone) } }
        error?.let { item { ErrorNotice(it) } }
        item {
            HubCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AccountAvatar(user, picture)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(user.displayName.ifEmpty { user.username }, fontSize = FontTokens.sizeMd.sp, fontWeight = FontWeight.SemiBold, color = LocalTokens.current.text)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            HubButton(
                                stringResource(R.string.own_settings_photo_choose),
                                { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                                kind = ButtonKind.Secondary, size = ControlSize.Sm, icon = Lucide.Image, enabled = !busy,
                                modifier = Modifier.testTag("account.photo"),
                            )
                            if (user.avatar.kind == Avatar.Kind.IMAGE) {
                                HubButton(
                                    stringResource(R.string.own_settings_photo_remove), {
                                        busy = true
                                        scope.launch {
                                            ops.setAvatar(null).onSuccess { done(saved); onChanged(it) }.onFailure(::failed)
                                            busy = false
                                        }
                                    },
                                    kind = ButtonKind.Ghost, size = ControlSize.Sm, icon = Lucide.Trash, enabled = !busy,
                                    modifier = Modifier.testTag("account.photo.remove"),
                                )
                            }
                        }
                    }
                }
            }
        }
        item {
            GroupedList {
                Item(stringResource(R.string.own_settings_account_username), value = user.username, icon = Lucide.CircleUserRound)
                Item(stringResource(R.string.own_settings_account_role), icon = Lucide.ShieldCheck, trailing = { Badge(roleLabel(user.role.value)) })
                Item(stringResource(R.string.own_settings_profiles), subtitle = user.profiles.joinToString(" · ") { shell.profileName(it) }, icon = Lucide.LayoutGrid)
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle(stringResource(R.string.own_settings_account_name))
                HubTextField(name, { name = it }, Modifier.fillMaxWidth(), fieldTag = "account.name")
                HubButton(
                    stringResource(R.string.own_settings_save), {
                        val value = OwnSettingsRules.nameToSave(name, user.displayName) ?: return@HubButton
                        busy = true
                        scope.launch {
                            ops.rename(value).onSuccess { name = it.displayName; done(saved); onChanged(it) }.onFailure(::failed)
                            busy = false
                        }
                    },
                    size = ControlSize.Md, enabled = !busy && OwnSettingsRules.nameToSave(name, user.displayName) != null,
                    modifier = Modifier.testTag("account.name.save"),
                )
            }
        }
        item {
            val problem = OwnSettingsRules.passwordProblem(fresh, again)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle(stringResource(R.string.own_settings_account_password))
                PasswordField(current, { current = it }, stringResource(R.string.own_settings_account_current_password), "account.current_password")
                PasswordField(
                    fresh, { fresh = it }, stringResource(R.string.own_settings_account_new_password), "account.new_password",
                    error = if (problem == OwnSettingsRules.PasswordProblem.SHORT) stringResource(R.string.own_settings_password_short) else null,
                )
                PasswordField(
                    again, { again = it }, stringResource(R.string.own_settings_confirm_password), "account.confirm_password",
                    error = if (problem == OwnSettingsRules.PasswordProblem.MISMATCH) stringResource(R.string.own_settings_password_mismatch) else null,
                )
                Text(stringResource(R.string.own_settings_password_hint), fontSize = FontTokens.sizeXs.sp, color = LocalTokens.current.textMuted)
                HubButton(
                    stringResource(R.string.own_settings_account_change_password), {
                        busy = true
                        scope.launch {
                            ops.changePassword(current, fresh).onSuccess {
                                // Emptied the moment the hub has them: nothing on the page keeps a password.
                                current = ""; fresh = ""; again = ""
                                done(passwordChanged)
                            }.onFailure(::failed)
                            busy = false
                        }
                    },
                    size = ControlSize.Md, icon = Lucide.KeyRound,
                    enabled = !busy && OwnSettingsRules.canChangePassword(current, fresh, again),
                    modifier = Modifier.testTag("account.change_password"),
                )
            }
        }
        item { ChannelAccountsCard(ops) }
        item {
            HubButton(term("sign_out"), shell::signOut, kind = ButtonKind.Danger, icon = Lucide.LogOut, fill = true, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun PasswordField(value: String, onValue: (String) -> Unit, label: String, tag: String, error: String? = null) {
    HubTextField(
        value, onValue, Modifier.fillMaxWidth(), label = label, error = error, fieldTag = tag,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
    )
}

@Composable
private fun AccountAvatar(user: User, picture: ImageBitmap?) {
    val t = LocalTokens.current
    Box(Modifier.size(64.dp).clip(CircleShape).background(t.accentSoft, CircleShape).clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
        if (picture != null) Image(picture, null, Modifier.size(64.dp), contentScale = ContentScale.Crop)
        else Text(OwnSettingsRules.initials(user.displayName.ifEmpty { user.username }), fontSize = FontTokens.sizeLg.sp, fontWeight = FontWeight.SemiBold, color = t.accentSoftText)
    }
}

@Composable
private fun roleLabel(role: String): String = stringResource(
    when (role) {
        "owner" -> R.string.own_settings_account_role_owner
        "admin" -> R.string.own_settings_account_role_admin
        else -> R.string.own_settings_account_role_member
    },
)

/**
 * The picked picture as a JPEG the hub takes: turned upright (ImageDecoder reads the orientation),
 * drawn at most [OwnSettingsRules.AVATAR_SIDE] on its longest side, a lower quality tried once when
 * the first is still too big.
 */
internal fun avatarJpeg(resolver: ContentResolver, uri: Uri): ByteArray? {
    val bitmap: Bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
            val (w, h) = OwnSettingsRules.avatarSize(info.size.width, info.size.height)
            decoder.setTargetSize(w, h)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    } else {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= OwnSettingsRules.AVATAR_SIDE) sample *= 2
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) } ?: return null
        val (w, h) = OwnSettingsRules.avatarSize(decoded.width, decoded.height)
        if (w == decoded.width && h == decoded.height) decoded else Bitmap.createScaledBitmap(decoded, w, h, true)
    }
    for (quality in listOf(85, 60)) {
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
        if (bytes.size <= OwnSettingsRules.AVATAR_MAX_BYTES) return bytes
    }
    return null
}

internal val accountPage = SettingsPageEntry("account") { AccountPage(it.shell) }
