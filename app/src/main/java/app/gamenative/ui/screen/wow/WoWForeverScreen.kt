package app.gamenative.ui.screen.wow

import app.gamenative.PrefManager
import app.gamenative.externaldisplay.PadSettings
import android.content.Context
import android.os.Build
import android.os.Environment
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import app.gamenative.ui.theme.WowCream
import app.gamenative.ui.theme.WowCrimsonBottom
import app.gamenative.ui.theme.WowCrimsonTop
import app.gamenative.ui.theme.WowFrame
import app.gamenative.ui.theme.WowFrameInner
import app.gamenative.ui.theme.WowStoneBottom
import app.gamenative.ui.theme.WowStoneTop
import app.gamenative.ui.theme.WowTitle
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.gamenative.BuildConfig
import app.gamenative.PluviaApp
import app.gamenative.R
import app.gamenative.events.AndroidEvent
import app.gamenative.ui.screen.wow.WowClientDownloader.BUILD_INFO
import app.gamenative.ui.screen.wow.WowClientDownloader.FLAVOR_DIR
import app.gamenative.ui.screen.wow.WowClientDownloader.TARGET_PRODUCT
import app.gamenative.ui.screen.xserver.CONTAINER_EXTRA_SKIP_WINHANDLER
import app.gamenative.ui.theme.WowBackgroundGradient
import app.gamenative.ui.theme.WowBronze
import app.gamenative.ui.theme.WowError
import app.gamenative.ui.theme.WowGold
import app.gamenative.ui.theme.WowMuted
import app.gamenative.ui.theme.WowSubtle
import app.gamenative.utils.AppUpdater
import app.gamenative.utils.StorageUtils
import app.gamenative.utils.renderReleaseNotes
import com.winlator.container.Container
import com.winlator.container.ContainerManager
import com.winlator.contents.ContentProfile
import com.winlator.contents.ContentsManager
import com.winlator.core.FileUtils
import com.winlator.core.GPUInformation
import com.winlator.core.TarCompressorUtils
import com.winlator.inputcontrols.Binding
import com.winlator.inputcontrols.ControlsProfile
import com.winlator.inputcontrols.ExternalControllerBinding
import com.winlator.inputcontrols.InputControlsManager
import com.winlator.xenvironment.ImageFs
import com.winlator.xenvironment.ImageFsInstaller
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber
import java.io.File

object WoWLauncherState {
    var shouldAutoLaunch = false
}

private const val READY_STATUS = "Ready to launch"

/** Where a local realm's client looks for its logon server: loopback, because the server is this device. */
private const val LOCAL_REALM_ADDRESS = "127.0.0.1"

/** The locale folder the local realm's realmlist.wtf is written into. */
private const val LOCAL_CLIENT_LOCALE = "enUS"

/** The container key that selects a pad profile; the X server screen reads it as a string. */
private const val CONTAINER_EXTRA_PROFILE_ID = "profileId"

/** The controller add-on the launcher installs for the local realm, and the version it installs. */
private const val LOCAL_ADDON_ARCHIVE = "ConsolePortLK-1.5.0-rc2-thor1.zip"
private const val LOCAL_ADDON_VERSION = "1.5.0-rc2-thor1"

/** This project's own companion add-on, which hands ConsolePort the pad's keys. */
private const val LOCAL_PAD_ADDON = "thorpad"
private const val LOCAL_PAD_ADDON_FOLDER = "ThorPad"
private const val LOCAL_PAD_ADDON_VERSION = "2"
// EInteract: the client half of the realm's mod-e-interact, one key for the whole interact loop
// (loot, gather, talk, chests and nodes). It ships as assets beside ThorPad.
private const val LOCAL_INTERACT_ADDON = "einteract"
private const val LOCAL_INTERACT_ADDON_FOLDER = "EInteract"
private const val LOCAL_INTERACT_ADDON_VERSION = "1"

/** Marks the client root as carrying [LOCAL_ADDON_VERSION], so a launch does not re-extract it. */
private const val LOCAL_ADDON_MARKER = ".consoleport-version"

/** The name of the pad profile this flavour installs. The player owns it once it exists. */
private const val LOCAL_CONTROLLER_PROFILE = "Thor Controller (3.3.5a)"

