package ai.rever.boss.components.workspaces

import ai.rever.boss.cli.CLISecurityValidator
import ai.rever.boss.plugin.workspace.LayoutWorkspace
import ai.rever.boss.plugin.workspace.SplitConfig

/**
 * Longest terminal command the Space approval prompt will show. The same display
 * bound as the terminal prompt (`TERMINAL_CONFIRM_MAX_COMMAND_LENGTH`): a
 * command the prompt cannot show in full is not one anybody can meaningfully
 * approve, so a longer one from outside the operator's own invocation is dropped
 * rather than prompted. If one bound ever changes, both must.
 */
internal const val SPACE_LOAD_CONFIRM_MAX_COMMAND_LENGTH = 512

/**
 * Most terminal commands one Space approval prompt will list. A Space with more
 * cannot be shown to the operator in full, so an external load carrying that
 * many is dropped rather than half-prompted.
 */
internal const val SPACE_LOAD_CONFIRM_MAX_COMMAND_COUNT = 16

/** What BOSS does with a request to load a Space. */
internal enum class SpaceLoadDisposition {
    /** Load the Space, typing its terminal commands as saved. */
    LOAD,

    /** Show the operator every terminal command and load only if they confirm. */
    CONFIRM,

    /** Do nothing at all. */
    REJECT,
}

/**
 * Decides what happens to a Space load, from who asked for it and what the
 * Space's terminal tabs would type into a shell.
 *
 * `boss://workspace?path=` reaches the same PTY the `boss://terminal` gate
 * guards: applying a Space types each terminal tab's `initialCommand` into a
 * new terminal. A load the operator asked for themselves ([requiresConfirmation]
 * false — the operator's own `boss workspace <file>`, or an in-app load, which
 * never travels through the deep link) behaves exactly as before, whatever the
 * Space carries. A load some other program asked the OS to open is held for the
 * operator to confirm when it carries terminal commands, and dropped outright
 * when a command is malformed, too long to display in full, or there are too
 * many to list — the same shape rules the terminal prompt applies
 * (`terminalCommandDisposition`). A Space with no terminal commands types
 * nothing into a shell and loads unchanged either way.
 */
internal fun spaceLoadDisposition(
    commands: List<String>,
    requiresConfirmation: Boolean,
): SpaceLoadDisposition =
    when {
        !requiresConfirmation -> SpaceLoadDisposition.LOAD
        commands.isEmpty() -> SpaceLoadDisposition.LOAD
        commands.size > SPACE_LOAD_CONFIRM_MAX_COMMAND_COUNT -> SpaceLoadDisposition.REJECT
        commands.any(::isUnshowableSpaceCommand) -> SpaceLoadDisposition.REJECT
        else -> SpaceLoadDisposition.CONFIRM
    }

/** True when the prompt cannot show this command in full, so nobody can meaningfully approve it. */
private fun isUnshowableSpaceCommand(command: String): Boolean =
    command.length > SPACE_LOAD_CONFIRM_MAX_COMMAND_LENGTH || !CLISecurityValidator.isValidCommand(command)

/**
 * Every terminal command a Space would type into a shell when applied, in tab
 * order: the non-blank `initialCommand` of each terminal tab in each panel.
 */
internal fun LayoutWorkspace.spaceTerminalCommands(): List<String> = layout.terminalTabCommands()

private fun SplitConfig.terminalTabCommands(): List<String> =
    when (this) {
        is SplitConfig.SinglePanel -> panel.tabs.mapNotNull { tab -> tab.initialCommand?.takeIf { it.isNotBlank() } }
        is SplitConfig.VerticalSplit -> left.terminalTabCommands() + right.terminalTabCommands()
        is SplitConfig.HorizontalSplit -> top.terminalTabCommands() + bottom.terminalTabCommands()
    }
