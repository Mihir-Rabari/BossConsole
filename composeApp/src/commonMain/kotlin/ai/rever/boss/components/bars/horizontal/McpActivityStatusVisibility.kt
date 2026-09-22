package ai.rever.boss.components.bars.horizontal

internal fun mcpActivityStatusShouldRender(
    hasRecentOperations: Boolean,
    hasRegisteredTools: Boolean,
    showActivityLog: Boolean,
    showFlightPlan: Boolean,
): Boolean = hasRecentOperations || hasRegisteredTools || showActivityLog || showFlightPlan
