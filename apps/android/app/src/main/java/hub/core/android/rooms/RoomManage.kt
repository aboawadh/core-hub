package hub.core.android.rooms

import hub.core.android.ui.components.FormField
import hub.core.android.ui.components.FormKind
import hub.core.android.ui.components.FormOption
import hub.core.android.ui.screens.ChatAgents
import hub.core.client.model.Agent
import hub.core.client.model.HandoffChain
import hub.core.client.model.HandoffPolicy
import hub.core.client.model.Project
import hub.core.client.model.ProjectWrite
import hub.core.client.model.RoomPatch
import hub.core.client.model.Seat
import hub.core.client.model.SeatConfig
import hub.core.client.model.SeatPatch
import java.math.BigDecimal

/**
 * The rules of managing a room on the phone, as the web's Rooms page has them (DECISIONS §69):
 * which actions a person may take on a room, the seat form (agent, name in the room, role,
 * instructions, model), the room's settings (@all, passing the turn and how far), and what the
 * handoff strip says. Plain functions, tested without drawing (RoomManageTest); iOS's
 * RoomManage.swift has the same rules.
 */
object RoomManage {
    /** What can be done to a room from its menu or a long press on its row. */
    enum class Action { RENAME, SETTINGS, CLEAR_CONTEXT, ARCHIVE, UNARCHIVE, DELETE, LEAVE }

    /**
     * The room's own menu: a manager renames, sets, clears, archives or deletes it; anyone else may
     * only leave. The maker of a room never leaves it (they delete it instead).
     */
    fun actions(canManage: Boolean, archived: Boolean, owner: Boolean = false): List<Action> = when {
        canManage -> listOf(Action.RENAME, Action.SETTINGS, Action.CLEAR_CONTEXT, if (archived) Action.UNARCHIVE else Action.ARCHIVE, Action.DELETE)
        owner -> emptyList()
        else -> listOf(Action.LEAVE)
    }

    /** A long press on a row of the rooms list: the same, without the room's inner settings. */
    fun rowActions(canManage: Boolean, archived: Boolean): List<Action> =
        actions(canManage, archived).filter { it != Action.SETTINGS && it != Action.CLEAR_CONTEXT }

    // Renaming

    const val NAME = "name"

    /** A room's new name, trimmed; null when nothing is left or nothing changed. */
    fun renamed(value: String, current: String): RoomPatch? {
        val name = value.trim()
        if (name.isEmpty() || name.length > 120 || name == current) return null
        return RoomPatch(name = name)
    }

    // Seats

    object SeatKey {
        const val AGENT = "agent"
        const val NAME = "name"
        const val ROLE = "role"
        const val INSTRUCTIONS = "instructions"
        const val MODEL = "model"
    }

    /** The words of the seat form, read by the screen from its strings. */
    data class SeatLabels(
        val agent: String,
        val name: String,
        val nameHint: String,
        val nameAddHint: String,
        val role: String,
        val instructions: String,
        val instructionsHint: String,
        val model: String,
        val modelHint: String,
    )

    /** Agents a seat can be: installed and switched on (the web's `installedAgents`). */
    fun seatable(agents: List<Agent>): List<Agent> = ChatAgents.startable(agents)

    /**
     * The seat form. The agent is chosen only when adding: an existing seat keeps its agent (a
     * different agent is a new seat). A new seat's name may stay empty: it is then the agent's.
     */
    fun seatFields(adding: Boolean, agents: List<Agent>, labels: SeatLabels): List<FormField> = buildList {
        if (adding) {
            add(FormField(SeatKey.AGENT, labels.agent, FormKind.Choice, required = true, options = seatable(agents).map { FormOption(it.id, it.name) }))
        }
        add(FormField(SeatKey.NAME, labels.name, required = !adding, help = if (adding) labels.nameAddHint else labels.nameHint))
        add(FormField(SeatKey.ROLE, labels.role))
        add(FormField(SeatKey.INSTRUCTIONS, labels.instructions, FormKind.Multiline, help = labels.instructionsHint))
        add(FormField(SeatKey.MODEL, labels.model, help = labels.modelHint, mono = true))
    }