@Composable
fun WoWForeverScreen(
    onLaunch: (appId: String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val gpu = remember { GpuProfile.detect(context) }

    // null means "auto": the driver matched to the detected GPU. A custom id is persisted, but
    // resolve() falls back to auto if the imported driver was removed or needs a newer API.
    var driverSelection by remember { mutableStateOf(GraphicsDriverStore.selected(context)) }
    var customDrivers by remember { mutableStateOf(GraphicsDriverStore.customDrivers(context)) }
    var showDriverDialog by remember { mutableStateOf(false) }
    var isImportingDriver by remember { mutableStateOf(false) }
    var driverMessage by remember { mutableStateOf<String?>(null) }
    var driverError by remember { mutableStateOf<String?>(null) }

    var flavor by remember { mutableStateOf(WowFlavor.load(context)) }
    var buttonPad by remember { mutableStateOf(ButtonPad.isEnabled(context, flavor)) }
    var r3Toggle by remember { mutableStateOf(ButtonPad.r3Toggle(context, flavor)) }
    var region by remember { mutableStateOf(WowFlavor.region) }
    var forceGamepadUi by remember { mutableStateOf(ButtonPad.forceGamepadUi(context, flavor)) }
    var gamePath by remember { mutableStateOf(GamePath.load(context)) }
    var files by remember { mutableStateOf(GamePath.Status()) }
    var isLaunching by remember { mutableStateOf(false) }
    var launchJob by remember { mutableStateOf<Job?>(null) }
    var hasSavedLogin by remember { mutableStateOf(BattleNetSignIn.load(context) != null) }
    var autoLogin by remember { mutableStateOf(BattleNetSignIn.autoLoginEnabled(context)) }
    // Ready to play: a local realm has no Battle.net login at all; otherwise a login is saved,
    // or auto-login is off and you sign in by hand.
    val loginReady = flavor.isLocal || hasSavedLogin || !autoLogin
    var showLoginDialog by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf(READY_STATUS) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showStoragePermissionDialog by remember { mutableStateOf(false) }
    var versionStatus by remember { mutableStateOf<WowClientDownloader.VersionCheckResult?>(null) }
    var isCheckingVersion by remember { mutableStateOf(false) }
    var isUpdating by remember { mutableStateOf(false) }
    var updateStatusText by remember { mutableStateOf("") }
    var appUpdate by remember { mutableStateOf<AppUpdater.Release?>(null) }
    var betaUpdates by remember { mutableStateOf(AppUpdater.betaEnabled(context)) }
    var skipAutoUpdates by remember { mutableStateOf(AppUpdater.skipAutoUpdates(context)) }
    var isDownloadingAppUpdate by remember { mutableStateOf(false) }
    var appUpdateProgress by remember { mutableFloatStateOf(0f) }
    var showAppUpdateDialog by remember { mutableStateOf(false) }
    var showAppUpdatePermissionDialog by remember { mutableStateOf(false) }
    var pendingInstallPermission by remember { mutableStateOf(false) }
    var downloadedApk by remember { mutableStateOf<File?>(null) }

    // The realm lives in Termux, so its state is not ours to remember: probe the ports while this
    // screen is up, and let a start or stop settle on what the ports say rather than on the intent.
    var termuxInstalled by remember { mutableStateOf(true) }
    var serverStatus by remember { mutableStateOf(LocalServer.Status()) }

    // Nothing here starts anything, so there is no command to wait on: the ports are the whole truth.
    if (flavor.isLocal) {
        LaunchedEffect(flavor) {
            termuxInstalled = LocalServer.termuxInstalled(context)
            while (true) {
                serverStatus = withContext(Dispatchers.IO) { LocalServer.probe() }
                delay(3000)
            }
        }
    }

    suspend fun fetchAppUpdate(manual: Boolean = false): AppUpdater.Release? {
        // With "skip automatic app updates" on, only a manual refresh looks for a new app version.
        if (skipAutoUpdates && !manual) return null
        val release = AppUpdater.check(includeBeta = betaUpdates)
        appUpdate = release
        if (release != null) {
            showAppUpdateDialog = true
        }
        return release
    }

    fun checkAppUpdate(manual: Boolean = false) {
        scope.launch { fetchAppUpdate(manual) }
    }

    fun installOrRequestPermission(apk: File) {
        if (AppUpdater.canInstall(context)) {
            AppUpdater.install(context, apk)
        } else {
            pendingInstallPermission = true
            showAppUpdatePermissionDialog = true
        }
    }

    fun performAppUpdate() {
        val target = appUpdate ?: return
        if (isDownloadingAppUpdate) return
        downloadedApk?.takeIf { it.exists() && it.length() == target.sizeBytes }?.let { apk ->
            installOrRequestPermission(apk)
            return
        }
        isDownloadingAppUpdate = true
        appUpdateProgress = 0f
        errorMessage = null
        scope.launch {
            try {
                val apk = AppUpdater.download(context, target) { progress ->
                    appUpdateProgress = progress
                }
                downloadedApk = apk
                isDownloadingAppUpdate = false
                installOrRequestPermission(apk)
            } catch (e: Exception) {
                Timber.e(e, "Error downloading app update")
                errorMessage = "App update download failed: ${e.message}"
                isDownloadingAppUpdate = false
            }
        }
    }

    suspend fun fetchVersionStatus() {
        isCheckingVersion = true
        versionStatus = withContext(Dispatchers.IO) { WowClientDownloader.checkVersion(File(gamePath)) }
        isCheckingVersion = false
    }

    fun checkVersionStatus() {
        // Nothing to compare a local realm against: there is no Blizzard patch server in the path.
        if (flavor.isLocal || !File(gamePath, BUILD_INFO).exists()) return
        scope.launch { fetchVersionStatus() }
    }

    fun checkFiles(): Boolean {
        files = GamePath.status(context, gamePath)
        if (files.isWrongGame) {
            errorMessage = "Incompatible game (${files.product}). ${flavor.label} needs a $BUILD_INFO entry for $TARGET_PRODUCT."
        }
        return files.isReady
    }

    fun selectFlavor(selected: WowFlavor) {
        if (selected == flavor || isLaunching || isUpdating) return
        WowFlavor.select(context, selected)
        flavor = selected
        buttonPad = ButtonPad.isEnabled(context, selected)
        forceGamepadUi = ButtonPad.forceGamepadUi(context, selected)
        r3Toggle = ButtonPad.r3Toggle(context, selected)
        errorMessage = null
        versionStatus = null
        gamePath = GamePath.load(context)
        if (checkFiles()) checkVersionStatus()
    }

    fun denyStorageAccess() {
        files = files.copy(hasStorageAccess = false)
        showStoragePermissionDialog = true
    }

    fun resetLaunch() {
        launchJob?.cancel()
        launchJob = null
        isLaunching = false
        statusText = READY_STATUS
    }

    fun launchGame() {
        if (isLaunching || files.isWrongGame) return
        if (!loginReady) {
            showLoginDialog = true
            return
        }
        if (!files.hasStorageAccess) {
            showStoragePermissionDialog = true
            return
        }
        // A bounce back to this screen tells you nothing: the guest's own output (Wine and box64) is
        // buffered and thrown away unless capture is on. The 32-bit path is new, so keep it on for the
        // local flavor and leave it off elsewhere. Wine then logs warn,err,fixme,loaddll and the lines
        // land in wine_logs/wine_debug.log inside the app's external files dir, overwritten each launch.
        PrefManager.enableWineDebug = flavor.isLocal
        isLaunching = true
        errorMessage = null
        statusText = "Preparing components..."

        launchJob = scope.launch {
            try {
                val containerId = withContext(Dispatchers.IO) {
                    val launchGpu = gpu.withDriver(GraphicsDriverStore.resolve(context, gpu.driver))
                    prepareLaunch(context, File(gamePath), launchGpu) { msg ->
                        scope.launch(Dispatchers.Main) { statusText = msg }
                    }
                }
                statusText = "Booting into ${flavor.gameName}..."
                onLaunch(containerId)
                // login.txt holds the password in plain text: remove it after the game starts (5 min on a build's first launch, 1 min after).
                if (BattleNetSignIn.autoLoginEnabled(context)) BattleNetSignIn.scheduleLoginFileRemoval(context, File(gamePath))
            } catch (e: CancellationException) {
                throw e
            } catch (_: StorageAccessDeniedException) {
                isLaunching = false
                statusText = READY_STATUS
                denyStorageAccess()
            } catch (e: Exception) {
                Timber.e(e, "Error launching the WoW client")
                errorMessage = e.message ?: "Launch failed"
                statusText = "Launch failed"
                isLaunching = false
            }
        }
    }

    LifecycleResumeEffect(gamePath) {
        checkFiles()
        isLaunching = false
        statusText = READY_STATUS
        // The pad settings carry the same per-game switches, and they can be flipped during play: read
        // them again so the main screen does not show a value that is no longer true.
        buttonPad = ButtonPad.isEnabled(context, flavor)
        forceGamepadUi = ButtonPad.forceGamepadUi(context, flavor)
        r3Toggle = ButtonPad.r3Toggle(context, flavor)
        if (versionStatus == null && !WoWLauncherState.shouldAutoLaunch) {
            checkVersionStatus()
        }
        if (appUpdate == null && !WoWLauncherState.shouldAutoLaunch) {
            checkAppUpdate()
        }
        if (pendingInstallPermission && AppUpdater.canInstall(context)) {
            pendingInstallPermission = false
            downloadedApk?.takeIf { it.exists() }?.let { AppUpdater.install(context, it) }
        }
        onPauseOrDispose {
            launchJob?.cancel()
            launchJob = null
        }
    }

    DisposableEffect(Unit) {
        val reset: (Any) -> Unit = { resetLaunch() }
        PluviaApp.events.on<AndroidEvent.ForceCloseApp, Unit>(reset)
        PluviaApp.events.on<AndroidEvent.GuestProgramTerminated, Unit>(reset)
        onDispose {
            PluviaApp.events.off<AndroidEvent.ForceCloseApp, Unit>(reset)
            PluviaApp.events.off<AndroidEvent.GuestProgramTerminated, Unit>(reset)
        }
    }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val path = StorageUtils.getPathFromTreeUri(context, uri) ?: return@rememberLauncherForActivityResult
        val root = GamePath.findGameRoot(File(path))
        if (root == null) {
            errorMessage = if (flavor.isLocal) {
                "No ${flavor.exeName} and Data/ in $path. Pick the folder that holds your 3.3.5a client."
            } else {
                "No $BUILD_INFO found in $path. Pick the folder that contains $BUILD_INFO and Data/."
            }
            return@rememberLauncherForActivityResult
        }
        errorMessage = null
        gamePath = root.absolutePath
        GamePath.save(context, gamePath)
        versionStatus = null
        val ok = checkFiles()
        if (!files.hasStorageAccess) {
            showStoragePermissionDialog = true
        } else if (ok) {
            checkVersionStatus()
        }
    }

    val driverPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        isImportingDriver = true
        driverMessage = null
        driverError = null
        scope.launch {
            try {
                val driver = withContext(Dispatchers.IO) { GraphicsDriverStore.import(context, uri) }
                customDrivers = GraphicsDriverStore.customDrivers(context)
                GraphicsDriverStore.select(context, driver.id)
                driverSelection = driver.id
                driverMessage = "Installed ${driver.name}."
            } catch (e: Exception) {
                Timber.e(e, "Error importing custom graphics driver")
                driverError = e.message ?: "Could not import the driver."
            } finally {
                isImportingDriver = false
            }
        }
    }

    // A local client carries no .build.info: its binary plus Data/ are the whole readiness test.
    val filesMissing = if (flavor.isLocal) {
        !(files.exeExists && files.dataExists)
    } else {
        !(files.dataExists && files.buildInfoExists)
    }
    val canUpdate = !isLaunching && !isUpdating && !filesMissing && !files.isWrongGame
    // The game won't start until auto-login is set up or switched off.
    val canPlay = canUpdate && loginReady && files.hasStorageAccess
    val busy = isLaunching || isUpdating
    val driverLabel = driverSelection
        ?.let { id -> customDrivers.firstOrNull { it.id == id }?.name }
        ?: "Auto"

    fun performUpdate() {
        val target = versionStatus ?: return
        if (isUpdating || isLaunching) return
        if (!files.hasStorageAccess) {
            showStoragePermissionDialog = true
            return
        }
        isUpdating = true
        updateStatusText = "Connecting to Blizzard CDN..."
        errorMessage = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    WowClientDownloader.updateGame(File(gamePath), target) { msg ->
                        scope.launch(Dispatchers.Main) { updateStatusText = msg }
                    }
                }
                checkFiles()
                versionStatus = versionStatus?.copy(isOutdated = false, localVersion = target.remoteVersion)
                isUpdating = false
                // Stay on the launcher after an update: the Play button is ready when you are.
                updateStatusText = ""
            } catch (e: Exception) {
                Timber.e(e, "Error updating game")
                if (e.isPermissionDenied()) denyStorageAccess()
                errorMessage = "Update failed: ${e.message}"
                isUpdating = false
            }
        }
    }

    LaunchedEffect(gamePath) {
        // A previous run may have been killed before the login file was removed. A local realm has none.
        if (!flavor.isLocal) {
            withContext(Dispatchers.IO) { BattleNetSignIn.removeLoginFile(File(gamePath)) }
        }
        val ready = checkFiles()
        if (!files.hasStorageAccess && (files.dataExists || files.buildInfoExists)) {
            showStoragePermissionDialog = true
        }
        Timber.i("WoWForeverScreen LaunchedEffect: gamePath=$gamePath, ready=$ready, hasSavedLogin=$hasSavedLogin, shouldAutoLaunch=${WoWLauncherState.shouldAutoLaunch}")
        when {
            !ready || !loginReady -> {
                WoWLauncherState.shouldAutoLaunch = false
                PluviaApp.events.emit(AndroidEvent.ClearBootingSplash)
                if (ready) {
                    checkVersionStatus()
                    checkAppUpdate()
                }
            }
            !WoWLauncherState.shouldAutoLaunch -> {
                checkVersionStatus()
                checkAppUpdate()
            }
            else -> {
                val updateJob = async { fetchAppUpdate() }
                fetchVersionStatus()
                val update = updateJob.await()
                Timber.i("WoWForeverScreen LaunchedEffect: isOutdated=${versionStatus?.isOutdated}, appUpdate=$update")
                WoWLauncherState.shouldAutoLaunch = false
                if (versionStatus?.isOutdated == true || update != null) {
                    PluviaApp.events.emit(AndroidEvent.ClearBootingSplash)
                } else {
                    launchGame()
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(WowBackgroundGradient))
            .padding(24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(modifier = Modifier.height(20.dp))
                Text(
                    text = if (flavor.isLocal) "AZEROTHCORE" else "WORLD OF WARCRAFT",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Serif,
                    letterSpacing = 5.sp,
                    color = WowTitle,
                )
                Text(
                    text = flavor.subtitle,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Serif,
                    letterSpacing = 3.sp,
                    color = WowFrame,
                )
                Spacer(modifier = Modifier.height(8.dp))
                WowDivider()
                Spacer(modifier = Modifier.height(6.dp))
                Text(text = "${gpu.edition} Edition", fontSize = 11.sp, color = WowSubtle)
                DriverChoiceLink(
                    label = driverLabel,
                    enabled = !busy,
                    onClick = {
                        driverMessage = null
                        driverError = null
                        showDriverDialog = true
                    },
                )
                Spacer(modifier = Modifier.height(12.dp))
                FlavorSelector(selected = flavor, enabled = !isLaunching && !isUpdating, onSelect = { selectFlavor(it) })
                LaunchSwitches(
                    enabled = !isLaunching,
                    buttonPad = buttonPad,
                    onButtonPad = {
                        buttonPad = it
                        ButtonPad.setEnabled(context, flavor, it)
                    },
                    forceGamepadUi = forceGamepadUi,
                    onForceGamepadUi = {
                        forceGamepadUi = it
                        ButtonPad.setForceGamepadUi(context, flavor, it)
                    },
                    r3Toggle = r3Toggle,
                    onR3Toggle = {
                        r3Toggle = it
                        ButtonPad.setR3Toggle(context, flavor, it)
                    },
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            WowPanel {
                Column(modifier = Modifier.padding(20.dp)) {
                    SectionTitle("ENVIRONMENT READINESS", WowGold)
                    Spacer(modifier = Modifier.height(14.dp))

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        CheckItem(
                            label = when {
                                flavor.isLocal && files.exeExists -> "Client Binary (${files.exeName})"
                                flavor.isLocal -> "Client Binary (copy your 3.3.5a client here)"
                                files.exeExists -> "ARM64 Binary (${files.exeName})"
                                else -> "ARM64 Binary (downloads on Play)"
                            },
                            ready = files.exeExists,
                            warning = !files.exeExists,
                        )
                        CheckItem(label = "Game Asset Archives (Data/)", ready = files.dataExists)
                        if (flavor.isLocal) {
                            CheckItem(
                                label = "Local Realm ($LOCAL_REALM_ADDRESS:${LocalServer.AUTH_PORT}) - ${serverStatus.label}",
                                ready = serverStatus.running,
                                warning = !serverStatus.running,
                            )
                        } else {
                            CheckItem(
                                label = if (files.isWrongGame) {
                                    "Install Info ($BUILD_INFO - requires $TARGET_PRODUCT)"
                                } else {
                                    "Install Info ($BUILD_INFO)"
                                },
                                ready = files.buildInfoExists && !files.isWrongGame,
                            )
                        }
                        CheckItem(label = "All Files Access Permission", ready = files.hasStorageAccess)
                        if (!flavor.isLocal) {
                            CheckItem(
                                label = when {
                                    !autoLogin -> "Battle.net Login (Auto-login off, sign in by hand)"
                                    hasSavedLogin -> "Battle.net Login (Configured)"
                                    else -> "Battle.net Login (Configure below, or turn off auto-login)"
                                },
                                ready = loginReady,
                            )
                        }
                        appUpdate?.let {
                            CheckItem(
                                label = "App Build: v${BuildConfig.VERSION_NAME} (v${it.version} available)",
                                ready = false,
                                warning = true,
                            )
                        } ?: CheckItem(
                            label = "App Build: v${BuildConfig.VERSION_NAME}",
                            ready = true,
                        )
                        versionStatus?.let { version ->
                            CheckItem(
                                label = if (version.isOutdated) {
                                    "Client Build: ${version.localVersion} (Update ${version.remoteVersion} available)"
                                } else {
                                    "Client Build: ${version.localVersion} (Up to date)"
                                },
                                ready = !version.isOutdated,
                                warning = true,
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider(color = WowFrameInner)
                    Spacer(modifier = Modifier.height(12.dp))

                    Text(text = "Target Path: $gamePath", fontSize = 11.sp, color = WowSubtle, maxLines = 2)

                    errorMessage?.let {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(text = "Error: $it", fontSize = 12.sp, color = WowError, fontWeight = FontWeight.Medium)
                    }
                }
            }

            if (flavor.isLocal) {
                Spacer(modifier = Modifier.height(20.dp))
                WowPanel {
                    Column(modifier = Modifier.padding(20.dp)) {
                        SectionTitle("LOCAL SERVER", WowGold)
                        Spacer(modifier = Modifier.height(14.dp))

                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            CheckItem(
                                label = "Logon Server ($LOCAL_REALM_ADDRESS:${LocalServer.AUTH_PORT})",
                                ready = serverStatus.auth,
                                warning = !serverStatus.auth,
                            )
                            CheckItem(
                                label = "World Server ($LOCAL_REALM_ADDRESS:${LocalServer.WORLD_PORT})",
                                ready = serverStatus.world,
                                warning = serverStatus.auth && !serverStatus.world,
                            )
                            CheckItem(
                                label = if (termuxInstalled) "Termux (this is where the realm runs)" else "Termux is not installed",
                                ready = termuxInstalled,
                                warning = !termuxInstalled,
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                        HorizontalDivider(color = WowFrameInner)
                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            text = when {
                                !termuxInstalled ->
                                    "The realm runs in Termux, which is not installed on this device. Install Termux (and Termux:API, for the wake lock), run the installer inside it, then start the realm there with ~/start-wow-server.sh."
                                serverStatus.world ->
                                    "Both servers are up, and the client is pointed at $LOCAL_REALM_ADDRESS:${LocalServer.AUTH_PORT}. The realm is started and stopped in Termux: ~/start-wow-server.sh, ~/stop-wow-server.sh."
                                serverStatus.auth ->
                                    "The logon server is up and the world is still starting. Right after a client update that import can take a while."
                                else ->
                                    "The realm is stopped. Start it in Termux with ~/start-wow-server.sh - or let a script in ~/.termux/boot/ start it at boot - then press Play."
                            },
                            fontSize = 11.sp,
                            color = WowSubtle,
                            lineHeight = 15.sp,
                        )
                    }
                }
            }

            versionStatus?.takeIf { it.isOutdated }?.let { version ->
                Spacer(modifier = Modifier.height(16.dp))
                WowPanel(accent = Color(0xFFB13A2A), innerTop = Color(0xFF2A130F), innerBottom = Color(0xFF170A08)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = WowError, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            SectionTitle("GAME UPDATE REQUIRED", WowError)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Live servers require build ${version.remoteVersion}, but local files are build ${version.localVersion}. Logging in may fail or disconnect at realm selection.",
                            fontSize = 12.sp,
                            color = Color(0xFFFED7D7),
                            lineHeight = 16.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Tap Update below to download the latest files directly from Blizzard's CDN over Wi-Fi.",
                            fontSize = 11.sp,
                            color = WowCream
                        )
                    }
                }
            }

            appUpdate?.let { update ->
                Spacer(modifier = Modifier.height(16.dp))
                WowPanel {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Refresh, contentDescription = null, tint = WowGold, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            SectionTitle("APP UPDATE AVAILABLE (v${update.version})", WowGold)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "A new release is available on GitHub. Tap below to view notes and update.",
                            fontSize = 12.sp,
                            color = WowCream,
                            lineHeight = 16.sp,
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        PrimaryButton(
                            text = when {
                                isDownloadingAppUpdate -> "DOWNLOADING UPDATE..."
                                downloadedApk?.exists() == true -> "INSTALL UPDATE (v${update.version})"
                                else -> "UPDATE APP (v${update.version})"
                            },
                            icon = Icons.Default.Refresh,
                            enabled = !isDownloadingAppUpdate && !isLaunching && !isUpdating,
                            onClick = {
                                if (downloadedApk?.exists() == true) {
                                    performAppUpdate()
                                } else {
                                    showAppUpdateDialog = true
                                }
                            },
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (isLaunching || isUpdating || (isCheckingVersion && WoWLauncherState.shouldAutoLaunch)) {
                    CircularProgressIndicator(color = WowGold, modifier = Modifier.size(36.dp))
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = when {
                            isUpdating -> updateStatusText
                            isLaunching -> statusText
                            else -> "Checking for game updates..."
                        },
                        fontSize = 13.sp,
                        color = WowCream,
                        textAlign = TextAlign.Center
                    )
                } else {
                    if (filesMissing) {
                        OutlinedButton(
                            onClick = { folderPicker.launch(null) },
                            modifier = Modifier.fillMaxWidth(0.85f).height(48.dp),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(2.dp, WowFrame),
                        ) {
                            Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(20.dp), tint = WowGold)
                            Spacer(modifier = Modifier.width(10.dp))
                            Text("LOCATE GAME FILES", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = WowGold)
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                    }

                    when {
                        versionStatus?.isOutdated == true -> {
                            PrimaryButton(
                                text = "UPDATE TO ${versionStatus?.remoteVersion}",
                                icon = Icons.Default.Refresh,
                                enabled = canUpdate,
                                onClick = ::performUpdate,
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            OutlinedButton(
                                onClick = ::launchGame,
                                enabled = canPlay,
                                modifier = Modifier.fillMaxWidth(0.85f).height(48.dp),
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(2.dp, Color(0xFFB13A2A)),
                            ) {
                                Text(text = "LAUNCH ANYWAY (OUTDATED)", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = WowError)
                            }
                        }
                        !files.hasStorageAccess -> PrimaryButton(
                            text = "GRANT STORAGE PERMISSION",
                            icon = Icons.Default.FolderOpen,
                            enabled = !isLaunching && !isUpdating,
                            onClick = { StorageUtils.requestManageExternalStoragePermission(context) },
                        )
                        !loginReady -> PrimaryButton(
                            text = "SET UP LOGIN TO PLAY",
                            icon = Icons.Default.Key,
                            enabled = canUpdate,
                            onClick = { showLoginDialog = true },
                        )
                        else -> PrimaryButton(
                            text = if (files.isWrongGame) "${flavor.label.uppercase()} INSTALL REQUIRED" else "PLAY ${flavor.shortName.uppercase()}",
                            icon = Icons.Default.PlayArrow,
                            enabled = canPlay,
                            onClick = ::launchGame,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LinkButton("Refresh Status", Icons.Default.Refresh, enabled = !busy) {
                        checkFiles()
                        checkVersionStatus()
                        checkAppUpdate(manual = true)
                    }
                    if (!filesMissing) {
                        LinkButton("Change Location", Icons.Default.FolderOpen, enabled = !busy) { folderPicker.launch(null) }
                    }
                    if (flavor.isLocal) {
                        // A local realm has no Battle.net credentials to configure: its account is created
                        // in the worldserver console (see the server half's runbook).
                    } else if (autoLogin) {
                        LinkButton(
                            text = if (hasSavedLogin) "Update Login" else "Configure Login",
                            icon = Icons.Default.Key,
                            enabled = !busy,
                        ) {
                            showLoginDialog = true
                        }
                        if (hasSavedLogin) {
                            LinkButton("Forget Saved Login", Icons.Default.Delete, enabled = !busy) {
                                BattleNetSignIn.forget(context)
                                BattleNetSignIn.removeLoginFile(File(gamePath))
                                hasSavedLogin = false
                            }
                        }
                        LinkButton("Turn Off Auto-Login", KeyOffIcon, enabled = !busy) {
                            BattleNetSignIn.forget(context)
                            BattleNetSignIn.removeLoginFile(File(gamePath))
                            BattleNetSignIn.setAutoLoginEnabled(context, false)
                            hasSavedLogin = false
                            autoLogin = false
                        }
                    } else {
                        LinkButton("Turn On Auto-Login", Icons.Default.Key, enabled = !busy) {
                            showLoginDialog = true
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = if (betaUpdates) "Beta app updates: On (tap to turn off)" else "Beta app updates: Off (tap to turn on)",
                fontSize = 11.sp,
                color = WowSubtle,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        betaUpdates = !betaUpdates
                        AppUpdater.setBetaEnabled(context, betaUpdates)
                        appUpdate = null
                        checkAppUpdate()
                    }
                    .padding(vertical = 8.dp),
            )
            Text(
                text = if (skipAutoUpdates) "Skip automatic app updates: On (tap to turn off)" else "Skip automatic app updates: Off (tap to turn on)",
                fontSize = 11.sp,
                color = WowSubtle,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        skipAutoUpdates = !skipAutoUpdates
                        AppUpdater.setSkipAutoUpdates(context, skipAutoUpdates)
                        if (skipAutoUpdates) appUpdate = null
                    }
                    .padding(vertical = 8.dp),
            )
            if (flavor.hasRegion) {
                Text(
                    text = "Region: ${region.name} (tap to change)",
                    fontSize = 11.sp,
                    color = WowSubtle,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !isLaunching && !isUpdating) {
                            region = region.next
                            WowFlavor.setRegion(context, region)
                            // A different region has its own servers and game build, so check again.
                            versionStatus = null
                            checkVersionStatus()
                        }
                        .padding(vertical = 8.dp),
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }

    if (showLoginDialog) {
        BattleNetCredentialDialog(
            onDismiss = { showLoginDialog = false },
            onConfirm = { login ->
                showLoginDialog = false
                BattleNetSignIn.save(context, login)
                BattleNetSignIn.setAutoLoginEnabled(context, true)
                hasSavedLogin = true
                autoLogin = true
            },
        )
    }

    if (showDriverDialog) {
        GraphicsDriverDialog(
            gpu = gpu,
            selectedId = driverSelection,
            customDrivers = customDrivers,
            importing = isImportingDriver,
            message = driverMessage,
            error = driverError,
            onDismiss = { showDriverDialog = false },
            onSelectAuto = {
                GraphicsDriverStore.select(context, null)
                driverSelection = null
                driverMessage = "Using the automatic driver."
                driverError = null
            },
            onSelect = { driver ->
                GraphicsDriverStore.select(context, driver.id)
                driverSelection = driver.id
                driverMessage = "Using ${driver.name}."
                driverError = null
            },
            onImport = {
                driverMessage = null
                driverError = null
                driverPicker.launch(
                    arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"),
                )
            },
            onRemove = { driver ->
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) { GraphicsDriverStore.remove(context, driver.id) }
                        customDrivers = GraphicsDriverStore.customDrivers(context)
                        if (driverSelection == driver.id) driverSelection = null
                        driverMessage = "Removed ${driver.name}."
                        driverError = null
                    } catch (e: Exception) {
                        Timber.e(e, "Error removing custom graphics driver")
                        driverError = e.message ?: "Could not remove the driver."
                    }
                }
            },
        )
    }

    if (showStoragePermissionDialog) {
        AlertDialog(
            onDismissRequest = { showStoragePermissionDialog = false },
            title = { Text(text = "Storage Permission Required", fontWeight = FontWeight.Bold, fontSize = 17.sp, color = WowGold) },
            text = {
                Text(
                    text = "Android requires 'All files access' for ${context.getString(R.string.app_name)} " +
                        "to read and update game files in this directory.\n\n" +
                        "Please enable 'Allow access to manage all files' on the next screen.",
                    fontSize = 13.sp,
                    color = WowCream,
                    lineHeight = 18.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showStoragePermissionDialog = false
                        StorageUtils.requestManageExternalStoragePermission(context)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = WowBronze, contentColor = Color.White),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("OPEN SETTINGS", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showStoragePermissionDialog = false }) {
                    Text("CANCEL", color = WowMuted)
                }
            },
            containerColor = WowStoneTop,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.border(3.dp, WowFrame, RoundedCornerShape(10.dp)),
        )
    }

    if (showAppUpdateDialog) {
        appUpdate?.let { update ->
            AlertDialog(
                onDismissRequest = {
                    if (!isDownloadingAppUpdate) showAppUpdateDialog = false
                },
                title = {
                    Text(
                        text = "App Update Available (v${update.version})",
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp,
                        color = WowGold,
                    )
                },
                text = {
                    Column {
                        if (update.notes.isNotBlank()) {
                            Text(
                                text = renderReleaseNotes(update.notes),
                                fontSize = 12.sp,
                                color = WowCream,
                                lineHeight = 16.sp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 240.dp)
                                    .verticalScroll(rememberScrollState()),
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                        }
                        if (isDownloadingAppUpdate) {
                            Text(
                                text = "Downloading: ${(appUpdateProgress * 100).toInt()}%",
                                fontSize = 12.sp,
                                color = WowGold,
                                fontWeight = FontWeight.Bold,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = { appUpdateProgress },
                                modifier = Modifier.fillMaxWidth(),
                                color = WowGold,
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = ::performAppUpdate,
                        enabled = !isDownloadingAppUpdate,
                        colors = ButtonDefaults.buttonColors(containerColor = WowBronze, contentColor = Color.White),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Text(
                            text = when {
                                isDownloadingAppUpdate -> "DOWNLOADING..."
                                downloadedApk?.exists() == true -> "INSTALL NOW"
                                else -> "UPDATE NOW"
                            },
                            fontWeight = FontWeight.Bold,
                        )
                    }
                },
                dismissButton = {
                    if (!isDownloadingAppUpdate) {
                        TextButton(onClick = { showAppUpdateDialog = false }) {
                            Text("LATER", color = WowMuted)
                        }
                    }
                },
                containerColor = WowStoneTop,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.border(3.dp, WowFrame, RoundedCornerShape(10.dp)),
            )
        }
    }

    if (showAppUpdatePermissionDialog) {
        AlertDialog(
            onDismissRequest = { showAppUpdatePermissionDialog = false },
            title = {
                Text(
                    text = "Install Permission Required",
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                    color = WowGold,
                )
            },
            text = {
                Text(
                    text = "Android requires permission to install apps from " +
                        "${context.getString(R.string.app_name)}.\n\n" +
                        "Please enable 'Allow from this source' on the next screen.",
                    fontSize = 13.sp,
                    color = WowCream,
                    lineHeight = 18.sp,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showAppUpdatePermissionDialog = false
                        pendingInstallPermission = true
                        AppUpdater.openInstallPermissionSettings(context)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = WowBronze, contentColor = Color.White),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text("OPEN SETTINGS", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAppUpdatePermissionDialog = false }) {
                    Text("CANCEL", color = WowMuted)
                }
            },
            containerColor = WowStoneTop,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.border(3.dp, WowFrame, RoundedCornerShape(10.dp)),
        )
    }
}

object GamePath {
    private const val PREFS = "wow_forever"
    private const val KEY_GAME_PATH = "game_path"

    // Forever keeps the original key so existing installs keep their saved folder.
    private fun pathKey(flavor: WowFlavor = WowFlavor.current) =
        if (flavor == WowFlavor.FOREVER) KEY_GAME_PATH else "${KEY_GAME_PATH}_${flavor.name.lowercase()}"

    private val defaultPath get() = File(Environment.getExternalStorageDirectory(), WowFlavor.current.defaultFolder)

    data class Status(
        val exeExists: Boolean = false,
        val exeName: String = "",
        val dataExists: Boolean = false,
        val buildInfoExists: Boolean = false,
        /** False for the local flavor: a 3.3.5a client has no .build.info and no Blizzard product. */
        val requiresBuildInfo: Boolean = true,
        val product: String = "",
        val hasStorageAccess: Boolean = true,
    ) {
        val isWrongGame get() = requiresBuildInfo && buildInfoExists && product.isNotBlank() && product != TARGET_PRODUCT
        val isReady get() = exeExists &&
            dataExists &&
            hasStorageAccess &&
            (!requiresBuildInfo || buildInfoExists) &&
            !isWrongGame
    }

    fun status(context: Context, path: String): Status {
        val root = File(path)
        val exe = WowFlavor.current.exeFile(root)
        return Status(
            exeExists = exe.exists(),
            exeName = exe.name,
            dataExists = File(root, "Data").listFiles()?.isNotEmpty() == true,
            buildInfoExists = File(root, BUILD_INFO).isFile,
            requiresBuildInfo = !WowFlavor.current.isLocal,
            product = productOf(root).orEmpty(),
            hasStorageAccess = StorageUtils.hasStoragePermission(context, path),
        )
    }

    fun load(context: Context): String {
        WowFlavor.load(context)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = prefs.getString(pathKey(), null)?.let(::File)
        // A single Battle.net-style folder can hold several products, so the other flavors' folders are candidates too.
        val others = WowFlavor.entries.mapNotNull { prefs.getString(pathKey(it), null)?.let(::File) }
        val usable = if (WowFlavor.current.isLocal) {
            // No .build.info to go by: a local client is the folder that holds Wow.exe and Data/.
            (listOfNotNull(saved, defaultPath) + others).firstOrNull { candidate ->
                File(candidate, "Data").isDirectory && WowFlavor.current.exeFile(candidate).exists()
            }
        } else {
            (listOfNotNull(saved, defaultPath) + others).firstOrNull { productOf(it) == TARGET_PRODUCT }
        }
        return (usable ?: saved ?: defaultPath).absolutePath
    }

    fun save(context: Context, path: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(pathKey(), path).apply()
    }

    fun isReady(context: Context, path: String = load(context)): Boolean =
        path.isNotEmpty() &&
            status(context, path).let {
                it.isReady && (WowFlavor.current.isLocal || it.product == TARGET_PRODUCT)
            }

    fun findGameRoot(picked: File): File? =
        sequenceOf(
            picked,
            picked.parentFile,
            File(picked, WowFlavor.current.defaultFolder),
            File(picked, "WoW Forever"),
            File(picked, "World of Warcraft"),
        )
            .filterNotNull()
            .firstOrNull {
                if (WowFlavor.current.isLocal) {
                    File(it, "Data").isDirectory && WowFlavor.current.exeFile(it).exists()
                } else {
                    File(it, BUILD_INFO).isFile
                }
            }

    private fun productOf(root: File) = WowClientDownloader.readBuildInfo(File(root, BUILD_INFO))?.get("Product")
}

@Composable
private fun WowDivider() {
    Row(modifier = Modifier.fillMaxWidth(0.5f), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).height(1.dp).background(Brush.horizontalGradient(listOf(Color.Transparent, WowFrame))))
        Box(Modifier.padding(horizontal = 8.dp).size(7.dp).rotate(45f).background(WowFrame))
        Box(Modifier.weight(1f).height(1.dp).background(Brush.horizontalGradient(listOf(WowFrame, Color.Transparent))))
    }
}

/** A dark stone panel in a gold frame. [accent] recolors the frame, e.g. red for a warning. */
@Composable
private fun WowPanel(
    accent: Color = WowFrame,
    innerTop: Color = WowStoneTop,
    innerBottom: Color = WowStoneBottom,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth(0.9f)
            .border(3.dp, accent, RoundedCornerShape(10.dp))
            .padding(3.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(innerTop, innerBottom)), RoundedCornerShape(7.dp))
                .border(1.dp, WowFrameInner, RoundedCornerShape(7.dp)),
            content = content,
        )
    }
}

/** The per-game switches. They sit on one row and wrap onto a second only when the screen is too narrow. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LaunchSwitches(
    enabled: Boolean,
    buttonPad: Boolean,
    onButtonPad: (Boolean) -> Unit,
    forceGamepadUi: Boolean,
    onForceGamepadUi: (Boolean) -> Unit,
    r3Toggle: Boolean,
    onR3Toggle: (Boolean) -> Unit,
) {
    @Composable
    fun Item(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = label, fontSize = 12.sp, color = WowMuted)
            Spacer(modifier = Modifier.width(8.dp))
            Switch(checked = checked, enabled = enabled, onCheckedChange = onChange)
        }
    }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(0.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Item("Action button pad", buttonPad, onButtonPad)
        Item("Force gamepad UI", forceGamepadUi, onForceGamepadUi)
        Item("R3 toggles mouse / camera", r3Toggle, onR3Toggle)
    }
}

@Composable
private fun DriverChoiceLink(label: String, enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .alpha(if (enabled) 1f else 0.5f)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.Settings,
            contentDescription = null,
            modifier = Modifier.size(12.dp),
            tint = WowFrame,
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(text = "Driver: $label", fontSize = 11.sp, color = WowGold)
    }
}

@Composable
private fun DriverOption(
    selected: Boolean,
    title: String,
    subtitle: String,
    enabled: Boolean,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, fontSize = 13.sp, color = WowCream)
            if (subtitle.isNotBlank()) {
                Text(text = subtitle, fontSize = 11.sp, color = WowSubtle, lineHeight = 15.sp)
            }
        }
        trailing?.invoke()
    }
}

@Composable
private fun GraphicsDriverDialog(
    gpu: GpuProfile,
    selectedId: String?,
    customDrivers: List<InstalledGraphicsDriver>,
    importing: Boolean,
    message: String?,
    error: String?,
    onDismiss: () -> Unit,
    onSelectAuto: () -> Unit,
    onSelect: (InstalledGraphicsDriver) -> Unit,
    onImport: () -> Unit,
    onRemove: (InstalledGraphicsDriver) -> Unit,
) {
    val selectedIsCustom = customDrivers.any { it.id == selectedId }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "Graphics Driver", fontWeight = FontWeight.Bold, fontSize = 17.sp, color = WowGold) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 430.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = "The automatic choice is the Turnip build matched to this device. " +
                        "You can import an Adrenotools or .adpkg.zip driver package to try another build; " +
                        "the archive must contain meta.json and its library at the root.",
                    fontSize = 12.sp,
                    color = WowCream,
                    lineHeight = 17.sp,
                )
                Spacer(modifier = Modifier.height(10.dp))

                DriverOption(
                    selected = !selectedIsCustom,
                    title = "Auto (recommended)",
                    subtitle = "${gpu.driver} · ${gpu.label}",
                    enabled = !importing,
                    onClick = onSelectAuto,
                )

                customDrivers.forEach { driver ->
                    DriverOption(
                        selected = selectedId == driver.id,
                        title = driver.name,
                        subtitle = listOfNotNull(
                            driver.version.takeIf { it.isNotBlank() },
                            "API ${driver.minApi}".takeIf { driver.minApi > 0 },
                        ).joinToString(" · "),
                        enabled = !importing,
                        onClick = { onSelect(driver) },
                        trailing = {
                            TextButton(
                                onClick = { onRemove(driver) },
                                enabled = !importing,
                            ) {
                                Text(text = "REMOVE", fontSize = 10.sp, color = WowError)
                            }
                        },
                    )
                }

                if (customDrivers.isEmpty()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "No custom drivers imported yet.",
                        fontSize = 11.sp,
                        color = WowSubtle,
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "A custom driver keeps this device's ${gpu.tuDebug} TU_DEBUG and " +
                        "${gpu.screenSize} screen size. An incompatible driver can prevent the " +
                        "game from starting; choose Auto to recover.",
                    fontSize = 11.sp,
                    color = WowSubtle,
                    lineHeight = 15.sp,
                )

                message?.let {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = it, fontSize = 11.sp, color = Color(0xFF9BD18A), lineHeight = 15.sp)
                }
                error?.let {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = it, fontSize = 11.sp, color = WowError, lineHeight = 15.sp)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onImport,
                enabled = !importing,
                colors = ButtonDefaults.buttonColors(containerColor = WowBronze, contentColor = Color.White),
                shape = RoundedCornerShape(10.dp),
            ) {
                Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (importing) "IMPORTING…" else "IMPORT DRIVER",
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !importing) {
                Text(text = "CLOSE", color = WowMuted)
            }
        },
        containerColor = WowStoneTop,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.border(3.dp, WowFrame, RoundedCornerShape(10.dp)),
    )
}

@Composable
private fun FlavorSelector(selected: WowFlavor, enabled: Boolean, onSelect: (WowFlavor) -> Unit) {
    // Top row: Forever, Retail, Classic. The second row carries the three Classic products when a
    // Classic flavor is selected, and the local realm otherwise, so every flavor stays one tap away.
    val top = listOf(WowFlavor.FOREVER, WowFlavor.RETAIL, WowFlavor.CLASSIC_ERA)
    val classic = listOf(WowFlavor.CLASSIC_ERA, WowFlavor.CLASSIC_ANNIVERSARY, WowFlavor.CLASSIC_MOP)
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.alpha(if (enabled) 1f else 0.5f)) {
        FlavorButtonRow(
            items = top,
            text = { if (it.isClassic) "CLASSIC" else it.shortName.uppercase() },
            isSelected = { if (it.isClassic) selected.isClassic else it == selected },
            enabled = enabled,
            onSelect = { if (it.isClassic && selected.isClassic) Unit else onSelect(it) },
        )
        Spacer(modifier = Modifier.height(6.dp))
        if (selected.isClassic) {
            FlavorButtonRow(
                items = classic,
                text = { it.shortName.uppercase() },
                isSelected = { it == selected },
                enabled = enabled,
                onSelect = onSelect,
            )
        } else {
            FlavorButtonRow(
                items = listOf(WowFlavor.LOCAL_AC),
                text = { it.shortName.uppercase() },
                isSelected = { it == selected },
                enabled = enabled,
                onSelect = onSelect,
            )
        }
    }
}

@Composable
private fun FlavorButtonRow(
    items: List<WowFlavor>,
    text: (WowFlavor) -> String,
    isSelected: (WowFlavor) -> Boolean,
    enabled: Boolean,
    onSelect: (WowFlavor) -> Unit,
) {
    Row {
        items.forEachIndexed { index, flavor ->
            val selected = isSelected(flavor)
            val shape = when {
                items.size == 1 -> RoundedCornerShape(8.dp)
                index == 0 -> RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp)
                index == items.lastIndex -> RoundedCornerShape(topEnd = 8.dp, bottomEnd = 8.dp)
                else -> RoundedCornerShape(0.dp)
            }
            val faces = if (selected) listOf(WowBronze, Color(0xFF462C0C)) else listOf(Color(0xFF28201A), Color(0xFF16110E))
            Box(
                modifier = Modifier
                    .background(Brush.verticalGradient(faces), shape)
                    .border(2.dp, if (selected) WowFrame else WowFrameInner, shape)
                    .clickable(enabled = enabled) { onSelect(flavor) }
                    .padding(horizontal = 18.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text(flavor),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    color = if (selected) Color(0xFFFFECB4) else WowMuted,
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String, color: Color) {
    Text(
        text = text,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = FontFamily.Serif,
        letterSpacing = 3.sp,
        color = color,
    )
}

@Composable
private fun PrimaryButton(text: String, icon: ImageVector, enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth(0.85f)
            .height(56.dp)
            .alpha(if (enabled) 1f else 0.5f)
            .background(Brush.verticalGradient(listOf(WowCrimsonTop, WowCrimsonBottom)), shape)
            .border(3.dp, WowGold, shape)
            .clickable(enabled = enabled, onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp), tint = Color(0xFFFFECB4))
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = text,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Serif,
            letterSpacing = 2.sp,
            color = Color(0xFFFFECB4),
        )
    }
}

@Composable
private fun LinkButton(text: String, icon: ImageVector, enabled: Boolean = true, onClick: () -> Unit) {
    val tint = if (enabled) WowFrame else WowFrame.copy(alpha = 0.4f)
    TextButton(onClick = onClick, enabled = enabled) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = tint)
        Spacer(modifier = Modifier.width(6.dp))
        Text(text, fontSize = 12.sp, color = tint)
    }
}

@Composable
private fun CheckItem(label: String, ready: Boolean, warning: Boolean = false) {
    // Green: ready. Yellow: not ready, but it doesn't stop you playing. Red: not ready and blocks Play.
    val yellow = !ready && warning
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .background(
                    when {
                        ready -> Color(0xFF4F9A3C)
                        yellow -> Color(0xFFD9A441)
                        else -> Color(0xFFB13A2A)
                    },
                    CircleShape,
                )
                .border(
                    2.dp,
                    when {
                        ready -> Color(0xFF1D3A16)
                        yellow -> Color(0xFF6B4E14)
                        else -> Color(0xFF4A1710)
                    },
                    CircleShape,
                ),
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(text = label, fontSize = 13.sp, color = if (ready) WowCream else Color(0xFFE0A050))
    }
}

/**
 * The per-game switches: the action bar pad on the second display, forcing WoW's gamepad UI, and R3
 * mouse/camera toggling. The launcher's main screen and the in-game pad settings both read and write
 * them.
 */
internal object ButtonPad {
    private const val PREFS = "wow_forever"

    // Per game, so each flavor remembers its own switches. The pad is on for every game by default.
    private fun padKey(flavor: WowFlavor) = "button_pad_${flavor.name}"
    private fun gamepadKey(flavor: WowFlavor) = "force_gamepad_ui_${flavor.name}"

    fun isEnabled(context: Context, flavor: WowFlavor) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(padKey(flavor), true)

    fun setEnabled(context: Context, flavor: WowFlavor, enabled: Boolean) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(padKey(flavor), enabled).apply()

    /** On: the launcher writes the gamepad lines into Config.wtf each launch. Off: it leaves them alone. */
    fun forceGamepadUi(context: Context, flavor: WowFlavor) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(gamepadKey(flavor), false)

    private fun r3Key(flavor: WowFlavor) = "r3_toggle_${flavor.name}"

    /**
     * Whether R3 switches between camera and the launcher's cursor for this game. Off for the local
     * realm by default: its client runs ConsolePort, which drives the right stick and R3 itself, and two
     * cursor modes fighting over the same stick is what made R3 feel broken there. Any other game keeps
     * it on, since nothing else provides a cursor.
     */
    fun r3Toggle(context: Context, flavor: WowFlavor) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(r3Key(flavor), !flavor.isLocal)

    /** Saves the choice for [flavor] and, since the controller reads the pad setting, applies it now. */
    fun setR3Toggle(context: Context, flavor: WowFlavor, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(r3Key(flavor), enabled).apply()
        PadSettings.init(context)
        PadSettings.set(PadSettings.R3_TOGGLE, enabled)
    }

    fun setForceGamepadUi(context: Context, flavor: WowFlavor, enabled: Boolean) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(gamepadKey(flavor), enabled).apply()
}

private class StorageAccessDeniedException(cause: Throwable) : Exception(cause)

private fun Throwable.isPermissionDenied() =
    message?.let { "EPERM" in it || "Operation not permitted" in it } == true

private const val PROTON_VERSION = "proton-11.0-90624-arm64ec"
private const val DXVK_VERSION = "2.4.1-wow-aarch64-test"

/** DXVK's 32-bit d3d9, which the 3.3.5a client needs because wined3d cannot serve d3d9 here. */
private const val D3D9_X32_ASSET = "d3d9-x32/d3d9.dll"

/** The shipped profile installs no WinComponents: every DLL comes from the bundled runtime. */
private const val WINCOMPONENTS_OFF =
    "direct3d=0,directsound=0,directinput8=0,directinput=0,directmusic=0,directshow=0," +
        "directplay=0,vcrun2010=0,wmdecoder=0,opengl=0"

private data class GpuProfile(val driver: String, val tuDebug: String, val screenSize: String, val label: String, val edition: String) {
    /** Keep the detected GPU flags and screen size, but use a user-selected driver id. */
    fun withDriver(driver: String) = copy(driver = driver)

    companion object {
        fun detect(context: Context) = if (isAdreno8xxDevice(context)) {
            GpuProfile(BUNDLED_TURNIP_8XX, "noconform,sysmem", "1280x720", "Adreno 8xx", "Snapdragon 8 Elite / Adreno 8xx")
        } else {
            GpuProfile(BUNDLED_TURNIP_740, "noconform", "1920x1080", "Adreno 740", "Snapdragon 8 Series / Adreno 740")
        }

        private fun isAdreno8xxDevice(context: Context): Boolean {
            if (GPUInformation.isAdreno8Elite(context)) return true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val soc = Build.SOC_MODEL
                if (soc.contains("SM8750", ignoreCase = true) || soc.contains("SM8850", ignoreCase = true)) return true
            }
            return Build.HARDWARE.contains("sun", ignoreCase = true)
        }
    }
}

