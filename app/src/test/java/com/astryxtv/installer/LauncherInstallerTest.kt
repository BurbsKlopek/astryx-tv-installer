package com.astryxtv.installer.core

import com.astryxtv.installer.adb.AdbSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class LauncherInstallerTest {

    private class FakeAdbSession(
        var sdk: Int = 31,
        var home: String = "com.google.android.apps.tv.launcherx/.home.HomeActivity",
        var installOutput: String = "Success\n",
        var startOutput: String = "Starting: Intent {}\nStatus: ok\nComplete\n",
        var hijackHome: String? = null
    ) : AdbSession {
        override val host = "192.168.1.200"
        override val port = 5555

        val disabled = mutableSetOf<String>()
        val uninstalled = mutableSetOf<String>()
        val commands = mutableListOf<String>()
        var pushedCount = 0

        private val activities = mapOf(
            "com.google.android.apps.tv.launcherx" to "com.google.android.apps.tv.launcherx/.home.HomeActivity",
            "com.gathin.tvhome" to "com.gathin.tvhome/.MainActivity",
            LauncherInstaller.ASTRYX_PKG to LauncherInstaller.ASTRYX_ACTIVITY
        )

        override fun shell(command: String, timeoutMs: Int): String {
            commands.add(command)
            return when {
                command == "getprop ro.build.version.sdk" -> "$sdk\n"
                command.startsWith("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME ") -> {
                    val pkg = command.substringAfter("android.intent.category.HOME ").trim()
                    "priority=0\n${activities[pkg] ?: ""}\n"
                }
                command == "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME" ->
                    "priority=0\n$home\n"
                command.startsWith("pm install -r ") -> installOutput
                command.startsWith("am force-stop ") -> ""
                command.startsWith("am start -W ") -> startOutput
                command.startsWith("pm disable-user --user 0 ") -> {
                    val pkg = command.substringAfterLast(' ').trim()
                    disabled.add(pkg)
                    if (hijackHome != null) home = hijackHome!!
                    "Package $pkg new state: disabled-user\n"
                }
                command.startsWith("pm enable --user 0 ") -> {
                    val pkg = command.substringAfterLast(' ').trim()
                    disabled.remove(pkg)
                    "Package $pkg new state: enabled\n"
                }
                command.startsWith("cmd package set-home-activity --user 0 ") -> {
                    val comp = command.substringAfterLast(' ').trim()
                    home = if (hijackHome != null && comp.startsWith("${LauncherInstaller.ASTRYX_PKG}/")) {
                        hijackHome!!
                    } else {
                        comp
                    }
                    "Success\n"
                }
                command == "pm list packages -d --user 0" ->
                    disabled.joinToString("\n") { "package:$it" } + "\n"
                command == "pm list packages -e --user 0" ->
                    activities.keys.filter { it !in disabled }.joinToString("\n") { "package:$it" } + "\n"
                command.startsWith("pm uninstall --user 0 ") -> {
                    uninstalled.add(command.substringAfterLast(' ').trim())
                    "Success\n"
                }
                else -> "ok\n"
            }
        }

        override fun pushFile(
            localFile: File,
            remotePath: String,
            onProgress: (Long, Long, String) -> Unit
        ) {
            pushedCount++
            onProgress(localFile.length(), localFile.length(), "ADB Sync")
        }

        override fun close() = Unit
    }

    private fun tempApk(): File = File.createTempFile("astryx", ".apk").apply {
        deleteOnExit()
        writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x00, 0x00))
    }

    @Test
    fun freshGoogleTvInstallAndRestoreWithUninstall() {
        val session = FakeAdbSession()
        val outcome = LauncherInstaller.installLauncher(session, tempApk(), InstallOptions())

        assertEquals(LauncherInstaller.ASTRYX_ACTIVITY, outcome.homeComponent)
        assertEquals("com.google.android.apps.tv.launcherx", outcome.disabledLauncher)
        assertTrue("com.google.android.apps.tv.launcherx" in session.disabled)
        assertTrue(session.commands.any { it.contains("SYSTEM_ALERT_WINDOW allow") })
        assertTrue(session.commands.any { it.contains("APP_AUTO_START allow") && it.contains("appops write-settings") })

        val restore = LauncherInstaller.restoreStockLauncher(
            session = session,
            preferredPackage = outcome.disabledLauncher,
            uninstallAstryx = true
        )
        assertEquals("com.google.android.apps.tv.launcherx", restore.restoredPackage)
        assertTrue(restore.astryxUninstalled)
        assertFalse("com.google.android.apps.tv.launcherx" in session.disabled)
        assertTrue(LauncherInstaller.ASTRYX_PKG in session.uninstalled)
    }

    @Test
    fun cleanYourTvLauncherUpgradeDisablesGathinTvHome() {
        val session = FakeAdbSession(home = "com.gathin.tvhome/.MainActivity")
        session.disabled.add("com.google.android.apps.tv.launcherx")

        val outcome = LauncherInstaller.installLauncher(session, tempApk(), InstallOptions())
        assertEquals(LauncherInstaller.ASTRYX_ACTIVITY, outcome.homeComponent)
        assertEquals("com.gathin.tvhome", outcome.disabledLauncher)
        assertTrue("com.gathin.tvhome" in session.disabled)
    }

    @Test
    fun preflightMinSdkRejectsBeforeUpload() {
        val session = FakeAdbSession(sdk = 24)
        try {
            LauncherInstaller.installLauncher(session, tempApk(), InstallOptions())
            fail("Expected minSdk refusal")
        } catch (e: IllegalStateException) {
            assertTrue(e.message.orEmpty().contains("SDK 24"))
        }
        assertEquals(0, session.pushedCount)
        assertTrue(session.disabled.isEmpty())
    }

    @Test
    fun failedInstallOrStartNeverDisablesStockLauncher() {
        val failInstall = FakeAdbSession(installOutput = "Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE]\n")
        try {
            LauncherInstaller.installLauncher(failInstall, tempApk(), InstallOptions())
            fail("Expected install failure")
        } catch (e: IllegalStateException) {
            assertTrue(e.message.orEmpty().contains("signing key"))
        }
        assertTrue(failInstall.disabled.isEmpty())

        val failStart = FakeAdbSession(startOutput = "Error: Activity not started\n")
        try {
            LauncherInstaller.installLauncher(failStart, tempApk(), InstallOptions())
            fail("Expected start failure")
        } catch (e: IllegalStateException) {
            assertTrue(e.message.orEmpty().contains("clean start"))
        }
        assertTrue(failStart.disabled.isEmpty())
    }

    @Test
    fun setupwraithHijackTriggersAutomaticRollback() {
        val session = FakeAdbSession(
            hijackHome = "com.google.android.tungsten.setupwraith/.RecoveryActivity"
        )
        try {
            LauncherInstaller.installLauncher(session, tempApk(), InstallOptions())
            fail("Expected handover failure with rollback")
        } catch (e: IllegalStateException) {
            assertTrue(e.message.orEmpty().contains("owns Home again"))
            assertTrue(e.message.orEmpty().contains("setupwraith"))
        }
        assertFalse("Stock launcher must be re-enabled on rollback", "com.google.android.apps.tv.launcherx" in session.disabled)
        assertEquals("com.google.android.apps.tv.launcherx/.home.HomeActivity", session.home)
    }
}
