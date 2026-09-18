package ai.rever.boss.app

import ai.rever.boss.plugin.workspace.LayoutWorkspace
import androidx.compose.runtime.mutableStateListOf

/**
 * A parsed Space held back for the operator's confirmation, carried whole so
 * the prompt applies exactly the Space it showed.
 *
 * BOSS reaches this state when a `boss://workspace?path=` request arrives over
 * a path any program can drive rather than from the operator's own `boss`
 * invocation (see `DeepLinkOrigin`), and the Space's terminal tabs carry
 * commands that would be typed into a shell when it loads.
 *
 * @property workspace the deserialized Space, applied verbatim on confirm —
 *   never a re-read of [workspacePath].
 * @property workspacePath the file the Space was loaded from, shown in the prompt.
 */
internal class PendingSpaceLoad(
    val workspace: LayoutWorkspace,
    val workspacePath: String,
)

/** Window-owned FIFO, accessed only on the UI thread. Closing the window drops its requests. */
internal class SpaceLoadApprovalQueue {
    private val requests = mutableStateListOf<PendingSpaceLoad>()

    val current: PendingSpaceLoad?
        get() = requests.firstOrNull()

    val size: Int
        get() = requests.size

    fun enqueue(request: PendingSpaceLoad): Boolean {
        if (requests.size >= MAX_PENDING) return false
        requests.add(request)
        return true
    }

    companion object {
        const val MAX_PENDING = 16
    }

    /** Only the request actually shown can be consumed, once, even for identical Spaces. */
    fun consume(request: PendingSpaceLoad): Boolean {
        if (current !== request) return false
        requests.removeAt(0)
        return true
    }
}