private fun prepareLaunch(context: Context, gameRoot: File, gpu: GpuProfile, onStatus: (String) -> Unit): String {
    installBundledComponents(context, onStatus)

    val flavor = WowFlavor.current
    if (flavor.isLocal) {
        // A 3.3.5a client brings its own Wow.exe + Data/ and no Battle.net metadata, so it must be
        // in place before we launch: there is no CDN to fetch it from.
        check(File(gameRoot, "Data").isDirectory) { "No Data/ folder in $gameRoot. Copy your 3.3.5a client there." }
        check(flavor.exeFile(gameRoot).exists()) { "No ${flavor.exeName} in $gameRoot. Copy your 3.3.5a client there." }
    } else {
        check(File(gameRoot, BUILD_INFO).exists()) { "Missing $BUILD_INFO in $gameRoot. Copy it from your WoW install." }
    }
    try {
        PadSettings.init(context)
        PadSettings.launchProfile(WowFlavor.current.name).takeIf { it > 0 }?.let { PadSettings.loadProfile(it) }
        PadSettings.set(PadSettings.R3_TOGGLE, ButtonPad.r3Toggle(context, WowFlavor.current))
        ensureGameConfig(gameRoot, if (ButtonPad.forceGamepadUi(context, WowFlavor.current)) 0 else 1, gpu.screenSize)
        if (WowFlavor.current.isLocal) installLocalAddons(context, WowFlavor.current.clientDir(gameRoot), onStatus)
    } catch (e: Exception) {
        if (e.isPermissionDenied()) throw StorageAccessDeniedException(e)
        throw e
    }

    if (!flavor.isLocal) {
        if (BattleNetSignIn.autoLoginEnabled(context)) {
            BattleNetSignIn.load(context)?.let { login ->
                BattleNetSignIn.writeLoginFile(gameRoot, login)
            }
        } else {
            BattleNetSignIn.removeLoginFile(gameRoot)
        }
        if (!flavor.exeFile(gameRoot).exists()) {
            try {
                WowClientDownloader.download(gameRoot, onStatus)
            } catch (e: Exception) {
                if (!flavor.exeFile(gameRoot).exists()) {
                    throw IllegalStateException("Couldn't download the ${flavor.label} client: ${e.message}", e)
                }
                Timber.w(e, "Client update check failed, launching the existing ${flavor.label} client")
            }
        }
    }
    val clientExe = flavor.exeFile(gameRoot)
    check(clientExe.exists()) { "No ${if (flavor.isLocal) flavor.exeName else "ARM64 client"} in ${clientExe.parent}." }

    onStatus("Configuring ${gpu.label} container...")
    val containerManager = ContainerManager(context)
    val config = containerConfig(context, gameRoot, gpu, clientExe.name, gpu.screenSize, flavor)
    val existing = containerManager.getContainerById(flavor.containerId)
    if (existing == null) {
        onStatus("Creating prefix environment (first boot)...")
        containerManager.createContainer(flavor.containerId, config)
    } else {
        existing.loadData(config)
    }
    val container = checkNotNull(containerManager.getContainerById(flavor.containerId)) {
        "Container creation failed. Check system storage and logs."
    }
    // Second display (if any) shows the 12-button action bar pad when its switch is on.
    container.setExternalDisplayMode(
        if (ButtonPad.isEnabled(context, WowFlavor.current)) {
            Container.EXTERNAL_DISPLAY_MODE_BUTTONS
        } else {
            Container.EXTERNAL_DISPLAY_MODE_OFF
        },
    )
    // winhandler.exe is an x86-64 helper and this build ships no FEX for it, so a non-ARM64 client
    // (a 3.3.5a Wow.exe here) is started by wine directly. Wine's wow64 layer and the bundled box64
    // HODLL run the 32-bit image; the ARM64 clients are unaffected because they never use the wrapper.
    container.putExtra(CONTAINER_EXTRA_SKIP_WINHANDLER, "1")
    if (flavor.isLocal) {
        // A 3.3.5a client reads no gamepad, so its container gets the profile that turns the pad into
        // keys and pointer motion instead of the default one, which forwards it as a virtual pad.
        container.putExtra(CONTAINER_EXTRA_PROFILE_ID, ensureLocalControllerProfile(context).toString())
        installD3d9x32(context, container, onStatus)
    }
    container.saveData()
    return flavor.containerId
}

