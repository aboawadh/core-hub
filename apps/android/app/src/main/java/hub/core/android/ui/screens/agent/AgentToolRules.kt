package hub.core.android.ui.screens

import hub.core.android.R
import hub.core.android.data.HubError
import hub.core.client.model.Agent
import hub.core.client.model.AgentInstall
import hub.core.client.model.AgentKind
import hub.core.client.model.AgentRuntime
import hub.core.client.model.AgentStatus
import hub.core.client.model.Job
import hub.core.client.model.JobStatus
import hub.core.client.model.MemoryItem
import hub.core.client.model.Skill
import hub.core.client.model.SkillCategory
import hub.core.client.model.SkillLibrary
import hub.core.client.model.SkillSource

/*
 * The rules of an agent's Skills, Memory and Plugins pages and of the cards on the Agents page (apps
 * batch 8), apart from the screens so they are tested without drawing. They follow the web's
 * `agents/AgentSkillsScreen.tsx`, `memoryEntries.ts`, `AgentPluginsScreen.tsx`, `AgentManagerScreen.tsx`
 * and `versionNotes.ts`; iOS `Screens/Agent/AgentToolRules.swift` is the twin.
 */

object SkillRules {
    /** The category folder Core Hub's own library lives in (decision §71). */
    const val LIBRARY_CATEGORY = "core-hub"

    /** The filter chips over the list: all, the person's own (and imported), the library, Hermes's. */
    enum class Filter { ALL, YOURS, LIBRARY, BUILTIN }

    fun inFilter(skill: Skill, filter: Filter): Boolean = when (filter) {
        Filter.ALL -> true
        Filter.YOURS -> skill.source == SkillSource.USER || skill.source == SkillSource.EXTERNAL
        Filter.LIBRARY -> skill.source == SkillSource.LIBRARY
        Filter.BUILTIN -> skill.source == SkillSource.BUILTIN
    }

    /** The categories with only the skills the search and the chip keep; empty categories drop out. */
    fun narrow(categories: List<SkillCategory>, query: String, filter: Filter): List<SkillCategory> {
        val needle = query.trim().lowercase()
        return categories.map { category ->
            category.copy(skills = category.skills.filter { skill ->
                inFilter(skill, filter) && (needle.isEmpty() || skill.name.lowercase().contains(needle) ||
                    skill.key.lowercase().contains(needle) || (skill.description ?: "").lowercase().contains(needle))
            })
        }.filter { it.skills.isNotEmpty() }
    }

    fun total(categories: List<SkillCategory>): Int = categories.sumOf { it.skills.size }

    private val KEY = Regex("^[a-z0-9][a-z0-9._-]{0,63}$")

    /** A new skill's key: its folder's name, as the hub's path accepts it. */
    fun validKey(key: String): Boolean = KEY.matches(key)

    /** What a new skill starts as: the front matter Hermes needs, to fill in. */
    const val TEMPLATE = "---\nname: \ndescription: \n---\n\n"

    /** The hub marks a file it cannot read by putting the reason where the description goes. */
    fun broken(skill: Skill): Boolean = (skill.description ?: "").startsWith("[")

    /** Hermes's own: read, pinned and switched, never written or deleted from here. */
    fun readOnly(skill: Skill): Boolean = skill.source == SkillSource.BUILTIN

    fun edited(skill: Skill): Boolean = skill.source == SkillSource.LIBRARY && skill.library == Skill.Library.EDITED

    enum class Action { OPEN, PIN, UNPIN, RESTORE, DELETE }

    /** A row's menu: open, pin or unpin, restore an edited library skill, delete what is not Hermes's. */
    fun actions(skill: Skill): List<Action> = buildList {
        add(Action.OPEN)
        add(if (skill.pinned) Action.UNPIN else Action.PIN)
        if (edited(skill)) add(Action.RESTORE)
        if (!readOnly(skill)) add(Action.DELETE)
    }

    /** What the library card's line says. */
    enum class LibraryLine { ON, NONE, OFF }

    fun libraryLine(library: SkillLibrary): LibraryLine = when {
        !library.enabled -> LibraryLine.OFF
        library.installed == 0 -> LibraryLine.NONE
        else -> LibraryLine.ON
    }

