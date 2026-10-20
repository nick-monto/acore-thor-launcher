package app.gamenative.ui.screen.wow

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.winlator.contents.AdrenotoolsManager
import com.winlator.core.envvars.EnvVars
import com.winlator.xenvironment.ImageFs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.`when`
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
class GraphicsDriverStoreTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        File(context.filesDir, "contents/adrenotools").deleteRecursively()
        context.getSharedPreferences("wow_graphics_driver", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun tearDown() {
        File(context.filesDir, "contents/adrenotools").deleteRecursively()
        context.getSharedPreferences("wow_graphics_driver", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun importInstallsAndSelectsAUserDriver() {
        val zip = zip(
            "meta.json" to """
                {"name":"User Turnip","driverVersion":"26.2.0-test","libraryName":"libvulkan_freedreno.so","minApi":29}
            """.trimIndent(),
            "libvulkan_freedreno.so" to "driver-bytes",
        )

        val driver = GraphicsDriverStore.import(context, Uri.fromFile(zip))

        assertEquals("custom-user-turnip", driver.id)
        assertEquals("User Turnip", driver.name)
        assertEquals("26.2.0-test", driver.version)
        assertTrue(File(context.filesDir, "contents/adrenotools/${driver.id}/libvulkan_freedreno.so").isFile)
        assertTrue(driver.id in AdrenotoolsManager(context).enumarateInstalledDrivers())

        GraphicsDriverStore.select(context, driver.id)
        assertEquals(driver.id, GraphicsDriverStore.resolve(context, BUNDLED_TURNIP_740))
        assertEquals(listOf(driver), GraphicsDriverStore.customDrivers(context))
    }

    @Test
    fun importsAnAdpkgStyleCoreDriver() {
        val zip = zip(
            "meta.json" to """
                {"name":"Qualcomm Adreno 805","driverVersion":"Vulkan 1.4.303","libraryName":"vulkan.adreno.so","minApi":27}
            """.trimIndent(),
            "vulkan.adreno.so" to "driver-bytes",
            "__MACOSX/._meta.json" to "junk",
        )

        val driver = GraphicsDriverStore.import(context, Uri.fromFile(zip))

        assertEquals("custom-qualcomm-adreno-805", driver.id)
        assertEquals("Vulkan 1.4.303", driver.version)
        assertTrue(File(context.filesDir, "contents/adrenotools/${driver.id}/vulkan.adreno.so").isFile)
        assertTrue(driver.id in AdrenotoolsManager(context).enumarateInstalledDrivers())

        val imageFs = mock<ImageFs>()
        `when`(imageFs.libDir).thenReturn(File(context.filesDir, "lib"))
        val env = EnvVars()
        AdrenotoolsManager(context).setDriverById(env, imageFs, driver.id)
        assertEquals("vulkan.adreno.so", env.get("ADRENOTOOLS_DRIVER_NAME"))
    }

    @Test
    fun setDriverByIdKeepsAnImportedDriver() {
        val zip = zip(
            "meta.json" to """
                {"name":"User Turnip","driverVersion":"26.2.0-test","libraryName":"libvulkan_freedreno.so","minApi":29}
            """.trimIndent(),
            "libvulkan_freedreno.so" to "driver-bytes",
        )
        val driver = GraphicsDriverStore.import(context, Uri.fromFile(zip))
        val imageFs = mock<ImageFs>()
        `when`(imageFs.libDir).thenReturn(File(context.filesDir, "lib"))
        val env = EnvVars()

        AdrenotoolsManager(context).setDriverById(env, imageFs, driver.id)

        assertEquals(
            File(context.filesDir, "contents/adrenotools/${driver.id}/").absolutePath + "/",
            env.get("ADRENOTOOLS_DRIVER_PATH"),
        )
        assertEquals("libvulkan_freedreno.so", env.get("ADRENOTOOLS_DRIVER_NAME"))
        assertTrue(File(context.filesDir, "contents/adrenotools/${driver.id}/meta.json").isFile)
    }

    @Test
    fun aMissingSelectionFallsBackToAutoAndIsForgotten() {
        GraphicsDriverStore.select(context, "custom-removed")

        assertEquals(BUNDLED_TURNIP_740, GraphicsDriverStore.resolve(context, BUNDLED_TURNIP_740))
        assertNull(GraphicsDriverStore.selected(context))
    }

    @Test
    fun minApiAboveThisDeviceIsRejected() {
        val zip = zip(
            "meta.json" to """
                {"name":"Too New","driverVersion":"1","libraryName":"libvulkan_freedreno.so","minApi":999}
            """.trimIndent(),
            "libvulkan_freedreno.so" to "driver-bytes",
        )

        val error = assertThrows(IllegalArgumentException::class.java) {
            GraphicsDriverStore.import(context, Uri.fromFile(zip))
        }
        assertTrue(error.message!!.contains("Android API 999"))
    }

    @Test
    fun anArchiveWithoutItsLibraryIsRejected() {
        val zip = zip(
            "meta.json" to """
                {"name":"Broken","driverVersion":"1","libraryName":"libvulkan_freedreno.so","minApi":29}
            """.trimIndent(),
        )

        assertThrows(IllegalArgumentException::class.java) {
            GraphicsDriverStore.import(context, Uri.fromFile(zip))
        }
    }

    @Test
    fun aPathEscapingTheDriverDirectoryIsRejected() {
        val zip = zip(
            "meta.json" to """
                {"name":"Traversal","driverVersion":"1","libraryName":"libvulkan_freedreno.so","minApi":29}
            """.trimIndent(),
            "libvulkan_freedreno.so" to "driver-bytes",
            "../escaped" to "nope",
        )

        assertThrows(IllegalArgumentException::class.java) {
            GraphicsDriverStore.import(context, Uri.fromFile(zip))
        }
        assertTrue(!File(context.filesDir, "contents/adrenotools/escaped").exists())
    }

    private fun zip(vararg entries: Pair<String, String>): File {
        val file = File.createTempFile("driver", ".zip")
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { out ->
            for ((name, content) in entries) {
                out.putNextEntry(ZipEntry(name))
                out.write(content.toByteArray())
                out.closeEntry()
            }
        }
        file.writeBytes(bytes.toByteArray())
        return file
    }
}