/**
 * Installs DXVK's 32-bit d3d9 into the container's wow64 system directory.
 *
 * The 3.3.5a client is Direct3D 9 only and wined3d cannot serve it on this Proton: its GL renderer
 * trips wow64's WGL buffer mapping ("buffer->host_ptr == buffer->vm_ptr" in opengl32/unix_wgl.c) and
 * its Vulkan renderer has no d3d9 state table, so CreateDevice fails and the client reports that 3D
 * acceleration is unavailable. WineUtils already overrides d3d9 as "native,builtin" and copies i386
 * DLLs into this directory for arm64ec wine, so the file is what the prefix is waiting for. A
 * d3d9.dll the user put there themselves is left alone.
 */
private fun installD3d9x32(context: Context, container: Container, onStatus: (String) -> Unit) {
    val target = File(container.getRootDir(), ".wine/drive_c/windows/syswow64/d3d9.dll")
    if (target.exists()) return
    onStatus("Installing 32-bit Direct3D 9...")
    target.parentFile?.mkdirs()
    context.assets.open(D3D9_X32_ASSET).use { source ->
        target.outputStream().use { sink -> source.copyTo(sink) }
    }
}

private fun containerConfig(
    context: Context,
    gameRoot: File,
    gpu: GpuProfile,
    exeName: String,
    screenSize: String,
    flavor: WowFlavor,
): JSONObject {
    val samsungEnv = if (Build.MANUFACTURER.equals("samsung", ignoreCase = true)) " FD_DEV_FEATURES=enable_tp_ubwc_flag_hint=1" else ""
    val envVars = "WRAPPER_MAX_IMAGE_COUNT=0 ZINK_DESCRIPTORS=lazy ZINK_DEBUG=compact,deck_emu MESA_SHADER_CACHE_DISABLE=false MESA_SHADER_CACHE_MAX_SIZE=512MB mesa_glthread=true WINEESYNC=0 MESA_VK_WSI_PRESENT_MODE=mailbox TU_DEBUG=${gpu.tuDebug} VKD3D_SHADER_MODEL=6_0 PULSE_LATENCY_MSEC=144$samsungEnv"
    return JSONObject().apply {
        put("id", flavor.containerId)
        put("name", if (flavor.isLocal) "AzerothCore 3.3.5a" else "WoW Forever")
        put("screenSize", screenSize)
        // The 3.3.5a client is D3D9-only, and DXVK's 32-bit d3d9 (installed in prepareLaunch) is what
        // serves it. WINE_D3D_CONFIG only matters if that file is missing: wined3d's GL renderer trips
        // Proton's wow64 WGL buffer mapping ("buffer->host_ptr == buffer->vm_ptr" in
        // opengl32/unix_wgl.c) and its Vulkan renderer has no d3d9 state table, so the Vulkan renderer
        // is the better of the two ways to fail. DXVK's own log is the thing to read either way; the
        // app otherwise silences it with DXVK_LOG_LEVEL=none, and the container's variables are
        // applied after that.
        put(
            "envVars",
            if (flavor.isLocal) {
                "$envVars BOX64_LOG=1 BOX64_DYNAREC_MISSING=1 WINE_D3D_CONFIG=renderer=vulkan " +
                    "DXVK_LOG_LEVEL=info DXVK_LOG_PATH=G:\\"
            } else {
                envVars
            },
        )
        put("graphicsDriver", "Wrapper")
        put("graphicsDriverVersion", gpu.driver)
        put("graphicsDriverConfig", "version=${gpu.driver},adrenotoolsTurnip=1,resourceType=buffer,bcnEmulation=auto,quality=high")
        put("displayRenderer", "vulkan")
        put("dxwrapper", "dxvk-$DXVK_VERSION-1")
        put("dxwrapperConfig", "version=$DXVK_VERSION-1")
        put("wineVersion", "$PROTON_VERSION-1")
        put("containerVariant", "bionic")
        if (!flavor.isLocal) {
            put("fexcoreVersion", "2609-0")
        }
        put("drives", "D:/storage/emulated/0/DownloadE:/data/data/${context.packageName}/storageG:${gameRoot.path}")
        // A local client sits at the root of its folder; Blizzard's live in their _product_ subfolder.
        put("executablePath", if (flavor.isLocal) "G:\\$exeName" else "G:\\$FLAVOR_DIR\\$exeName")
        // 3.3.5a has no -d3d11 switch: D3D9 is its only renderer, and this prefix serves that from
        // Wine's own 32-bit wined3d. The bundled DXVK carries no d3d9.dll and no syswow64/ at all while
        // the prefix overrides d3d9 as "native,builtin" (WineUtils), so the call lands on wined3d by
        // itself - which is why wincomponents stays at the shipped all-zero profile (no downloads).
        put("execArgs", if (flavor.isLocal) "" else "-d3d11")
        if (flavor.isLocal) {
            // A 32-bit Windows client needs the WoW64 emulator: the bundled wowbox64 HODLL.
            put("emulator", "Box64")
        }
        put("showFPS", true)
        put("startupSelection", Container.STARTUP_SELECTION_AGGRESSIVE.toInt())
        put("wow64Mode", true)
        put("wincomponents", WINCOMPONENTS_OFF)
    }
}

