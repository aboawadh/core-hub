package hub.core.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hub.core.android.R
import hub.core.android.data.HubError
import hub.core.android.generated.FontTokens
import hub.core.android.ui.components.ErrorNotice
import hub.core.android.ui.kit.ButtonKind
import hub.core.android.ui.kit.ControlSize
import hub.core.android.ui.kit.HubButton
import hub.core.android.ui.kit.HubCard
import hub.core.android.ui.theme.LocalTokens
import kotlinx.coroutines.launch

/** A sender waiting to pair with the agent's channel, in the pending sheet: approved or denied here. */
@Composable
internal fun PairingCard(shell: ShellViewModel, item: PendingPairing) {
    val scope = rememberCoroutineScope()
    val t = LocalTokens.current
    var busy by remember(item.request.requestId) { mutableStateOf(false) }
    var error by remember(item.request.requestId) { mutableStateOf<HubError?>(null) }
    fun answer(approve: Boolean) {
        busy = true
        scope.launch {
            shell.answerPairing(item, approve).onFailure { error = it as? HubError }
            busy = false
        }
    }
    HubCard(Modifier.testTag("pending.pairing.${item.request.requestId}"), padding = 12.dp) {
        Text(
            stringResource(R.string.admin_pairing_request, item.request.userName ?: item.request.userId, item.request.platform),
            fontSize = FontTokens.sizeSm.sp, fontWeight = FontWeight.Medium,
        )
        Text(stringResource(R.string.admin_pairing_hint), fontSize = FontTokens.sizeXs.sp, color = t.textMuted)
        ErrorNotice(error)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HubButton(stringResource(R.string.admin_pairing_approve), { answer(true) }, size = ControlSize.Sm, enabled = !busy, modifier = Modifier.testTag("pending.pairing.approve"))
            HubButton(stringResource(R.string.admin_pairing_deny), { answer(false) }, kind = ButtonKind.Ghost, size = ControlSize.Sm, enabled = !busy, modifier = Modifier.testTag("pending.pairing.deny"))
        }
    }
}