    fun seatValues(seat: Seat?, agents: List<Agent>): Map<String, String> =
        if (seat == null) mapOf(SeatKey.AGENT to (seatable(agents).firstOrNull()?.id ?: ""))
        else mapOf(
            SeatKey.NAME to seat.name,
            SeatKey.ROLE to seat.description.orEmpty(),
            SeatKey.INSTRUCTIONS to seat.instructions.orEmpty(),
            SeatKey.MODEL to seat.model.orEmpty(),
        )

    private fun Map<String, String>.trimmed(key: String) = this[key].orEmpty().trim()

    /** A new seat. Empty role, instructions and model are left out (the model then is the agent's own). */
    fun seatConfig(values: Map<String, String>, agents: List<Agent>): SeatConfig? {
        val agent = agents.firstOrNull { it.id == values.trimmed(SeatKey.AGENT) } ?: return null
        val name = values.trimmed(SeatKey.NAME).ifEmpty { agent.name.trim() }.ifEmpty { return null }
        return SeatConfig(
            agentId = agent.id,
            name = name,
            description = values.trimmed(SeatKey.ROLE).ifEmpty { null },
            model = values.trimmed(SeatKey.MODEL).ifEmpty { null },
            instructions = values.trimmed(SeatKey.INSTRUCTIONS).ifEmpty { null },
        )
    }

    /**
     * An edited seat, every field sent: an emptied role, instructions or model goes as `null`, so the
     * seat has none (and answers with the agent's own model), as on the web.
     */
    fun seatPatch(values: Map<String, String>): SeatPatch {
        val role = values.trimmed(SeatKey.ROLE).ifEmpty { null }
        val instructions = values.trimmed(SeatKey.INSTRUCTIONS).ifEmpty { null }
        val model = values.trimmed(SeatKey.MODEL).ifEmpty { null }
        return SeatPatch(
            name = values.trimmed(SeatKey.NAME).ifEmpty { null },
            description = role,
            model = model,
            instructions = instructions,
            sendNull = buildSet {
                if (role == null) add(SeatPatch.Clearable.DESCRIPTION)
                if (instructions == null) add(SeatPatch.Clearable.INSTRUCTIONS)
                if (model == null) add(SeatPatch.Clearable.MODEL)
            },
        )
    }

    /** The seat's second line: its role and its model (or «the agent's own model»). */
    fun seatLine(seat: Seat, defaultModel: String): String =
        listOf(seat.description, seat.model ?: defaultModel).mapNotNull { it?.trim()?.ifEmpty { null } }.joinToString(" · ")

    // Settings

    object SettingsKey {
        const val MENTION_ALL = "mention_all"
        const val HANDOFF = "handoff"
        const val MAX_DEPTH = "max_depth"
        const val PROJECT = "project"
    }

    /** The project choice's «no project». */
    const val NO_PROJECT = "none"

    /** The words of the project choice, read by the screen from its strings. */
    data class ProjectLabels(val label: String, val none: String, val hint: String)

    /**
     * The room's settings; with [projects] (when they could be read), which project reports its
     * tasks' progress into the room.
     */
    fun settingsFields(
        mentionAll: String, handoff: String, handoffHint: String, maxDepth: String, maxDepthHint: String,
        projects: List<Project>? = null, projectLabels: ProjectLabels? = null,
    ): List<FormField> = listOf(
        FormField(SettingsKey.MENTION_ALL, mentionAll, FormKind.Toggle),
        FormField(SettingsKey.HANDOFF, handoff, FormKind.Toggle, help = handoffHint),
        FormField(
            SettingsKey.MAX_DEPTH, maxDepth, FormKind.Number, help = maxDepthHint,
            min = BigDecimal.ONE, max = BigDecimal(20), integer = true, mono = true,
        ),
    ) + listOfNotNull(
        if (projects != null && projectLabels != null) {
            FormField(
                SettingsKey.PROJECT, projectLabels.label, FormKind.Choice, help = projectLabels.hint,
                options = listOf(FormOption(NO_PROJECT, projectLabels.none)) + projects.map { FormOption(it.id, it.name) },
            )
        } else null,
    )