    /** On, but not every skill of it is here: the card offers Install. */
    fun libraryMissing(library: SkillLibrary): Boolean = library.enabled && library.installed < library.available

    private val PACKS = setOf("md", "markdown", "zip", "skill")

    /** A file the hub takes as a skill pack (`agents.importSkills`): a SKILL.md or a zip. */
    fun importable(fileName: String): Boolean = fileName.substringAfterLast('.', "").lowercase() in PACKS
}

/**
 * The two memory lists as Hermes keeps them (decision §102): short entries separated by a line
 * holding only `§`, within a budget in characters counted as code points (web `memoryEntries.ts`).
 */
object MemoryRules {
    const val SEPARATOR = "\n§\n"
    private val SPLIT = Regex("\n[ \t]*§[ \t]*\n")

    fun entriesOf(text: String): List<String> =
        text.replace("\r\n", "\n").replace('\r', '\n').split(SPLIT).map { it.trim() }.filter { it.isNotEmpty() }

    fun join(entries: List<String>): String = entries.joinToString(SEPARATOR)

    fun lengthOf(entries: List<String>): Int = join(entries).let { it.codePointCount(0, it.length) }

    fun listOf(item: MemoryItem): List<String> = item.propertyEntries ?: entriesOf(item.content.orEmpty())

    /** The two memories are lists; the persona (`soul`) and an entry are one text. */
    fun isList(item: MemoryItem): Boolean = item.kind == MemoryItem.Kind.DOCUMENT && item.id != "soul"

    /** One of the three documents Hermes keeps, which have names of ours. */
    fun known(item: MemoryItem): Boolean = item.id in setOf("soul", "memory", "user")

    enum class Tone { NORMAL, WARNING, DANGER }

    /** Warning from 80 %, danger past the limit. */
    fun tone(count: Int, limit: Int?): Tone = when {
        limit == null || limit <= 0 -> Tone.NORMAL
        count > limit -> Tone.DANGER
        count >= limit * 0.8 -> Tone.WARNING
        else -> Tone.NORMAL
    }

    /** Within the budget, or — for a list already over it — not longer than it is now. */
    fun fits(next: Int, current: Int, limit: Int?): Boolean = limit == null || limit <= 0 || next <= limit || next <= current

    /** The list with entry [index] replaced by what was typed (added last when null); a `§` line makes two. */
    fun withEntry(entries: List<String>, index: Int?, typed: String): List<String> {
        val parts = entriesOf(typed)
        return if (index == null) entries + parts else entries.take(index) + parts + entries.drop(index + 1)
    }

    fun without(entries: List<String>, index: Int): List<String> = entries.filterIndexed { i, _ -> i != index }
}

object PluginRules {
    /** A catalog name, `owner/repo` or a Git URL: one word, never an option (the contract's pattern). */
    fun validIdentifier(text: String): Boolean {
        val value = text.trim()
        return value.isNotEmpty() && value.length <= 400 && !value.startsWith("-") && value.none { it.isWhitespace() }
    }
}

/** What a card on the Agents page offers, as the web's card and its Updates card do. */
object AgentCardRules {
    enum class Action { INSTALL, UNINSTALL, RESTART, CHECK_UPDATE, UPGRADE, AUTO_UPDATE_ON, AUTO_UPDATE_OFF }

    /** An agent that is actually here (web `sections.ts` `configurable`). */
    fun installed(agent: Agent): Boolean = agent.status != AgentStatus.NOT_INSTALLED && agent.install.source != AgentInstall.Source.NONE

    /** Only a Hermes the hub supervises restarts (web `canRestart`). */
    fun canRestart(agent: Agent): Boolean = agent.kind == AgentKind.HERMES && agent.runtime.state != AgentRuntime.State.NOT_APPLICABLE

    /** One the hub installed from a registry, not one that ships with the image. */
    fun updatable(agent: Agent): Boolean =
        installed(agent) && agent.install.source != AgentInstall.Source.BUILTIN && agent.install.source != AgentInstall.Source.NONE

    /** The version an update would install, or null. */
    fun update(agent: Agent): String? = if (updatable(agent) && agent.install.updateAvailable) agent.install.latestVersion else null

