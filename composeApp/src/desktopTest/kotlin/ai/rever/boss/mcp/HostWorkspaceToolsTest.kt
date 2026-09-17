package ai.rever.boss.mcp

import ai.rever.boss.components.window_panel.SplitViewState
import ai.rever.boss.components.window_panel.SplitViewStateRegistry
import ai.rever.boss.components.workspaces.LayoutWorkspace
import ai.rever.boss.components.workspaces.PanelConfig
import ai.rever.boss.components.workspaces.TabConfig
import ai.rever.boss.components.workspaces.extractCurrentWorkspace
import ai.rever.boss.components.workspaces.workspaceManager
import ai.rever.boss.plugin.api.TabComponentWithUI
import ai.rever.boss.plugin.api.TabInfo
import ai.rever.boss.plugin.api.TabRegistry
import ai.rever.boss.plugin.api.TabTypeInfo
import ai.rever.boss.plugin.tab.terminal.TerminalTabType
import ai.rever.boss.plugin.workspace.SplitConfig
import ai.rever.boss.window.WindowProjectStateRegistry
import androidx.compose.runtime.Composable
import com.arkivanov.decompose.ComponentContext
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * open_workspace, driven through the real invocation path (McpToolRegistryCore, the same
 * construction McpToolSandboxTest uses) so these tests pin the exact contract an MCP client
 * gets: policy resolution, argument parsing, error text and the JSON payload shape.
 *
 * The window is headless but real: a SplitViewState whose TabRegistry has a stub factory
 * for TerminalTabType, the same construction WorkspaceApplierMigrationTest and
 * ProjectChangeAnnouncementTest use, so the whole preserve/load/apply chain runs and the
 * assertions see what a user would see on screen.
 */
class HostWorkspaceToolsTest {
    private val windowId = "host-workspace-tools-test-window"

    /** Minimal stand-in; the applier only builds TabInfo, it never renders the component. */
    private class StubTabComponent(
        ctx: ComponentContext,
        override val config: TabInfo,
        override val tabTypeInfo: TabTypeInfo,
    ) : TabComponentWithUI,
        ComponentContext by ctx {
        @Composable
        override fun Content() = Unit
    }

    private val tabRegistry =
        TabRegistry().apply {
            registerTabType(TerminalTabType) { config, ctx -> StubTabComponent(ctx, config, TerminalTabType) }
        }

    @AfterTest
    fun tearDown() {
        SplitViewStateRegistry.unregister(windowId)
        WindowProjectStateRegistry.unregister(windowId)
    }

    private fun core() =
        McpToolRegistryCore(disabledFile = null).also {
            it.registerProvider(HostWorkspaceTools.provider)
        }

    // ---------------------------------------------------------------- errors an agent can act on

    @Test
    fun `missing path argument is a clear error`() =
        runBlocking {
            val result = core().invoke(HostWorkspaceTools.OPEN_WORKSPACE_TOOL_NAME, "{}")

            assertTrue(result.isError)
            assertTrue(result.text.contains("path"), result.text)
        }

    @Test
    fun `relative path is refused rather than resolved against the process directory`() =
        runBlocking {
            val result = core().invoke(
                HostWorkspaceTools.OPEN_WORKSPACE_TOOL_NAME,
                """{"path":"some/relative/dir"}""",
            )

            assertTrue(result.isError)
            assertTrue(result.text.contains("absolute"), result.text)
        }

    @Test
    fun `missing directory is a clear error`() =
        runBlocking {
            val missing = createTempDirectory("host-tools").resolve("no-such-directory").toString()

            val result = core().invoke(HostWorkspaceTools.OPEN_WORKSPACE_TOOL_NAME, """{"path":"$missing"}""")

            assertTrue(result.isError)
            assertTrue(result.text.contains("not an existing directory"), result.text)
        }

    @Test
    fun `a file path is rejected the same as a missing directory`() =
        runBlocking {
            val file = createTempDirectory("host-tools").resolve("plain-file.txt").toFile().apply { writeText("content") }

            val result = core().invoke(HostWorkspaceTools.OPEN_WORKSPACE_TOOL_NAME, """{"path":"${file.path}"}""")

            assertTrue(result.isError)
            assertTrue(result.text.contains("not an existing directory"), result.text)
        }

    @Test
    fun `unknown window id is rejected with the open window list`() =
        runBlocking {
            SplitViewStateRegistry.register(windowId, SplitViewState(tabRegistry, windowId))
            val project = createTempDirectory("host-tools-project")

            val result =
                core().invoke(
                    HostWorkspaceTools.OPEN_WORKSPACE_TOOL_NAME,
                    """{"path":"$project","window_id":"no-such-window"}""",
                )

            assertTrue(result.isError)
            assertTrue(result.text.contains("no-such-window"), result.text)
            assertTrue(result.text.contains(windowId), result.text)
        }

    @Test
    fun `no open window is a clear error`() =
        runBlocking {
            val project = createTempDirectory("host-tools-project")

            val result = core().invoke(HostWorkspaceTools.OPEN_WORKSPACE_TOOL_NAME, """{"path":"$project"}""")

            assertTrue(result.isError)
            assertTrue(result.text.contains("No open BOSS window"), result.text)
        }

    // ------------------------------------------------- the happy path, headless but through the real chain