    fun settingsValues(canMentionAll: Boolean, handoff: HandoffPolicy?, linkedProject: String? = null): Map<String, String> = mapOf(
        SettingsKey.MENTION_ALL to canMentionAll.toString(),
        SettingsKey.HANDOFF to (handoff?.enabled ?: true).toString(),
        SettingsKey.MAX_DEPTH to (handoff?.maxDepth?.toString() ?: ""),
        SettingsKey.PROJECT to (linkedProject ?: NO_PROJECT),
    )

    /** The project that reports into this room (one at a time, as the web links it). */
    fun linkedProject(projects: List<Project>, roomId: String): String? = projects.firstOrNull { it.reportRoomId == roomId }?.id

    /** One project write: which project, and what it is told. */
    data class ProjectLink(val projectId: String, val write: ProjectWrite)

    /**
     * Changing the project that reports here: the one linked before stops (`report_room_id: null`),
     * then the chosen one starts. Nothing when the choice did not change.
     */
    fun projectLinks(values: Map<String, String>, linked: String?, roomId: String): List<ProjectLink> {
        val chosen = values[SettingsKey.PROJECT] ?: return emptyList()
        val next = chosen.takeUnless { it == NO_PROJECT || it.isEmpty() }
        if (next == linked) return emptyList()
        return listOfNotNull(
            linked?.let { ProjectLink(it, ProjectWrite(sendNull = setOf(ProjectWrite.Clearable.REPORT_ROOM_ID))) },
            next?.let { ProjectLink(it, ProjectWrite(reportRoomId = roomId)) },
        )
    }

    /**
     * The settings to send. The passing-the-turn policy goes only when it changed, whole (`enabled`
     * with `max_depth`, empty = no limit); null when nothing changed at all.
     */
    fun settingsPatch(values: Map<String, String>, canMentionAll: Boolean, handoff: HandoffPolicy?): RoomPatch? {
        val all = values[SettingsKey.MENTION_ALL] == "true"
        val enabled = values[SettingsKey.HANDOFF] == "true"
        val depth = values.trimmed(SettingsKey.MAX_DEPTH).toIntOrNull()
        val policyChanged = handoff == null || handoff.enabled != enabled || handoff.maxDepth != depth
        val allChanged = all != canMentionAll
        if (!policyChanged && !allChanged) return null
        return RoomPatch(
            handoff = if (policyChanged) HandoffPolicy(enabled = enabled, maxDepth = depth) else null,
            canMentionAll = if (allChanged) all else null,
        )
    }

    // Passing the turn

    sealed interface HandoffLine {
        /** A chain going on now: [from] passed to [to], the [depth]th pass in a row. */
        data class Active(val from: String, val to: String, val depth: Int) : HandoffLine

        /** The newest chain was stopped; [more] when a person may give it one more round. */
        data class Stopped(val id: String, val reason: HandoffChain.StopReason, val from: String, val to: String, val more: Boolean) : HandoffLine
    }

    /**
     * What the strip over the composer says: the chain going on now, else the newest chain when the
     * guard stopped it and its one more round is unused (an older stopped chain was overtaken by
     * what the room did since).
     */
    fun handoffLine(active: List<HandoffChain>, listed: List<HandoffChain>, seats: List<Seat>): HandoffLine? {
        val name = { id: String -> seats.firstOrNull { it.id == id }?.name ?: "?" }
        active.firstOrNull { it.status == HandoffChain.Status.ACTIVE }?.let {
            return HandoffLine.Active(name(it.fromSeatId), name(it.toSeatId), it.depth)
        }
        val newest = listed.maxByOrNull { it.updatedAt } ?: return null
        if (newest.status != HandoffChain.Status.STOPPED || newest.continueUsed) return null
        val reason = newest.stopReason ?: HandoffChain.StopReason.INTERRUPTED
        return HandoffLine.Stopped(newest.id, reason, name(newest.fromSeatId), name(newest.toSeatId), reason != HandoffChain.StopReason.INTERRUPTED)
    }
}
