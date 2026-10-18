package app.gamenative.ui.screen.xserver

import org.junit.Assert.assertEquals
import org.junit.Test

class GuestStartCommandTest {
    @Test
    fun `arm64 client is handed to wine as it is`() {
        assertEquals("G:\\Wow Forever\\Wow.exe", guestProgramArgument("G:\\Wow Forever\\Wow.exe", isArm64 = true, skipWinHandler = false))
    }

    @Test
    fun `non-arm64 client keeps the winhandler wrapper`() {
        assertEquals("winhandler.exe G:\\Wow.exe", guestProgramArgument("G:\\Wow.exe", isArm64 = false, skipWinHandler = false))
    }

    @Test
    fun `container that skips winhandler gets the client itself`() {
        assertEquals("G:\\Wow.exe", guestProgramArgument("G:\\Wow.exe", isArm64 = false, skipWinHandler = true))
    }

    @Test
    fun `quotes around a path with spaces survive when the wrapper is skipped`() {
        assertEquals("\"G:\\Program Files\\Wow.exe\"", guestProgramArgument("\"G:\\Program Files\\Wow.exe\"", isArm64 = false, skipWinHandler = true))
    }

    @Test
    fun `quotes around a path with spaces are kept inside the wrapper`() {
        assertEquals("winhandler.exe \"G:\\Program Files\\Wow.exe\"", guestProgramArgument("\"G:\\Program Files\\Wow.exe\"", isArm64 = false, skipWinHandler = false))
    }
}
