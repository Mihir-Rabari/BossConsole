package ai.rever.boss.mcp

import ai.rever.boss.components.window_panel.SplitViewState
import ai.rever.boss.components.window_panel.SplitViewStateRegistry
import ai.rever.boss.components.workspaces.LayoutWorkspace
import ai.rever.boss.components.workspaces.PanelConfig
import ai.rever.boss.components.workspaces.TabConfig
import ai.rever.boss.components.workspaces.applyWorkspace
import ai.rever.boss.components.workspaces.extractPanels
import ai.rever.boss.components.workspaces.workspaceManager
import ai.rever.boss.plugin.api.McpToolArgs
import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolHandler
import ai.rever.boss.plugin.api.McpToolProvider
import ai.rever.boss.plugin.api.McpToolResult
import ai.rever.boss.plugin.tab.terminal.TerminalTabType
import ai.rever.boss.plugin.workspace.SplitConfig
import ai.rever.boss.utils.extractFileName
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import ai.rever.boss.window.WindowProjectStateRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock

/**
 * Host-side MCP tools for bootstrapping a Space without a human clicking the UI first (#780).
 *
 * With no Space open there is no terminal tab, so an agent's terminal tools fail to register
 * and the only way forward was a human opening something. This provider contributes
 * [OPEN_WORKSPACE_TOOL_NAME]: it opens a project directory as a Space in a BOSS window,
 * which creates the first terminal tab and returns the ids the panel-scoped terminal tools
 * (`run_in_panel` and friends) need to work.
 *
 * It is host-side rather than a plugin because it has to exist before any plugin content
 * does, and it goes through the same doors the UI goes through - [workspaceManager.loadWorkspace]
 * plus [applyWorkspace] - so a Space an agent opens behaves exactly like one a user opens.
 */
internal object HostWorkspaceTools {
    private val logger = BossLogger.forComponent("HostWorkspaceTools")

    /** Name of the single tool this provider ships. */
    const val OPEN_WORKSPACE_TOOL_NAME = "open_workspace"

    private const val PROVIDER_ID = "boss-host"
    private const val PATH_ARG = "path"
    private const val WINDOW_ID_ARG = "window_id"

    private val OPEN_WORKSPACE_DESCRIPTION =
        "Open a project directory as a Space in a BOSS window, creating a terminal tab that the " +
            "panel-scoped terminal tools (run_in_panel, run_in_sidebar, send_input) can register against. " +
            "Use this first when no Space is open yet. Returns window_id, workspace_id and panel_id. " +
            "Re-opening a path that is already running reuses the running Space instead of duplicating it."

    private val OPEN_WORKSPACE_INPUT_SCHEMA =
        """
        {
            "type": "object",
            "properties": {
                "path": {
                    "type": "string",
                    "description": "Absolute path of an existing project directory"
                },
                "window_id": {
                    "type": "string",
                    "description": "Optional id of the window to host the Space in; defaults to the first window with no project open"
                }
            },
            "required": ["path"]
        }
        """.trimIndent()

    /**
     * BossApp runs its startup effect per window, but the registry is a process-wide
     * singleton and the handler resolves its window at invoke time, so the window that
     * wires this first is not privileged: register once, keep it.
     */
    private val registeredOnce = AtomicBoolean(false)

    /**
     * Spaces this tool created, by window then by canonical project path, so a second open
     * of the same project re-enters the running Space instead of clearing the window and
     * rebuilding its tabs, which would restart a terminal an agent may be mid-session in.
     * The manager's windowWorkspaces map covers saved Spaces, but an unsaved bootstrap
     * Space is not in the manager's list, so the tool remembers its own.
     */
    private val createdSpaces = ConcurrentHashMap<String, ConcurrentHashMap<String, LayoutWorkspace>>()

    internal val provider =
        object : McpToolProvider {
            override val providerId = PROVIDER_ID

            override fun tools() = listOf(openWorkspaceTool())
        }

    /** Register the host tools the first time a window's startup effect runs. Idempotent. */
    fun ensureRegistered() {
        if (!registeredOnce.compareAndSet(false, true)) return
        McpToolRegistryImpl.registerProvider(provider)
        logger.info(
            LogCategory.WORKSPACE,
            "Registered host workspace bootstrap MCP tools",
            mapOf("tool" to OPEN_WORKSPACE_TOOL_NAME),
        )
    }

