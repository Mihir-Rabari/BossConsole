package ai.rever.boss.window

import ai.rever.boss.plugin.tab.codeeditor.EditorTabInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A window's lifecycle state must die with the window: [WindowManager] is a
 * process-lifetime object, so anything keyed by windowId that survives
 * [WindowManager.closeWindow] is a leak. The pending initial tab/project are stored
 * before the window initializes and are normally consumed by BossApp on init - a
 * window that closes before (or without) that consumption must have them retired by
 * closeWindow, not stranded forever (#1224).
 */
class WindowManagerPendingStateTest {
    private fun editorTab(title: String) =
        EditorTabInfo(
            id = "editor-test-$title",
            title = title,
            filePath = "/tmp/$title",
        )

    private fun project(name: String) = Project(name = name, path = "/tmp/$name", lastOpened = 0L)

    @Test
    fun `closing a window retires its unconsumed pending tab`() {
        val window = WindowManager.createNewWindowWithTab(editorTab("Stranded.kt"))
        assertTrue(
            WindowManager.windows.any { it.id == window.id },
            "the window exists before closing",
        )

        WindowManager.closeWindow(window.id)

        assertEquals(
            0,
            WindowManager.windows.count { it.id == window.id },
            "the window state is gone",
        )
        assertNull(
            WindowManager.consumePendingTab(window.id),
            "closeWindow must retire a tab BossApp never consumed",
        )
    }

    @Test
    fun `closing a window retires its unconsumed pending project`() {
        val window = WindowManager.createNewWindowWithProject(project("stranded"))

        WindowManager.closeWindow(window.id)

        assertNull(
            WindowManager.consumePendingProject(window.id),
            "closeWindow must retire a project BossApp never consumed",
        )
    }

    @Test
    fun `closing one window leaves other windows pending state alone`() {
        val tabWindow = WindowManager.createNewWindowWithTab(editorTab("Closed.kt"))
        val projectWindow = WindowManager.createNewWindowWithProject(project("kept"))

        WindowManager.closeWindow(tabWindow.id)

        assertNull(
            WindowManager.consumePendingTab(tabWindow.id),
            "the closed window's tab was retired",
        )
        assertNotNull(
            WindowManager.consumePendingProject(projectWindow.id),
            "an unrelated window's pending project survives",
        )
        WindowManager.closeWindow(projectWindow.id)
    }

    @Test
    fun `a tab consumed before close is gone either way`() {
        val window = WindowManager.createNewWindowWithTab(editorTab("Consumed.kt"))

        assertNotNull(
            WindowManager.consumePendingTab(window.id),
            "the normal init path still consumes",
        )
        WindowManager.closeWindow(window.id)

        assertNull(WindowManager.consumePendingTab(window.id), "consumed once, null forever after")
    }

    @Test
    fun `closing an unknown id does not throw and retires nothing`() {
        // No window with this id exists; closeWindow must be a no-op, not a crash.
        WindowManager.closeWindow("no-such-window")

        assertEquals(0, WindowManager.windows.count { it.id == "no-such-window" })
        assertNull(WindowManager.consumePendingTab("no-such-window"))
    }
}
