package ai.rever.boss.components.dialogs

import ai.rever.boss.mcp.McpFlightPlanInput
import ai.rever.boss.mcp.McpToolRegistryImpl
import ai.rever.boss.mcp.mcpFlightPlan
import ai.rever.boss.mcp.mcpPolicyFaultBlocksInvocation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue

/** Supplies [McpFlightPlanDialog] with a live registry snapshot while it is open. */
@Composable
fun McpFlightPlanLauncher(onDismiss: () -> Unit) {
    val allTools by McpToolRegistryImpl.allTools.collectAsState()
    val availableTools by McpToolRegistryImpl.tools.collectAsState()
    val disabledTools by McpToolRegistryImpl.disabledToolNames.collectAsState()
    val policyFault by McpToolRegistryImpl.policyFault.collectAsState()
    val availableIds = availableTools.map { "${it.providerId}/${it.definition.name}" }.toSet()
    val plans =
        allTools
            .map { tool ->
                val id = "${tool.providerId}/${tool.definition.name}"
                val isEnabled = tool.definition.name !in disabledTools
                McpFlightPlanView(
                    providerId = tool.providerId,
                    toolName = tool.definition.name,
                    description = tool.definition.description,
                    inputSchema = tool.definition.inputSchema,
                    plan =
                        mcpFlightPlan(
                            McpFlightPlanInput(
                                toolName = tool.definition.name,
                                providerId = tool.providerId,
                                declaredReadOnly = tool.definition.readOnly,
                                isEnabled = isEnabled,
                                isPermitted = id in availableIds,
                                policy =
                                    McpToolRegistryImpl.policyEngine.policyFor(
                                        tool.definition.name,
                                        tool.providerId,
                                        tool.definition.readOnly,
                                    ),
                                policyFaulted = mcpPolicyFaultBlocksInvocation(policyFault),
                            ),
                        ),
                )
            }.sortedBy { it.toolName }
    McpFlightPlanDialog(plans = plans, onDismiss = onDismiss)
}
