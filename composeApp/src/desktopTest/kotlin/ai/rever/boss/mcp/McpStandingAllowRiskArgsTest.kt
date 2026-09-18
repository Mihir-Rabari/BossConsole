package ai.rever.boss.mcp

import ai.rever.boss.mcp.sandbox.McpRiskLevel
import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolHandler
import ai.rever.boss.plugin.api.McpToolProvider
import ai.rever.boss.plugin.api.McpToolResult
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the argument-aware risk gate on the ALLOW paths: a standing grant (a persisted
 * rule, provider trust, session trust) auto-allows the tool it was answered for, but
 * never the argument-driven CRITICAL escalation the operator did not see - while still
 * auto-allowing genuinely benign invocations, so the grant keeps meaning what the
 * operator granted.
 *
 * The escalation is the DELTA over the tool's argument-free baseline: an intrinsically
 * CRITICAL tool (`helm_uninstall`) keeps its grant - that risk is the tool's own name,
 * visible at grant time - while a shell tool handed a destructive `command` rates
 * CRITICAL where its baseline is HIGH, and that difference is what goes back to the
 * operator.
 */
class McpStandingAllowRiskArgsTest {
    private fun provider(
        id: String,
        vararg defs: McpToolDefinition,
    ) = object : McpToolProvider {
        override val providerId = id

        override fun tools() = defs.toList()
    }

    private fun tool(
        name: String,
        handler: McpToolHandler,
    ) = McpToolDefinition(name = name, description = "test tool $name", readOnly = false, handler = handler)

    // Single line, plain ASCII, far under the 4096-char cap: it passes the shape-only
    // command check a tool like open_terminal applies, so under a standing ALLOW the
    // argument-aware risk evaluation is the only thing standing between the grant and
    // unattended execution.
    private val destructiveArgs = """{"command":"rm -rf /tmp/boss-risk-args-fixture"}"""

    @Test
    fun `a persisted ALLOW re-asks a destructive single-line command instead of running it`() =
        runBlocking {
            val policyEngine = McpPolicyEngine(policyFile = null)
            policyEngine.setToolPolicy("open_terminal", McpPolicyAction.ALLOW)
            val approvalBus = McpApprovalBus(defaultTimeoutMs = 5000L)
            val ledger = McpOperationLedger(ledgerFile = null)
            var calls = 0
            val core =
                McpToolRegistryCore(
                    disabledFile = null,
                    policyEngine = policyEngine,
                    approvalBus = approvalBus,
                    ledger = ledger,
                )
            core.registerProvider(
                provider(
                    "workspace",
                    tool("open_terminal") {
                        calls++
                        McpToolResult("opened")
                    },
                ),
            )

            val call = async { core.invoke("open_terminal", destructiveArgs) }
            val request = withTimeout(5_000) { approvalBus.pendingList.first { it.isNotEmpty() } }.first()
            assertEquals(McpRiskLevel.CRITICAL, request.riskAssessment?.level)
            approvalBus.deny(request.id)

            val result = call.await()
            assertTrue(result.isError)
            assertEquals(0, calls)
            val record = ledger.recentOperations.value.first()
            // The persisted rule still reads ALLOW - the re-ask came from this call's
            // arguments, which the grant never covered.
            assertEquals(McpPolicyAction.ALLOW, record.policyApplied)
            assertEquals(McpApprovalDisposition.DENIED_BY_OPERATOR, record.approvalDisposition)
        }

    @Test
    fun `session trust asks again for a destructive command`() =
        runBlocking {
            val policyEngine = McpPolicyEngine(policyFile = null)
            policyEngine.trustForSession("run_command", "terminal-tab")
            var calls = 0
            val approvalBus = McpApprovalBus(defaultTimeoutMs = 5000L)
            val ledger = McpOperationLedger(ledgerFile = null)
            val core =
                McpToolRegistryCore(
                    disabledFile = null,
                    policyEngine = policyEngine,
                    approvalBus = approvalBus,
                    ledger = ledger,
                )
            core.registerProvider(
                provider(
                    "terminal-tab",
                    tool("run_command") {
                        calls++
                        McpToolResult("ran")
                    },
                ),
            )

            val call = async { core.invoke("run_command", destructiveArgs) }
            val request = withTimeout(5_000) { approvalBus.pendingList.first { it.isNotEmpty() } }.first()
            approvalBus.deny(request.id)

            assertTrue(call.await().isError)
            assertEquals(0, calls)
        }

    @Test
    fun `provider trust asks again for a destructive command`() =
        runBlocking {
            val policyEngine = McpPolicyEngine(policyFile = null)
            policyEngine.setProviderPolicy("terminal-tab", McpPolicyAction.ALLOW)
            var calls = 0
            val approvalBus = McpApprovalBus(defaultTimeoutMs = 5000L)
            val ledger = McpOperationLedger(ledgerFile = null)
            val core =
                McpToolRegistryCore(
                    disabledFile = null,
                    policyEngine = policyEngine,
                    approvalBus = approvalBus,
                    ledger = ledger,
                )
            core.registerProvider(
                provider(
                    "terminal-tab",
                    tool("run_command") {
                        calls++
                        McpToolResult("ran")
                    },
                ),
            )

            val call = async { core.invoke("run_command", destructiveArgs) }
            val request = withTimeout(5_000) { approvalBus.pendingList.first { it.isNotEmpty() } }.first()
            approvalBus.deny(request.id)

            assertTrue(call.await().isError)
            assertEquals(0, calls)
        }

