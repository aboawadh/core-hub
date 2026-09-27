package hub.core.android.chat

import hub.core.client.model.AgentCapability
import hub.core.client.model.SkillCategory

/*
 * The composer's `/` commands (decision §57), as on the web and iOS: typing `/` offers what this
 * agent can be given and what the app does itself; a finished `/command words` runs it instead of
 * being sent. After `/skill ` the agent's enabled skills are offered, and a tap writes the skill's
 * key. Before 2026-09-27 the Android composer had none of it.
 *
 * - `HUB`: this app, with what it already does — a new chat, a fork, archiving, the model, clearing
 *   the screen. Every agent gets them.
 * - `ACTION`: the hub asks the agent (`sessions.compress`, `sessions.steerRun`), only with the capability.
 * - `MESSAGE`: sent as the message it is; the agent reads the word itself (`/goal`, `/plan`,
 *   `/learn`, `/skill <name>`), only with the capability.
 *
 * Anything else that starts with `/` is an ordinary message, sent as typed.
 */
object SlashCommands {
    enum class Kind { HUB, ACTION, MESSAGE }
    enum class Argument { NONE, OPTIONAL, REQUIRED }

    data class Command(val name: String, val kind: Kind, val capability: AgentCapability?, val argument: Argument)

    /** In the order the menu lists them: the agent's own first, then the app's. */
    val all: List<Command> = listOf(
        Command("compress", Kind.ACTION, AgentCapability.COMPRESS, Argument.OPTIONAL),
        Command("steer", Kind.ACTION, AgentCapability.STEER, Argument.REQUIRED),
        Command("skill", Kind.MESSAGE, AgentCapability.SKILL_COMMANDS, Argument.REQUIRED),
        Command("plan", Kind.MESSAGE, AgentCapability.PLANS, Argument.REQUIRED),
        Command("goal", Kind.MESSAGE, AgentCapability.GOALS, Argument.OPTIONAL),
        Command("learn", Kind.MESSAGE, AgentCapability.LEARN, Argument.OPTIONAL),
        Command("new", Kind.HUB, null, Argument.NONE),
        Command("fork", Kind.HUB, null, Argument.NONE),
        Command("archive", Kind.HUB, null, Argument.NONE),
        Command("model", Kind.HUB, null, Argument.OPTIONAL),
        Command("clear-screen", Kind.HUB, null, Argument.NONE),
    )

    /** What this agent can be given: a command whose capability it lacks is never offered. */
    fun available(capabilities: Collection<AgentCapability>): List<Command> =
        all.filter { it.capability == null || it.capability in capabilities }

    private val QUERY = Regex("^/([^\\s/]*)$")
    private val SKILL_QUERY = Regex("^/skill[ \\t]+(\\S*)$")
    private val PARSE = Regex("^/([a-z-]+)(?:[ \\t]+([\\s\\S]*))?$")

    /** The word being typed after `/`, while the composer holds nothing else; null otherwise. */
    fun query(text: String): String? = QUERY.matchEntire(text)?.groupValues?.get(1)

    /** `/skill rev` → `rev` while a skill's name is being typed; null otherwise. */
    fun skillQuery(text: String): String? = SKILL_QUERY.matchEntire(text)?.groupValues?.get(1)

    /** Names that start with the word first, then names (or descriptions) that contain it. */
    fun <T> filter(items: List<T>, query: String, name: (T) -> String, describe: (T) -> String = { "" }): List<T> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return items
        val starts = items.filter { name(it).lowercase().startsWith(q) }
        return starts + items.filter { it !in starts && (name(it).lowercase().contains(q) || describe(it).lowercase().contains(q)) }
    }

    /** The command a finished message is, among those offered, with its words; null for anything else. */
    fun parse(text: String, offered: List<Command>): Pair<Command, String>? {
        val m = PARSE.matchEntire(text.trim()) ?: return null
        val command = offered.firstOrNull { it.name == m.groupValues[1] } ?: return null
        return command to m.groupValues[2].trim()
    }

    /** What picking a command in the menu puts in the composer (a command without words runs at once: null). */
    fun picked(command: Command): String? = if (command.argument == Argument.NONE) null else "/${command.name} "

    /** A skill the menu offers after `/skill `: its key is what is written. */
    data class SkillChoice(val key: String, val name: String, val description: String)

    /** The agent's enabled skills. */
    fun skills(categories: List<SkillCategory>): List<SkillChoice> =
        categories.flatMap { it.skills }.filter { it.enabled }.map { SkillChoice(it.key, it.name, it.description.orEmpty()) }

    fun filterSkills(skills: List<SkillChoice>, query: String): List<SkillChoice> =
        filter(skills, query, { it.key }, { it.name + " " + it.description })

    /** A skill picked after `/skill `. */
    fun pickSkill(skill: SkillChoice): String = "/skill ${skill.key} "

    /** A model named by `/model <words>`: its id, its label, or the id's last part, case aside. */
    fun model(words: String, options: List<ChatControls.ModelOption>): String? {
        val needle = words.trim().lowercase()
        if (needle.isEmpty()) return null
        return options.firstOrNull { it.value.lowercase() == needle || it.label.lowercase() == needle || it.value.lowercase().endsWith("/$needle") }?.value
    }

    /** `/clear-screen`: the messages after the one the screen was cleared at (nothing is deleted). */
    fun <M> afterClear(messages: List<M>, seq: (M) -> Int, hiddenThrough: Int?): List<M> =
        if (hiddenThrough == null) messages else messages.filter { seq(it) > hiddenThrough }
}
