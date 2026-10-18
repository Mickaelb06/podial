package app.podial

import java.io.File

/** Lancement à l'ouverture de session : clé « Run » sous Windows, LaunchAgent sous macOS, fichier autostart sous Linux. */
object Autostart {
    private val os = System.getProperty("os.name").lowercase()
    private val home = System.getProperty("user.home")

    /** Chemin du programme installé (fourni par jpackage). Absent quand on lance depuis les sources. */
    private val exe: String? get() = System.getProperty("jpackage.app-path")

    fun available() = exe != null

    private val macFile get() = File(home, "Library/LaunchAgents/app.podial.plist")
    private val linuxFile get() = File(System.getenv("XDG_CONFIG_HOME") ?: "$home/.config", "autostart/podial.desktop")
    private const val RUN_KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run"

    private fun run(vararg cmd: String): Int =
        try { ProcessBuilder(*cmd).redirectErrorStream(true).start().also { it.inputStream.readBytes() }.waitFor() } catch (e: Exception) { -1 }

    fun isEnabled(): Boolean = when {
        "win" in os -> run("reg", "query", RUN_KEY, "/v", "Podial") == 0
        "mac" in os -> macFile.exists()
        else -> linuxFile.exists()
    }

    fun set(enabled: Boolean) {
        val path = exe ?: return
        when {
            "win" in os ->
                if (enabled) run("reg", "add", RUN_KEY, "/v", "Podial", "/t", "REG_SZ", "/d", "\"$path\" --minimized", "/f")
                else run("reg", "delete", RUN_KEY, "/v", "Podial", "/f")
            "mac" in os ->
                if (enabled) {
                    macFile.parentFile.mkdirs()
                    macFile.writeText("""<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
<key>Label</key><string>app.podial</string>
<key>ProgramArguments</key><array><string>$path</string><string>--minimized</string></array>
<key>RunAtLoad</key><true/>
</dict></plist>
""")
                } else macFile.delete()
            else ->
                if (enabled) {
                    linuxFile.parentFile.mkdirs()
                    linuxFile.writeText("[Desktop Entry]\nType=Application\nName=Podial Player\nExec=\"$path\" --minimized\nTerminal=false\n")
                } else linuxFile.delete()
        }
    }
}