private val CONFIG_DEFAULTS get() = linkedMapOf(
    "portal" to "\"${WowFlavor.current.activePortal}\"",
    "agentUID" to "\"$TARGET_PRODUCT\"",
    "gxApi" to "\"D3D11\"",
    "textLocale" to "\"enUS\"",
    "audioLocale" to "\"enUS\"",
    "gxMaximize" to "\"1\"",
    "gxWindowedResolution" to "\"1920x1080\"",
    "graphicsQuality" to "\"0\"",
    "ResampleQuality" to "\"0\"",
    "RenderScale" to "\"1\"",
    "farclip" to "\"1200\"",
    "horizonClip" to "\"1200\"",
    "RAIDfarclip" to "\"1200\"",
    "RAIDhorizonClip" to "\"1200\"",
    "shadowMode" to "\"0\"",
    "graphicsShadowQuality" to "\"0\"",
    "raidGraphicsShadowQuality" to "\"0\"",
    "worldBaseMip" to "\"0\"",
    "RAIDworldBaseMip" to "\"0\"",
    "graphicsTextureResolution" to "\"2\"",
    "raidGraphicsTextureResolution" to "\"2\"",
    "componentTextureLevel" to "\"0\"",
    "RAIDcomponentTextureLevel" to "\"0\"",
    "entityShadowFadeScale" to "\"0\"",
    "refraction" to "\"0\"",
    "groundEffectDensity" to "\"16\"",
    "GamePadEnable" to "\"1\"",
    "InputDeviceInterfaceStyle" to "\"1\"",
)

