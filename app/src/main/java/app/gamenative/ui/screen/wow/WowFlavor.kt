package app.gamenative.ui.screen.wow

import android.content.Context
import java.io.File

/** A Battle.net region. [code] is used as the game's `portal` and as the patch server prefix. */
enum class WowRegion(val code: String) {
    US("us"),
    EU("eu"),
    KR("kr"),
    TW("tw"),
    ;

    val next get() = entries[(ordinal + 1) % entries.size]

    companion object {
        fun fromCode(code: String?) = entries.firstOrNull { it.name == code } ?: US
    }
}

/**
 * The WoW products this launcher can boot. Each one lives in its own folder under the
 * shared install root (next to `Data/` and `.build.info`), exactly like a Battle.net install.
 */
enum class WowFlavor(
    val label: String,
    val subtitle: String,
    val product: String,
    val dir: String,
    private val exeNames: List<String>,
    val portal: String,
    val defaultFolder: String,
) {
    FOREVER(
        label = "Forever",
        subtitle = "FOREVER BETA (ARM64 NATIVE)",
        product = "wow_classic_beta",
        dir = "_classic_beta_",
        exeNames = listOf("WowB-ARM64.exe"),
        portal = "test",
        defaultFolder = "WoW Forever",
    ),
    RETAIL(
        label = "Retail",
        subtitle = "RETAIL (ARM64 NATIVE)",
        product = "wow",
        dir = "_retail_",
        exeNames = listOf("Wow-ARM64.exe", "WowArm64.exe", "Wow-arm64.exe"),
        portal = "us",
        defaultFolder = "World of Warcraft",
    ),
    CLASSIC_ERA(
        label = "Classic Era",
        subtitle = "CLASSIC ERA (ARM64 NATIVE)",
        product = "wow_classic_era",
        dir = "_classic_era_",
        // Names are a best guess modelled on Retail's. exeFile() falls back to any ARM64 .exe in the
        // folder, so a different real name from the CDN download is still picked up.
        exeNames = listOf("WowClassic-ARM64.exe", "WowClassicArm64.exe", "WowClassic-arm64.exe"),
        portal = "us",
        defaultFolder = "World of Warcraft",
    ),
    CLASSIC_ANNIVERSARY(
        label = "TBC Anniversary",
        subtitle = "BURNING CRUSADE ANNIVERSARY (ARM64 NATIVE)",
        product = "wow_anniversary",
        dir = "_anniversary_",
        // Best-guess names; exeFile() falls back to any ARM64 .exe in the folder.
        exeNames = listOf("WowClassic-ARM64.exe", "WowClassicArm64.exe", "WowClassic-arm64.exe"),
        portal = "us",
        defaultFolder = "World of Warcraft",
    ),
    CLASSIC_MOP(
        label = "MoP Classic",
        subtitle = "MISTS OF PANDARIA CLASSIC (ARM64 NATIVE)",
        product = "wow_classic",
        dir = "_classic_",
        exeNames = listOf("WowClassic-ARM64.exe", "WowClassicArm64.exe", "WowClassic-arm64.exe"),
        portal = "us",
        defaultFolder = "World of Warcraft",
    ),
    LOCAL_AC(
        label = "AzerothCore",
        subtitle = "SELF-HOSTED 3.3.5A (32-BIT)",
        product = "wow_ac_335",
        // 3.3.5a keeps Wow.exe, Data/ and WTF/ together at the client root, so "." is that root.
        dir = ".",
        exeNames = listOf("Wow.exe"),
        portal = "us", // unused: RealmList comes from realmlist.wtf, not Config.wtf
        defaultFolder = "AzerothCore 3.3.5a",
    ),
    ;

    /** True for the self-hosted AzerothCore flavor: no Battle.net, no CDN, no `.build.info`, no region. */
    val isLocal get() = this == LOCAL_AC

    /** Each flavor boots in its own container, so the two very different Wine profiles never mix. */
    val containerId get() = if (isLocal) "ac_thor_335" else "wow_forever"

    /** True for the three Classic products, which share the Classic button and its second row. */
    val isClassic get() = this == CLASSIC_ERA || this == CLASSIC_ANNIVERSARY || this == CLASSIC_MOP

    /** Forever runs on Blizzard's test servers and a local realm has one address, so neither picks a region. */
    val hasRegion get() = this != FOREVER && !isLocal

    /** The `portal` for Config.wtf: the chosen region, or the fixed test portal for Forever. */
    val activePortal get() = if (hasRegion) WowFlavor.region.code else portal

    val exeName get() = exeNames.first()

    /** Short name for the launch screens: Forever, Retail or one of the Classic products. */
    val shortName get() = when (this) {
        FOREVER -> "Forever"
        RETAIL -> "Retail"
        CLASSIC_ERA -> "Classic Era"
        CLASSIC_ANNIVERSARY -> "TBC Anniversary"
        CLASSIC_MOP -> "MoP Classic"
        LOCAL_AC -> "AzerothCore"
    }

    /** Full name shown while the game boots. */
    val gameName get() = if (isLocal) "AzerothCore 3.3.5a" else "World of Warcraft $shortName"

    /**
     * The folder holding this flavor's client under the install root. Blizzard's flavors live in their
     * `_product_` subfolder; a 3.3.5a client keeps `Wow.exe`, `Data/` and `WTF/` at the root itself.
     */
    fun clientDir(root: File): File = if (isLocal) root else File(root, dir)

    /**
     * The client in this flavor's folder. Blizzard's exe names differ per product, so after the known
     * names this falls back to any ARM64 .exe the CDN download put there - or, for the local flavor,
     * to any `Wow*.exe`, since its client is a 32-bit `Wow.exe`.
     */
    fun exeFile(root: File): File {
        val flavorDir = clientDir(root)
        exeNames.map { File(flavorDir, it) }.firstOrNull { it.isFile }?.let { return it }
        // The local flavor's client is 32-bit and named Wow.exe; Blizzard's flavors ship *-ARM64.exe.
        val wanted = { name: String ->
            if (isLocal) name.startsWith("wow", ignoreCase = true) else name.contains("arm64", ignoreCase = true)
        }
        flavorDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".exe", ignoreCase = true) && wanted(it.name) }
            ?.firstOrNull { exe -> listOf("launcher", "crash", "error").none { exe.name.contains(it, ignoreCase = true) } }
            ?.let { return it }
        return File(flavorDir, exeName)
    }

    companion object {
        private const val PREFS = "wow_forever"
        private const val KEY_FLAVOR = "flavor"

        /** The flavor the launcher is currently set up for. Read by the downloader and launch code. */
        @Volatile
        var current: WowFlavor = FOREVER
            private set

        /** The region chosen in the launcher. One setting for every game that has a region; US until changed. */
        @Volatile
        var region: WowRegion = WowRegion.US
            private set

        private var loaded = false

        private const val KEY_REGION = "region"

        fun setRegion(context: Context, value: WowRegion) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_REGION, value.name).apply()
            region = value
        }

        fun load(context: Context): WowFlavor {
            if (!loaded) {
                val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_FLAVOR, null)
                current = entries.firstOrNull { it.name == saved } ?: FOREVER
                region = WowRegion.fromCode(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_REGION, null))
                loaded = true
            }
            return current
        }

        fun select(context: Context, flavor: WowFlavor) {
            current = flavor
            loaded = true
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_FLAVOR, flavor.name).apply()
        }
    }
}
