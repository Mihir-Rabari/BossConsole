package ai.rever.boss.components.bars.horizontal

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class McpActivityStatusItemTest {
    @Test
    fun `registered tools keep the status item visible when every tool is blocked`() {
        assertTrue(mcpActivityStatusShouldRender(false, true, false, false))
    }

    @Test
    fun `empty registry and activity keep the status item hidden`() {
        assertFalse(mcpActivityStatusShouldRender(false, false, false, false))
    }
}
