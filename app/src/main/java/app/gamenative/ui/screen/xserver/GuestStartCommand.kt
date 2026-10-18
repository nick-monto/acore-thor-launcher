package app.gamenative.ui.screen.xserver

/**
 * Container flag: hand wine the client itself instead of wrapping it in winhandler.exe.
 *
 * winhandler.exe is the app's guest-side launcher for non-ARM64 clients, and it is an x86-64 image.
 * On an ARM64EC wine that needs FEX, and this build ships no FEX payload - the content index has no
 * fexcore component and there is no assets/fexcore - so the wrapper fails with c0000135 before the
 * client is ever started. Wine's own wow64 layer plus the bundled box64 HODLL run the 32-bit client
 * directly, which is also how the ARM64 clients are started.
 */
const val CONTAINER_EXTRA_SKIP_WINHANDLER = "skipWinHandler"

/**
 * The program argument wine gets for a client: either the executable itself or that executable
 * wrapped in winhandler.exe.
 *
 * ARM64 clients run as they are ([isArm64] comes from the file name, as it does upstream), and so do
 * clients whose container set [CONTAINER_EXTRA_SKIP_WINHANDLER]. The wrapper is applied in exactly one
 * place, so a path that is already a bare executable can never be wrapped twice.
 */
internal fun guestProgramArgument(argument: String, isArm64: Boolean, skipWinHandler: Boolean): String =
    if (isArm64 || skipWinHandler) argument else "winhandler.exe $argument"