/**
 * The same defaults for a 3.3.5a client: no Battle.net keys (`portal`, `agentUID`), its own renderer
 * (`D3D9`, not `D3D11`), the logon server this install runs, and the desktop it is launched into.
 *
 * The geometry keys are 3.3.5a's own. `gxWindowedResolution` is a modern-client key that a 3.3.5a
 * client ignores, and without `gxWindow`/`gxResolution` it opens its 640x480 default window, which
 * lands in the corner of the container's desktop.
 */
private fun localConfigDefaults(screenSize: String) = CONFIG_DEFAULTS.also { defaults ->
    defaults.remove("portal")
    defaults.remove("agentUID")
    defaults.remove("gxWindowedResolution")
    defaults["gxApi"] = "\"D3D9\""
    defaults["realmList"] = "\"$LOCAL_REALM_ADDRESS\""
    defaults["gxWindow"] = "\"1\""
    defaults["gxResolution"] = "\"$screenSize\""
}

private val ALWAYS_FORCED_CONFIG_KEYS get() =
    if (WowFlavor.current.isLocal) {
        // Neither the realm nor the client's shape is a preference here: the realm has to match the
        // server this install runs, and the desktop is fixed by the GPU profile, so a 3.3.5a client
        // that saved its own 640x480 window (or exclusive fullscreen, which cannot change the display
        // mode in this X server) is corrected instead of left that way.
        listOf("gxApi", "realmList", "gxWindow", "gxMaximize", "gxResolution")
    } else {
        listOf("gxApi", "RenderScale", "ResampleQuality") + if (WowFlavor.current.hasRegion) listOf("portal") else emptyList()
    }
