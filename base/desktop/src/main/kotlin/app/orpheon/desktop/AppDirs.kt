// Part of Orpheon (https://github.com/kota-zo/Orpheon), GPL-3.0.
package app.orpheon.desktop

import java.io.File

/** Where Orpheon keeps its files: %APPDATA%\Orpheon on Windows, $XDG_DATA_HOME/Orpheon (else ~/.local/share/Orpheon) on Linux. */
object AppDirs {
    val data: File by lazy {
        val home = System.getProperty("user.home")
        val base = if (System.getProperty("os.name").startsWith("Windows")) {
            System.getenv("APPDATA")?.takeIf { it.isNotBlank() }?.let(::File) ?: File(home)
        } else {
            System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }?.let(::File) ?: File(home, ".local/share")
        }
        File(base, "Orpheon").also { it.mkdirs() }
    }
}
