package ai.rever.boss.mcp

import ai.rever.boss.mcp.sandbox.McpRiskLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class McpFlightPlanTest {
    @Test
    fun `allowed read only tool is clear for execution`() {
        val plan = mcpFlightPlan(input(toolName = "git_status", policy = McpPolicyAction.ALLOW))

        assertEquals(McpFlightOutcome.READY_TO_RUN, plan.outcome)
        assertEquals(McpRiskLevel.LOW, plan.risk.level)
        assertTrue(plan.checkpoints.all { it.state == McpFlightCheckpointState.CLEAR })
    }

    @Test
    fun `ask policy pauses the flight plan for a human`() {
        val plan = mcpFlightPlan(input(toolName = "run_command", policy = McpPolicyAction.ASK, readOnly = false))

        assertEquals(McpFlightOutcome.AWAITING_OPERATOR, plan.outcome)
        assertEquals(McpRiskLevel.HIGH, plan.risk.level)
        assertEquals(McpFlightCheckpointState.WAITING, plan.checkpoints[1].state)
        assertEquals(McpFlightCheckpointState.WAITING, plan.checkpoints[2].state)
    }

    @Test
    fun `disabled tool is withheld before an allow policy could run it`() {
        val plan = mcpFlightPlan(input(policy = McpPolicyAction.ALLOW, enabled = false))

        assertEquals(McpFlightOutcome.WITHHELD, plan.outcome)
        assertEquals(McpFlightCheckpointState.BLOCKED, plan.checkpoints.first().state)
        assertEquals(McpFlightCheckpointState.BLOCKED, plan.checkpoints.last().state)
    }

    @Test
    fun `unhealthy policy storage fails closed in the forecast`() {
        val plan = mcpFlightPlan(input(policy = McpPolicyAction.ALLOW, policyFaulted = true))

        assertEquals(McpFlightOutcome.WITHHELD, plan.outcome)
        assertTrue(plan.checkpoints.any { it.label == "Policy" && it.state == McpFlightCheckpointState.BLOCKED })
    }

    @Test
    fun `a side effect declaration is shown as mutating even with an innocent name`() {
        val plan = mcpFlightPlan(input(toolName = "sync_environment", policy = McpPolicyAction.ASK, readOnly = false))

        assertTrue(plan.mutating)
    }

    private fun input(
        toolName: String = "git_status",
        policy: McpPolicyAction = McpPolicyAction.ALLOW,
        readOnly: Boolean = true,
        enabled: Boolean = true,
        policyFaulted: Boolean = false,
    ) = McpFlightPlanInput(
        toolName = toolName,
        providerId = "test-provider",
        declaredReadOnly = readOnly,
        isEnabled = enabled,
        isPermitted = true,
        policy = policy,
        policyFaulted = policyFaulted,
    )
}
