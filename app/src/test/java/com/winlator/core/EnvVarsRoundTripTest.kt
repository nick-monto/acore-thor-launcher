package com.winlator.core

import com.winlator.core.envvars.EnvVars
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The container stores envVars as one space-separated string that the same parser reads back, and a
 * backslash in it is an escape character. A raw `G:\` followed by a space therefore swallows the
 * next variable: on the Thor that turned SOTSK_ROOT into "G: SOTSK_DATA=G:clientData" and the native
 * side resolved every path from it. Only the serialized form may be stored.
 */
class EnvVarsRoundTripTest {
    @Test
    fun `windows paths survive the container format`() {
        val built = EnvVars().apply {
            put("SOTSK_ROOT", "G:\\")
            put("SOTSK_DATA", "G:\\client\\Data")
            put("DXVK_LOG_PATH", "G:\\native-probe\\build")
            put("WINEDLLOVERRIDES", "dinput8=n,b")
        }.toEscapedString()

        val parsed = EnvVars(built)

        assertEquals("G:\\", parsed.get("SOTSK_ROOT"))
        assertEquals("G:\\client\\Data", parsed.get("SOTSK_DATA"))
        assertEquals("G:\\native-probe\\build", parsed.get("DXVK_LOG_PATH"))
        assertEquals("dinput8=n,b", parsed.get("WINEDLLOVERRIDES"))
    }

    @Test
    fun `an unescaped trailing backslash folds the next variable`() {
        // The exact failure seen on the device, kept as the contract this format demands.
        val parsed = EnvVars("SOTSK_ROOT=G:\\ SOTSK_DATA=G:\\client\\Data")

        assertEquals("G: SOTSK_DATA=G:clientData", parsed.get("SOTSK_ROOT"))
    }
}