    private fun openWorkspaceTool() =
        McpToolDefinition.withRbac(
            name = OPEN_WORKSPACE_TOOL_NAME,
            description = OPEN_WORKSPACE_DESCRIPTION,
            handler = McpToolHandler { args -> openWorkspace(args) },
            inputSchema = OPEN_WORKSPACE_INPUT_SCHEMA,
            // Honest hint: this changes what is on screen, it does not just read state.
            readOnly = false,
        )

    private suspend fun openWorkspace(args: McpToolArgs): McpToolResult {
        val rawPath = args.string(PATH_ARG)
        if (rawPath.isNullOrBlank()) {
            return McpToolResult(
                "Missing required argument '$PATH_ARG': pass the absolute path of an existing project directory.",
                isError = true,
            )
        }
        val expandedPath = expandTilde(rawPath)
        if (!File(expandedPath).isAbsolute) {
            return McpToolResult(
                "Path must be absolute (got '$rawPath'): a relative path would resolve against the BOSS " +
                    "process's working directory, not the caller's.",
                isError = true,
            )
        }
        val projectPath =
            withContext(Dispatchers.IO) { canonicalizeOrNull(expandedPath) }
                ?: return McpToolResult("Path is not an existing directory: $rawPath", isError = true)

        val requestedWindowId = args.string(WINDOW_ID_ARG)
        val windowId =
            if (requestedWindowId != null) {
                if (SplitViewStateRegistry.getState(requestedWindowId) == null) {
                    val openWindows =
                        SplitViewStateRegistry
                            .getAllStates()
                            .keys
                            .joinToString(", ")
                            .ifEmpty { "(none)" }
                    return McpToolResult(
                        "No open window with id '$requestedWindowId'. Open windows: $openWindows",
                        isError = true,
                    )
                }
                requestedWindowId
            } else {
                pickDefaultWindow()
                    ?: return McpToolResult(
                        "No open BOSS window is available to host the Space. Open a BOSS window first, then retry.",
                        isError = true,
                    )
            }

        // Resolved above, but the window can close between resolution and use.
        val splitViewState = SplitViewStateRegistry.getState(windowId)
        if (splitViewState == null) {
            return McpToolResult("Window '$windowId' closed while opening the Space.", isError = true)
        }

        try {
            val runningIds = workspaceManager.windowWorkspaces.value[windowId].orEmpty()
            val existing =
                matchExistingSpace(
                    remembered = createdSpaces[windowId]?.get(projectPath),
                    savedSpaces = workspaceManager.workspaces.value,
                    runningIdsInWindow = runningIds,
                    projectPath = projectPath,
                )
            val space =
                existing ?: buildBootstrapSpace(projectPath).also { fresh ->
                    createdSpaces.getOrPut(windowId) { ConcurrentHashMap() }[projectPath] = fresh
                }

            val windowProjectState = WindowProjectStateRegistry.getOrCreate(windowId)
            withContext(Dispatchers.Main) {
                // Preserve, load, apply: the same three steps the top bar's Space switch
                // takes (BossAppEventBusEffects), so re-entering a running Space restores its
                // preserved tree instead of clearing the window and rebuilding the tabs,
                // which would restart a terminal an agent may be mid-session in.
                val currentWorkspace = workspaceManager.currentWorkspace.value
                if (currentWorkspace != null && currentWorkspace.id.isNotEmpty()) {
                    splitViewState.preserveCurrentState(currentWorkspace.id, currentWorkspace.name)
                }
                workspaceManager.loadWorkspace(space)
                applyWorkspace(space, splitViewState, windowProjectState)
            }

            if (!splitViewState.tabRegistry.isRegistered(TerminalTabType.typeId)) {
                return McpToolResult(
                    "The Space is open, but the terminal tab type is not registered, so terminal tools have " +
                        "no panel to attach to. Check that the terminal plugin is installed and enabled, then retry.",
                    isError = true,
                )
            }

            logger.info(
                LogCategory.WORKSPACE,
                "MCP open_workspace opened a Space",
                mapOf(
                    "windowId" to windowId,
                    "workspaceId" to space.id,
                    "reused" to (existing != null).toString(),
                ),
            )
            return McpToolResult(
                buildOpenResult(
                    reused = existing != null,
                    windowId = windowId,
                    space = space,
                    projectPath = projectPath,
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            logger.error(
                LogCategory.WORKSPACE,
                "MCP open_workspace failed",
                mapOf("error" to (t.message ?: t::class.simpleName.orEmpty())),
            )
            return McpToolResult(
                "Opening the Space failed: ${t.message ?: t::class.simpleName}",
                isError = true,
            )
        }
    }

    /**
     * When the caller does not pick a window: the single-window case is obvious, and with
     * several open prefer one with no project selected, so an agent does not take over a
     * window the user is actively working in. The chosen id comes back in the result, so
     * a wrong guess is visible and correctable.
     */
    private fun pickDefaultWindow(): String? {
        val windows = SplitViewStateRegistry.getAllStates()
        if (windows.isEmpty()) return null
        if (windows.size == 1) return windows.keys.first()
        return windows.keys.firstOrNull {
            WindowProjectStateRegistry
                .get(it)
                ?.selectedProject
                ?.value
                ?.path
                .isNullOrEmpty()
        } ?: windows.keys.first()
    }
}

/** Panel id of the terminal panel the bootstrap Space builds. */
internal const val BOOTSTRAP_PANEL_ID = "panel-open-workspace"

/**
 * Expands a leading `~` to the user's home directory, the way a shell would, so a path an
 * agent copy-pasted from a terminal works unchanged. Anything else passes through as-is.
 */
internal fun expandTilde(
    path: String,
    home: String? = System.getProperty("user.home"),
): String =
    when {
        path == "~" -> home ?: path
        path.startsWith("~/") -> home?.let { it + path.substring(1) } ?: path
        else -> path
    }

/**
 * The canonical absolute form of [path], or null when it is not an existing directory.
 * Every failure (missing, a file, unreadable, security-restricted) means the same thing
 * to the caller: report a clear error.
 */
@Suppress("TooGenericExceptionCaught")
internal fun canonicalizeOrNull(path: String): String? =
    try {
        val dir = File(path)
        if (dir.isDirectory) dir.canonicalPath else null
    } catch (t: Throwable) {
        null
    }

/**
 * The disposable Space open_workspace opens: one panel, one terminal tab, pointed at the
 * project. Name and working directory come from the path, so the Space reads naturally in
 * the Space picker if the user saves it.
 */
internal fun buildBootstrapSpace(canonicalPath: String): LayoutWorkspace {
    val projectName = canonicalPath.trimEnd('/').extractFileName().ifEmpty { "Project" }
    return LayoutWorkspace(
        id = LayoutWorkspace.generateId(),
        name = projectName,
        description = "Bootstrap Space opened by the open_workspace MCP tool.",
        layout =
            SplitConfig.SinglePanel(
                PanelConfig(
                    id = BOOTSTRAP_PANEL_ID,
                    tabs =
                        listOf(
                            TabConfig(
                                type = "terminal",
                                title = "Terminal",
                                workingDirectory = canonicalPath,
                            ),
                        ),
                ),
            ),
        timestamp = Clock.System.now().toEpochMilliseconds(),
        projectPath = canonicalPath,
    )
}

/**
 * The Space to re-enter for [projectPath], if there is one worth reusing rather than
 * building a fresh disposable Space:
 *
 * 1. a Space this tool already created for the path, when it is running in the window -
 *    re-entering restores the live terminal instead of restarting it;
 * 2. a saved Space for the path that is running in the window, the same thing from the
 *    user's own list;
 * 3. any saved Space for the path, which applies it to this window the way picking it in
 *    the Space switcher would;
 * 4. a Space this tool created earlier even though it is no longer running - reusing the
 *    object keeps its id stable instead of minting a second Space for the same directory.
 */
internal fun matchExistingSpace(
    remembered: LayoutWorkspace?,
    savedSpaces: List<LayoutWorkspace>,
    runningIdsInWindow: Set<String>,
    projectPath: String,
): LayoutWorkspace? {
    remembered?.takeIf { it.id in runningIdsInWindow }?.let { return it }
    savedSpaces.firstOrNull { it.id in runningIdsInWindow && it.projectPath == projectPath }?.let { return it }
    savedSpaces.firstOrNull { it.projectPath == projectPath }?.let { return it }
    return remembered
}

/**
 * The tool's JSON reply: status (opened or reused) plus the ids a caller needs to aim
 * panel-scoped tools at what was opened.
 */
internal fun buildOpenResult(
    reused: Boolean,
    windowId: String,
    space: LayoutWorkspace,
    projectPath: String,
): String {
    val panelId =
        space.layout
            .extractPanels()
            .firstOrNull()
            ?.first ?: BOOTSTRAP_PANEL_ID
    return buildJsonObject {
        put("status", if (reused) "reused" else "opened")
        put("window_id", windowId)
        put("workspace_id", space.id)
        put("panel_id", panelId)
        put("project_path", projectPath)
    }.toString()
}
