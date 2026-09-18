package ai.rever.boss.components.events

import ai.rever.boss.ipc.IpcEventBridge
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Event emitted when a workspace should be loaded.
 *
 * @property workspacePath Path to the workspace file
 * @property sourceWindowId The window that should load the workspace (required for multi-window support)
 * @property requiresConfirmation True when the load reached BOSS from somewhere
 *   other than the operator's own invocation (see `DeepLinkOrigin`) and the
 *   window must show the operator the Space's terminal commands before
 *   anything loads. Ignored for a Space with no terminal commands — that Space
 *   types nothing into a shell. Defaults to false so the in-app loads, which
 *   are the operator clicking something, stay direct.
 */
data class WorkspaceLoadEvent(
    val workspacePath: String,
    val sourceWindowId: String,
    val requiresConfirmation: Boolean = false,
)

/**
 * Event bus for workspace-related events.
 *
 * Issue #506: Added sourceWindowId for multi-window support.
 */
object WorkspaceEventBus {
    /** Optional IPC bridge for forwarding events cross-process in kernel mode. */
    @Volatile var ipcBridge: IpcEventBridge? = null

    private val _workspaceLoadEvents =
        MutableSharedFlow<WorkspaceLoadEvent>(
            replay = 0, // Don't replay past events to new subscribers (new windows)
            extraBufferCapacity = 10, // Buffer up to 10 events if collector not ready yet
        )
    val workspaceLoadEvents: SharedFlow<WorkspaceLoadEvent> = _workspaceLoadEvents.asSharedFlow()

    /**
     * Emit a workspace load event.
     *
     * @param workspacePath Path to the workspace file
     * @param sourceWindowId The window that should load the workspace (required for multi-window support)
     * @param requiresConfirmation See [WorkspaceLoadEvent.requiresConfirmation]
     */
    suspend fun loadWorkspace(
        workspacePath: String,
        sourceWindowId: String,
        requiresConfirmation: Boolean = false,
    ) {
        val event = WorkspaceLoadEvent(workspacePath, sourceWindowId, requiresConfirmation)
        _workspaceLoadEvents.emit(event)
        ipcBridge?.forward("WorkspaceLoadEvent", event, sourceWindowId)
    }
}