    /** The update on offer is past the version Core Hub was tested with. */
    fun updateUntested(agent: Agent): Boolean {
        val update = update(agent) ?: return false
        val tested = agent.install.pinnedVersion ?: return false
        return update != tested
    }

    /** The one button on the card: install what is missing, else take an update, else restart. */
    fun primary(agent: Agent): Action? = when {
        !installed(agent) -> Action.INSTALL
        update(agent) != null -> Action.UPGRADE
        canRestart(agent) -> Action.RESTART
        else -> null
    }

    /** The card's «⋯»: everything else it can do. */
    fun menu(agent: Agent): List<Action> {
        if (!installed(agent)) return emptyList()
        val primary = primary(agent)
        return buildList {
            if (canRestart(agent)) add(Action.RESTART)
            if (updatable(agent)) {
                add(Action.CHECK_UPDATE)
                if (update(agent) != null) add(Action.UPGRADE)
                if (agent.install.autoUpdateSupported) add(if (agent.install.autoUpdate) Action.AUTO_UPDATE_OFF else Action.AUTO_UPDATE_ON)
            }
            if (agent.install.source == AgentInstall.Source.MANAGED) add(Action.UNINSTALL)
        }.filter { it != primary }
    }

    fun terminal(status: JobStatus): Boolean = status == JobStatus.SUCCEEDED || status == JobStatus.FAILED || status == JobStatus.CANCELLED

    /** How far along the bar is, 0…1: the job's percent, else a third while it runs. */
    fun progress(job: Job): Float = job.progress.percent?.coerceIn(0, 100)?.div(100f) ?: if (terminal(job.status)) 1f else 0.3f

    /** An install's or update's `result.version`, when the job says it. */
    fun resultVersion(job: Job): String? =
        (job.result?.get("version") as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
}

/** The pages' refusals in our words where the hub names why (web `toolErrors.ts`); null keeps the hub's sentence. */
object AgentToolErrors {
    private val IMPORT = mapOf(
        "pack_unrecognised" to R.string.agents_import_pack_unrecognised,
        "pack_has_no_skill" to R.string.agents_import_pack_has_no_skill,
        "pack_path_unsafe" to R.string.agents_import_pack_path_unsafe,
        "pack_unsupported" to R.string.agents_import_pack_unsupported,
        "pack_corrupt" to R.string.agents_import_pack_corrupt,
        "pack_too_large" to R.string.agents_import_pack_too_large,
        "skill_empty" to R.string.agents_import_skill_empty,
        "skill_too_large" to R.string.agents_import_skill_too_large,
        "skill_front_matter_missing" to R.string.agents_import_skill_front_matter_missing,
        "skill_front_matter_unclosed" to R.string.agents_import_skill_front_matter_unclosed,
        "skill_front_matter_invalid" to R.string.agents_import_skill_front_matter_invalid,
        "skill_name_required" to R.string.agents_import_skill_name_required,
        "skill_description_required" to R.string.agents_import_skill_description_required,
        "skill_description_too_long" to R.string.agents_import_skill_description_too_long,
        "skill_body_empty" to R.string.agents_import_skill_body_empty,
        "skill_name_invalid" to R.string.agents_import_skill_name_invalid,
        "skill_not_utf8" to R.string.agents_import_skill_not_utf8,
        "skill_nested" to R.string.agents_import_skill_nested,
        "skill_duplicate" to R.string.agents_import_skill_duplicate,
        "skill_exists" to R.string.agents_import_skill_exists,
    )

    private val OTHER = mapOf(
        "skill_bundled" to R.string.agents_skill_bundled_refused,
        "skill_essential" to R.string.agents_skill_essential_refused,
        "skill_not_library" to R.string.agents_skill_not_library,
        "skill_library_off" to R.string.agents_skill_library_off_refused,
        "plugin_bundled" to R.string.agents_plugin_bundled_refused,
        "hermes_not_supervised" to R.string.agents_tools_not_supervised,
        "memory_too_long" to R.string.agents_memory_too_long_refused,
    )

    /** The string that leads the refusal, and whether the hub's own sentence follows it (an import's precise reason). */
    fun lead(error: HubError): Pair<Int, Boolean>? {
        val reason = error.reason ?: return null
        IMPORT[reason]?.let { return it to true }
        return OTHER[reason]?.let { it to false }
    }
}
