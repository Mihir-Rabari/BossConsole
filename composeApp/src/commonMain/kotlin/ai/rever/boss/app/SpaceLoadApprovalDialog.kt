package ai.rever.boss.app

import ai.rever.boss.components.dialogs.ConfirmationDialog
import ai.rever.boss.components.workspaces.spaceTerminalCommands
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

/** Each distinct request gets a fresh arming interval, including an identical Space re-request. */
@Composable
internal fun SpaceLoadApprovalDialog(
    request: PendingSpaceLoad,
    pendingCount: Int,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    key(request) {
        var armed by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            delay(500)
            armed = true
        }
        ConfirmationDialog(
            title = "Load this Space? ($pendingCount pending)",
            message =
                "BOSS was asked from outside the app to load a Space whose terminal tabs run commands " +
                    "when it loads. Nothing has loaded and nothing has run. Confirm only if you " +
                    "recognise them:\n\nFile: ${request.workspacePath}\n\nCommands:\n" +
                    request.workspace.spaceTerminalCommands().joinToString("\n") { "- $it" },
            confirmText = "Load Space",
            confirmEnabled = armed,
            onDismiss = onDismiss,
            onConfirm = { if (armed) onConfirm() },
        )
    }
}