    @Test
    fun `opening a project returns usable ids and points the terminal at the project`() =
        runBlocking {
            SplitViewStateRegistry.register(windowId, SplitViewState(tabRegistry, windowId))
            val project = createTempDirectory("host-tools-project")
            val canonical = File(project.toString()).canonicalPath

            val result =
                core().invoke(HostWorkspaceTools.OPEN_WORKSPACE_TOOL_NAME, """{"path":"$project"}""")

            assertFalse(result.isError, result.text)
            val payload = Json.parseToJsonElement(result.text).jsonObject
            assertEquals("opened", payload["status"]?.jsonPrimitive?.content)
            assertEquals(windowId, payload["window_id"]?.jsonPrimitive?.content)
            assertEquals(canonical, payload["project_path"]?.jsonPrimitive?.content)
            val workspaceId = payload["workspace_id"]?.jsonPrimitive?.content
            assertFalse(workspaceId.isNullOrEmpty(), "workspace_id is missing: ${result.text}")

            assertEquals(workspaceId, workspaceManager.currentWorkspace.value?.id)

            val state = SplitViewStateRegistry.getState(windowId) ?: error("window disappeared")
            val onScreen = extractCurrentWorkspace(state, projectPath = canonical)
            val tab = (onScreen.layout as SplitConfig.SinglePanel).panel.tabs.single()
            assertEquals("terminal", tab.type)
            assertEquals(canonical, tab.workingDirectory)
        }

    @Test
    fun `re-opening the same path reuses the space instead of duplicating it`() =
        runBlocking {
            SplitViewStateRegistry.register(windowId, SplitViewState(tabRegistry, windowId))
            val project = createTempDirectory("host-tools-project")
            val registryCore = core()

            val first =
                registryCore.invoke(HostWorkspaceTools.OPEN_WORKSPACE_TOOL_NAME, """{"path":"$project"}""")
            assertFalse(first.isError, first.text)
            val firstId = Json.parseToJsonElement(first.text).jsonObject["workspace_id"]!!.jsonPrimitive.content

            val second =
                registryCore.invoke(HostWorkspaceTools.OPEN_WORKSPACE_TOOL_NAME, """{"path":"$project"}""")
            assertFalse(second.isError, second.text)
            val payload = Json.parseToJsonElement(second.text).jsonObject
            assertEquals("reused", payload["status"]?.jsonPrimitive?.content)
            assertEquals(firstId, payload["workspace_id"]?.jsonPrimitive?.content)

            // and the panel did not grow a second terminal for the same project
            val state = SplitViewStateRegistry.getState(windowId) ?: error("window disappeared")
            val onScreen = extractCurrentWorkspace(state, projectPath = File(project.toString()).canonicalPath)
            assertEquals(1, (onScreen.layout as SplitConfig.SinglePanel).panel.tabs.size)
        }

    // ----------------------------------------------------------------------------- pure helpers

    @Test
    fun `tilde expands to the home directory only at the start of a path`() {
        assertEquals("/home/boss/projects", expandTilde("~/projects", home = "/home/boss"))
        assertEquals("/home/boss", expandTilde("~", home = "/home/boss"))
        assertEquals("/opt/~literal/projects", expandTilde("/opt/~literal/projects", home = "/home/boss"))
        assertEquals("~/unchanged", expandTilde("~/unchanged", home = null))
    }

    @Test
    fun `bootstrap space is one terminal panel named for the project`() {
        val space = buildBootstrapSpace("/work/some-project")

        assertEquals("some-project", space.name)
        assertEquals("/work/some-project", space.projectPath)
        val panel = (space.layout as SplitConfig.SinglePanel).panel
        assertEquals(BOOTSTRAP_PANEL_ID, panel.id)
        val tab = panel.tabs.single()
        assertEquals("terminal", tab.type)
        assertEquals("/work/some-project", tab.workingDirectory)
    }

    @Test
    fun `a running saved space is reused before a non-running one`() {
        val running = savedSpace("workspace-running", "/work/p")
        val shelved = savedSpace("workspace-shelved", "/work/p")

        val match =
            matchExistingSpace(
                remembered = null,
                savedSpaces = listOf(shelved, running),
                runningIdsInWindow = setOf("workspace-running"),
                projectPath = "/work/p",
            )

        assertEquals("workspace-running", match?.id)
    }

    @Test
    fun `spaces for other projects never match`() {
        val other = savedSpace("workspace-other", "/work/other")

        assertNull(
            matchExistingSpace(
                remembered = null,
                savedSpaces = listOf(other),
                runningIdsInWindow = setOf("workspace-other"),
                projectPath = "/work/p",
            ),
        )
    }

    @Test
    fun `a remembered space is reused even when it is not running`() {
        val remembered = buildBootstrapSpace("/work/p")

        val match =
            matchExistingSpace(
                remembered = remembered,
                savedSpaces = emptyList(),
                runningIdsInWindow = emptySet(),
                projectPath = "/work/p",
            )

        assertEquals(remembered.id, match?.id)
    }

    @Test
    fun `result payload carries the ids a caller needs`() {
        val space = buildBootstrapSpace("/work/p")

        val payload =
            Json.parseToJsonElement(
                buildOpenResult(reused = true, windowId = "w-1", space = space, projectPath = "/work/p"),
            ).jsonObject

        assertEquals("reused", payload["status"]?.jsonPrimitive?.content)
        assertEquals("w-1", payload["window_id"]?.jsonPrimitive?.content)
        assertEquals(space.id, payload["workspace_id"]?.jsonPrimitive?.content)
        assertEquals(BOOTSTRAP_PANEL_ID, payload["panel_id"]?.jsonPrimitive?.content)
        assertEquals("/work/p", payload["project_path"]?.jsonPrimitive?.content)
    }

    private fun savedSpace(
        id: String,
        projectPath: String,
    ) = LayoutWorkspace(
        id = id,
        name = id,
        description = "",
        layout = SplitConfig.SinglePanel(
            PanelConfig(id = "panel-$id", tabs = listOf(TabConfig(type = "terminal", title = "Terminal"))),
        ),
        projectPath = projectPath,
    )
}
