package hub.core.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
import hub.core.android.ui.components.ErrorNotice
import hub.core.android.ui.components.LoadView
import hub.core.android.ui.components.rememberLoad
import hub.core.android.ui.kit.BadgeTone
import hub.core.android.ui.kit.GroupedList
import hub.core.android.ui.kit.Item
import hub.core.android.ui.kit.Lucide
import hub.core.android.ui.kit.NoticeBox
import hub.core.android.ui.theme.LocalTokens
import hub.core.client.model.Model
import hub.core.client.model.ModelRef
import hub.core.client.model.Provider
import kotlinx.coroutines.launch

/**
 * Pictures: the model this profile draws and edits images with — one that draws, on a chat provider the
 * hub can draw with (§72, §84), the image-only ones first (§87, §110). A profile that chose none uses
 * the default profile's, and says so; choosing again can go back to it.
 */
@Composable
internal fun ImagesTab(ops: ModelOps, providers: List<Provider>, isAdmin: Boolean) {
    val scope = rememberCoroutineScope()
    val t = LocalTokens.current
    val load = rememberLoad(ops.profile, "defaults-image") { ops.defaults().getOrThrow() }
    var picking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<HubError?>(null) }
    val models = remember(providers) { ModelRules.imageModels(providers) }
    val viaText = stringResource(R.string.models_images_via_subscription)
    fun name(m: Model): String = ModelRules.viaSubscription(m, providers)?.let { viaText.format(it, m.model) } ?: (m.alias ?: m.model)
    fun label(ref: ModelRef?): String? {
        if (ref == null) return null
        val m = providers.firstOrNull { it.id == ref.providerId }?.models?.firstOrNull { it.model == ref.model }
        return if (m != null && ModelRules.viaSubscription(m, providers) != null) name(m) else modelLabel(ref, providers)
    }
    LoadView(load) { d ->
        val inherited = "image" in d.inherited.orEmpty()
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp).testTag("models.images"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ErrorNotice(error)
            GroupedList(title = stringResource(R.string.models_image_model)) {
                Item(
                    label(d.image) ?: stringResource(R.string.models_none_chosen), icon = Lucide.Image,
                    subtitle = stringResource(R.string.models_inherited_short).takeIf { inherited && d.image != null },
                    chevron = isAdmin && models.isNotEmpty(), tag = "images.model", onClick = if (isAdmin && models.isNotEmpty()) ({ picking = true }) else null,
                )
            }
            Text(stringResource(R.string.models_images_hint_full), fontSize = FontTokens.sizeXs.sp, color = t.textMuted)
            if (models.isEmpty() && d.image == null) NoticeBox(stringResource(R.string.models_images_none_hint), BadgeTone.Info)
            else Text(stringResource(R.string.models_images_used_by), fontSize = FontTokens.sizeXs.sp, color = t.textMuted)
        }
        if (picking) {
            ModelPickerSheet(
                stringResource(R.string.models_image_model), models, providers,
                onPick = { ref ->
                    picking = false
                    scope.launch { ops.setImage(ref).onFailure { error = it as HubError }; load.reload() }
                },
                onDismiss = { picking = false },
                clear = if (d.image != null && !inherited) stringResource(if (ops.profile == "default") R.string.models_clear_choice else R.string.models_use_inherited) else null,
                name = ::name,
            )
        }
    }
}
