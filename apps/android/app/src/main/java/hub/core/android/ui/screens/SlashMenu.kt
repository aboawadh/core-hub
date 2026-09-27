package hub.core.android.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hub.core.android.R
import hub.core.android.chat.SlashCommands
import hub.core.android.generated.FontTokens
import hub.core.android.ui.kit.Spinner
import hub.core.android.ui.kit.floatingChrome
import hub.core.android.ui.theme.LocalTokens

/*
 * The menu above the composer while `/` is typed (SlashCommands.kt): the commands this agent takes,
 * each with what it does; after `/skill ` the agent's enabled skills, a tap writing the skill's key.
 */

@Composable
fun slashDescription(name: String): String = stringResource(
    when (name) {
        "compress" -> R.string.slash_describe_compress
        "steer" -> R.string.slash_describe_steer
        "skill" -> R.string.slash_describe_skill
        "plan" -> R.string.slash_describe_plan
        "goal" -> R.string.slash_describe_goal
        "learn" -> R.string.slash_describe_learn
        "new" -> R.string.slash_describe_new
        "fork" -> R.string.slash_describe_fork
        "archive" -> R.string.slash_describe_archive
        "model" -> R.string.slash_describe_model
        else -> R.string.slash_describe_clear_screen
    },
)

private val ltr = TextStyle(textDirection = TextDirection.Ltr, fontFamily = FontFamily.Monospace)

@Composable
fun SlashMenu(
    draft: String,
    offered: List<SlashCommands.Command>,
    skills: List<SlashCommands.SkillChoice>?,
    onCommand: (SlashCommands.Command) -> Unit,
    onSkill: (SlashCommands.SkillChoice) -> Unit,
) {
    val t = LocalTokens.current
    val skillQuery = SlashCommands.skillQuery(draft)
    val query = SlashCommands.query(draft)
    if (skillQuery == null && query == null) return
    if (skillQuery != null && offered.none { it.name == "skill" }) return
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp).floatingChrome().padding(vertical = 6.dp).heightIn(max = 260.dp)
            .verticalScroll(rememberScrollState()).testTag(if (skillQuery != null) "chat.slash.skills" else "chat.slash"),
    ) {
        Text(
            stringResource(if (skillQuery != null) R.string.slash_skills_title else R.string.slash_title),
            fontSize = FontTokens.sizeXs.sp, fontWeight = FontWeight.SemiBold, color = t.textFaint, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        )
        if (skillQuery != null) {
            when {
                skills == null -> Spinner(16.dp, t.textMuted, Modifier.padding(12.dp))
                else -> {
                    val shown = SlashCommands.filterSkills(skills, skillQuery)
                    if (shown.isEmpty()) Text(stringResource(R.string.slash_skills_none), fontSize = FontTokens.sizeXs.sp, color = t.textMuted, modifier = Modifier.padding(12.dp))
                    shown.forEach { skill ->
                        MenuRow(skill.key, skill.description.ifBlank { skill.name }, "chat.slash.skill.${skill.key}") { onSkill(skill) }
                    }
                }
            }
        } else {
            val shown = SlashCommands.filter(offered, query.orEmpty(), { it.name })
            if (shown.isEmpty()) Text(stringResource(R.string.slash_no_match), fontSize = FontTokens.sizeXs.sp, color = t.textMuted, modifier = Modifier.padding(12.dp))
            shown.forEach { command ->
                MenuRow("/" + command.name, slashDescription(command.name), "chat.slash.command.${command.name}") { onCommand(command) }
            }
        }
    }
}

@Composable
private fun MenuRow(title: String, subtitle: String, tag: String, onClick: () -> Unit) {
    val t = LocalTokens.current
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 6.dp).testTag(tag),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Text(title, fontSize = FontTokens.sizeSm.sp, fontWeight = FontWeight.Medium, style = ltr, color = t.text)
        Text(subtitle, fontSize = FontTokens.sizeXs.sp, color = t.textMuted, maxLines = 2, overflow = TextOverflow.Ellipsis, style = TextStyle(textDirection = TextDirection.Content))
    }
}