    @Test
    fun `a genuinely read-only tool stays auto-allowed with no prompt`() =
        runBlocking {
            val approvalBus = McpApprovalBus(defaultTimeoutMs = 5000L)
            val ledger = McpOperationLedger(ledgerFile = null)
            var calls = 0
            val core =
                McpToolRegistryCore(
                    disabledFile = null,
                    approvalBus = approvalBus,
                    ledger = ledger,
                )
            core.registerProvider(
                provider(
                    "workspace",
                    McpToolDefinition(
                        name = "codebase_read",
                        description = "test tool codebase_read",
                        readOnly = true,
                        handler =
                            McpToolHandler {
                                calls++
                                McpToolResult("read")
                            },
                    ),
                ),
            )

            val result = core.invoke("codebase_read", """{"path":"/src/Main.kt"}""")
            assertFalse(result.isError)
            assertEquals(1, calls)
            assertTrue(approvalBus.pendingList.value.isEmpty())
            assertEquals(
                McpApprovalDisposition.AUTO_ALLOWED,
                ledger.recentOperations.value
                    .first()
                    .approvalDisposition,
            )
        }

    @Test
    fun `a persisted ALLOW still covers benign commands and intrinsically critical tools`() =
        runBlocking {
            val policyEngine = McpPolicyEngine(policyFile = null)
            policyEngine.setToolPolicy("run_command", McpPolicyAction.ALLOW)
            policyEngine.setToolPolicy("helm_uninstall", McpPolicyAction.ALLOW)
            val approvalBus = McpApprovalBus(defaultTimeoutMs = 5000L)
            val ledger = McpOperationLedger(ledgerFile = null)
            var shellCalls = 0
            var helmCalls = 0
            val core =
                McpToolRegistryCore(
                    disabledFile = null,
                    policyEngine = policyEngine,
                    approvalBus = approvalBus,
                    ledger = ledger,
                )
            core.registerProvider(
                provider(
                    "terminal-tab",
                    tool("run_command") {
                        shellCalls++
                        McpToolResult("ran")
                    },
                    tool("helm_uninstall") {
                        helmCalls++
                        McpToolResult("uninstalled")
                    },
                ),
            )

            // A benign command on the granted shell tool: still no prompt, the grant
            // covers it exactly as it did before the gate existed.
            assertFalse(core.invoke("run_command", """{"command":"ls -la"}""").isError)
            // An intrinsically CRITICAL tool named in its own grant: still no prompt -
            // the risk lived in the tool's name, which the operator saw when granting it.
            assertFalse(core.invoke("helm_uninstall", "{}").isError)

            assertEquals(1, shellCalls)
            assertEquals(1, helmCalls)
            assertTrue(approvalBus.pendingList.value.isEmpty())
            assertEquals(
                listOf(McpApprovalDisposition.AUTO_ALLOWED, McpApprovalDisposition.AUTO_ALLOWED),
                ledger.recentOperations.value.map { it.approvalDisposition },
            )
        }

    @Test
    fun `approving the re-ask runs that one call and asks again on the next`() =
        runBlocking {
            val policyEngine = McpPolicyEngine(policyFile = null)
            policyEngine.setToolPolicy("run_command", McpPolicyAction.ALLOW)
            val approvalBus = McpApprovalBus(defaultTimeoutMs = 5000L)
            val ledger = McpOperationLedger(ledgerFile = null)
            var calls = 0
            val core =
                McpToolRegistryCore(
                    disabledFile = null,
                    policyEngine = policyEngine,
                    approvalBus = approvalBus,
                    ledger = ledger,
                )
            core.registerProvider(
                provider(
                    "terminal-tab",
                    tool("run_command") {
                        calls++
                        McpToolResult("ran")
                    },
                ),
            )

            val first = async { core.invoke("run_command", destructiveArgs) }
            val firstRequest = withTimeout(5_000) { approvalBus.pendingList.first { it.isNotEmpty() } }.first()
            approvalBus.approve(firstRequest.id)
            assertFalse(first.await().isError)
            assertEquals(1, calls)

            // The approval covered THAT call only: the next destructive command asks
            // again rather than riding a grant that was never allowed to cover it.
            val second = async { core.invoke("run_command", destructiveArgs) }
            val secondRequest = withTimeout(5_000) { approvalBus.pendingList.first { it.isNotEmpty() } }.first()
            approvalBus.deny(secondRequest.id)
            assertTrue(second.await().isError)
            assertEquals(1, calls)
        }
}