private val GAMEPAD_CONFIG_KEYS = listOf("GamePadEnable", "InputDeviceInterfaceStyle")

private val LEGACY_CONFIG_VALUES = mapOf(
    "farclip" to "\"3000\"",
    "horizonClip" to "\"3000\"",
    "RAIDfarclip" to "\"3000\"",
    "RAIDhorizonClip" to "\"3000\"",
    "worldBaseMip" to "\"2\"",
    "RAIDworldBaseMip" to "\"2\"",
    "graphicsTextureResolution" to "\"0\"",
    "raidGraphicsTextureResolution" to "\"0\"",
    "componentTextureLevel" to "\"1\"",
    "RAIDcomponentTextureLevel" to "\"1\"",
)

/**
 * Installs the pad profile this flavour plays with. WoW 3.3.5a has no gamepad support of its own, so
 * the pad cannot reach the client as a pad: it has to arrive as keys and pointer motion, which is what
 * ConsolePortLK (the ConsolePort backport installed into Interface/AddOns) expects. The mapping is the
 * one WoWpadX, the mapper that add-on was built against, sends - face buttons and D-pad on F1-F12, the
 * shoulders on F7/F8, the triggers on shift/control, the left stick on WASD, and the right stick on
 * relative pointer motion, which turns the camera while ConsolePort holds mouse look for us.
 *
 * F7/F8 are ConsolePort's two shoulder buttons, so they carry the pad's shoulders (L1/R1); shift and
 * control are its two modifiers, which the triggers hold. Anything else would make ConsolePort draw
 * "L1" on a prompt for a button on the right hand.
 *
 * The codes come from the app's own axis convention rather than literals, and the profile is only ever
 * written when a profile of this name does not exist yet: after the first launch it belongs to the
 * player, who binds its keys to ConsolePort's buttons once with /cp and may edit it freely. The one
 * exception is a profile whose four shoulders still hold exactly what this function shipped: that is
 * the installed layout, not the player's, and it is corrected once in place.
 */
internal fun ensureLocalControllerProfile(context: Context): Int {
    val manager = InputControlsManager(context)
    manager.profiles.firstOrNull { it.name == LOCAL_CONTROLLER_PROFILE }?.let { installed ->
        migrateShoulderKeys(installed)
        return installed.id
    }

    val id = (manager.profiles.maxOfOrNull { it.id } ?: 0) + 1
    val profile = ControlsProfile(context, id)
    profile.setName(LOCAL_CONTROLLER_PROFILE)
    // "*" is the wildcard controller, so this covers the Thor's built-in pad and any pad paired to it.
    val pad = profile.addController("*")

    fun bind(keyCode: Int, binding: Binding) {
        val entry = ExternalControllerBinding()
        entry.setKeyCode(keyCode)
        entry.setBinding(binding)
        pad.addControllerBinding(entry)
    }

    /** An axis direction in the app's own pseudo-key-code space, e.g. stick up is a negative Y. */
    fun axis(axis: Int, sign: Int) = ExternalControllerBinding.getKeyCodeForAxis(axis, sign.toByte())

    bind(KeyEvent.KEYCODE_BUTTON_Y, Binding.KEY_F9)
    bind(KeyEvent.KEYCODE_BUTTON_B, Binding.KEY_F10)
    bind(KeyEvent.KEYCODE_BUTTON_A, Binding.KEY_F11)
    bind(KeyEvent.KEYCODE_BUTTON_X, Binding.KEY_F12)

    bind(KeyEvent.KEYCODE_DPAD_UP, Binding.KEY_F1)
    bind(KeyEvent.KEYCODE_DPAD_RIGHT, Binding.KEY_F2)
    bind(KeyEvent.KEYCODE_DPAD_DOWN, Binding.KEY_F3)
    bind(KeyEvent.KEYCODE_DPAD_LEFT, Binding.KEY_F4)

    bind(KeyEvent.KEYCODE_BUTTON_SELECT, Binding.KEY_F5)
    bind(KeyEvent.KEYCODE_BUTTON_START, Binding.KEY_F6)
    bind(KeyEvent.KEYCODE_BUTTON_MODE, Binding.KEY_KP_MULTIPLY)

    for ((keyCode, binding) in THOR_SHOULDER_KEYS) bind(keyCode, binding)

    bind(axis(MotionEvent.AXIS_Y, -1), Binding.KEY_W)
    bind(axis(MotionEvent.AXIS_Y, 1), Binding.KEY_S)
    bind(axis(MotionEvent.AXIS_X, -1), Binding.KEY_A)
    bind(axis(MotionEvent.AXIS_X, 1), Binding.KEY_D)

    bind(axis(MotionEvent.AXIS_RZ, -1), Binding.MOUSE_MOVE_UP)
    bind(axis(MotionEvent.AXIS_RZ, 1), Binding.MOUSE_MOVE_DOWN)
    bind(axis(MotionEvent.AXIS_Z, -1), Binding.MOUSE_MOVE_LEFT)
    bind(axis(MotionEvent.AXIS_Z, 1), Binding.MOUSE_MOVE_RIGHT)

    profile.save()
    return id
}

/**
 * Installs the controller add-ons into the client's `Interface/AddOns`. A 3.3.5a client accepts any
 * keys it is sent, but it has no idea what a controller is, so the console behaviour - the button
 * rings, the action bar, targeting, cursor mode - has to live in an add-on. That add-on is
 * ConsolePortLK, and beside it go two of this project's own: ThorPad, which hands ConsolePort the keys
 * this pad sends so nobody has to calibrate a handheld, and EInteract, one interact key for the realm's
 * mod-e-interact.
 *
 * Idempotent and self-healing: the marker records both versions and keeps a launch from re-extracting
 * the textures or re-copying the defaults, a changed version is installed over the old copy, and
 * add-ons the player added are left alone.
 */
internal fun installLocalAddons(context: Context, clientRoot: File, onStatus: (String) -> Unit) {
    val installed = "$LOCAL_ADDON_VERSION $LOCAL_PAD_ADDON_VERSION $LOCAL_INTERACT_ADDON_VERSION"
    val marker = File(clientRoot, LOCAL_ADDON_MARKER)
    if (marker.isFile && marker.readText().trim() == installed) return

    onStatus("Installing the controller add-ons...")
    val addOns = File(clientRoot, "Interface/AddOns")
    addOns.mkdirs()
    check(FileUtils.extractZipFromAssets(context, "bundled_components/$LOCAL_ADDON_ARCHIVE", addOns)) {
        "Could not install $LOCAL_ADDON_ARCHIVE into ${addOns.path}."
    }
    // Ours is two small files, so it ships as assets rather than inside the pinned archive.
    FileUtils.copy(context, LOCAL_PAD_ADDON, File(addOns, LOCAL_PAD_ADDON_FOLDER))
    check(File(addOns, "$LOCAL_PAD_ADDON_FOLDER/$LOCAL_PAD_ADDON_FOLDER.toc").isFile) {
        "Could not install $LOCAL_PAD_ADDON_FOLDER into ${addOns.path}."
    }
    // EInteract, likewise: the addon sends the hidden whisper its server module answers.
    FileUtils.copy(context, LOCAL_INTERACT_ADDON, File(addOns, LOCAL_INTERACT_ADDON_FOLDER))
    check(File(addOns, "$LOCAL_INTERACT_ADDON_FOLDER/$LOCAL_INTERACT_ADDON_FOLDER.toc").isFile) {
        "Could not install $LOCAL_INTERACT_ADDON_FOLDER into ${addOns.path}."
    }
    marker.writeText("$installed\n")
}

/**
 * The pad's shoulders, as ConsolePortLK numbers them: L1/R1 are its two shoulder buttons (F7/F8) and
 * L2/R2 are its shift and control modifiers, which is the layout it draws in a prompt. The face
 * buttons, D-pad and sticks are positional and need no translation.
 */
private val THOR_SHOULDER_KEYS = linkedMapOf(
    KeyEvent.KEYCODE_BUTTON_L1 to Binding.KEY_F7,
    KeyEvent.KEYCODE_BUTTON_R1 to Binding.KEY_F8,
    KeyEvent.KEYCODE_BUTTON_L2 to Binding.KEY_SHIFT_L,
    KeyEvent.KEYCODE_BUTTON_R2 to Binding.KEY_CTRL_L,
)

/** What those four shoulders held before the layout was corrected, so a stock profile can be fixed. */
private val LEGACY_SHOULDER_KEYS = mapOf(
    KeyEvent.KEYCODE_BUTTON_L1 to Binding.KEY_SHIFT_L,
    KeyEvent.KEYCODE_BUTTON_R1 to Binding.KEY_F7,
    KeyEvent.KEYCODE_BUTTON_L2 to Binding.KEY_CTRL_L,
    KeyEvent.KEYCODE_BUTTON_R2 to Binding.KEY_F8,
)

/**
 * Corrects a profile this launcher wrote before the shoulder layout was fixed, in place: the player's
 * own profile is never rewritten, so this only runs when all four shoulders still hold exactly what
 * was installed. Signs are left alone, so a pad the player has added stays where it is.
 */
private fun migrateShoulderKeys(profile: ControlsProfile) {
    val pad = profile.loadControllers().firstOrNull { it.id == "*" } ?: return
    val bindings = pad.getControllerBindings()
    val held = bindings.associate { it.getKeyCodeForAxis() to it.getBinding() }
    if (LEGACY_SHOULDER_KEYS.any { (keyCode, binding) -> held[keyCode] != binding }) return

    bindings.filter { it.getKeyCodeForAxis() in THOR_SHOULDER_KEYS }.forEach { pad.removeControllerBinding(it) }
    for ((keyCode, binding) in THOR_SHOULDER_KEYS) {
        val entry = ExternalControllerBinding()
        entry.setKeyCode(keyCode)
        entry.setBinding(binding)
        pad.addControllerBinding(entry)
    }
    profile.save()
}

/** [gamepadMode]: 0 = force gamepad UI on, 1 = leave the player's lines alone, 2 = force off. */
private fun ensureGameConfig(root: File, gamepadMode: Int, screenSize: String) {
    val flavor = WowFlavor.current
    val defaults = (if (flavor.isLocal) localConfigDefaults(screenSize) else CONFIG_DEFAULTS).also { d ->
        when (gamepadMode) {
            2 -> GAMEPAD_CONFIG_KEYS.forEach { d[it] = "\"0\"" }
            // Leave alone: also never add the lines when they're missing. WoW drops settings that are back at
            // their default from Config.wtf, so turning the gamepad UI off in-game removes these lines, and
            // adding them back as "1" would switch it on again at every launch.
            1 -> GAMEPAD_CONFIG_KEYS.forEach { d.remove(it) }
        }
    }
    val forcedKeys = if (gamepadMode == 1) ALWAYS_FORCED_CONFIG_KEYS else ALWAYS_FORCED_CONFIG_KEYS + GAMEPAD_CONFIG_KEYS
    val flavorDir = flavor.clientDir(root)
    flavorDir.mkdirs()
    if (flavor.isLocal) {
        writeLocalRealmList(flavorDir)
    } else {
        val flavorInfo = File(flavorDir, ".flavor.info")
        if (!flavorInfo.exists()) {
            flavorInfo.writeText("Product Flavor!STRING:0\n$TARGET_PRODUCT\n")
        }
    }
    val configWtf = File(flavorDir, "WTF/Config.wtf")
    if (!configWtf.exists()) {
        configWtf.parentFile?.mkdirs()
        configWtf.writeText(defaults.entries.joinToString("\n") { "SET ${it.key} ${it.value}" } + "\n")
        return
    }

    val original = configWtf.readLines().filterNot { it.startsWith("SET ResampleSharpness", ignoreCase = true) }
    val existingKeys = mutableSetOf<String>()
    val updated = original.map { line ->
        val parts = line.takeIf { it.startsWith("SET ", ignoreCase = true) }?.removePrefix("SET ")?.trim()?.split(" ", limit = 2)
        if (parts?.size != 2) return@map line
        val (key, value) = parts
        existingKeys.add(key)
        val forcedKey = forcedKeys.firstOrNull { it.equals(key, ignoreCase = true) }
        when {
            forcedKey != null && value != defaults.getValue(forcedKey) -> "SET $forcedKey ${defaults.getValue(forcedKey)}"
            LEGACY_CONFIG_VALUES[key] == value -> "SET $key ${defaults.getValue(key)}"
            else -> line
        }
    } + defaults.filterKeys { it !in existingKeys }.map { (k, v) -> "SET $k $v" }

    if (updated != original) {
        configWtf.writeText(updated.joinToString("\n") + "\n")
    }
}

/**
 * Points the local client at the local realm. A 3.3.5a client reads realmlist.wtf from `Data/<locale>/`
 * and from its own root, and which one wins is not worth relying on, so both are written. They are
 * rewritten whenever they drift - e.g. right after copying a client from a PC that played elsewhere.
 */
private fun writeLocalRealmList(clientDir: File) {
    val wanted = "set realmlist $LOCAL_REALM_ADDRESS\r\n"
    val realmLists = listOf(
        File(File(clientDir, "Data/$LOCAL_CLIENT_LOCALE"), "realmlist.wtf"),
        File(clientDir, "realmlist.wtf"),
    )
    for (realmList in realmLists) {
        if (realmList.isFile && realmList.readText() == wanted) continue
        realmList.parentFile?.mkdirs()
        realmList.writeText(wanted)
    }
}

private fun installBundledComponents(context: Context, onStatus: (String) -> Unit) {
    installDriver(context, BUNDLED_TURNIP_740, "turnip-wow-scheduler-test.zip", onStatus)
    installDriver(context, BUNDLED_TURNIP_8XX, "turnip-V32-RP6sched.zip", onStatus)

    val protonDir = File(ImageFs.getSharedProtonDir(context), PROTON_VERSION)
    if (!File(protonDir, "profile.json").exists() || !File(protonDir, "bin/wine").exists()) {
        onStatus("Extracting Proton 11 ARM64EC (takes ~10s)...")
        check(extractBundledWcp(context, "$PROTON_VERSION.wcp", protonDir)) { "Failed to extract Proton or graphics components." }
        File(protonDir, "bin").listFiles()?.forEach {
            it.setExecutable(true, false)
            it.setReadable(true, false)
        }
    }

    val dxvkDir = File(ContentsManager.getContentTypeDir(context, ContentProfile.ContentType.CONTENT_TYPE_DXVK), DXVK_VERSION)
    if (!File(dxvkDir, "profile.json").exists()) {
        onStatus("Extracting DXVK ARM64...")
        if (!extractBundledWcp(context, "dxvk-$DXVK_VERSION.wcp", dxvkDir)) Timber.e("Failed to extract DXVK")
    }

    ImageFsInstaller.ensureBionicLib(context, ImageFs.find(context).rootDir)
    ContentsManager(context).syncContents()
}

private fun installDriver(context: Context, driverName: String, assetZip: String, onStatus: (String) -> Unit) {
    val driverDir = File(context.filesDir, "contents/adrenotools/$driverName")
    if (File(driverDir, "meta.json").exists()) return
    onStatus("Installing $driverName driver...")
    driverDir.mkdirs()
    if (!FileUtils.extractZipFromAssets(context, "bundled_components/$assetZip", driverDir)) {
        Timber.e("Error extracting driver $driverName from $assetZip")
    }
}

private fun extractBundledWcp(context: Context, assetName: String, destination: File): Boolean {
    destination.mkdirs()
    return TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context, "bundled_components/$assetName", destination)
}
