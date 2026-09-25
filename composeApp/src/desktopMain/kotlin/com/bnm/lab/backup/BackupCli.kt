package com.bnm.lab.backup

import java.io.File
import java.util.Base64

/**
 * The headless escape hatch, for support rescuing a lab whose GUI is broken:
 *
 *     BNMLab-portable.jar --export-backup <file.bnmlab> <out.db> [--key BNMD-… | --code XXXXX-…]
 *
 * Writes the plain SQLite file and `prefs.json` next to it, prints the counts
 * (never a record), exits 0 on success and 1 on any failure. With neither
 * secret it tries this PC's own vault key. Handled at the very top of
 * `main()`, before diagnostics, the window or the engine exist.
 */
internal object BackupCli {
    const val FLAG = "--export-backup"

    /** The exit code when [args] asked for a headless job; null means "start the app". */
    fun run(args: Array<String>, prefs: BackupPrefs = BackupPrefs(), out: (String) -> Unit = ::println): Int? {
        val at = args.indexOf(FLAG)
        if (at < 0) return null
        val rest = args.drop(at + 1)
        val positional = ArrayList<String>()
        var key: String? = null
        var code: String? = null
        var i = 0
        while (i < rest.size) {
            when (rest[i]) {
                "--key" -> key = rest.getOrNull(++i)
                "--code" -> code = rest.getOrNull(++i)
                else -> positional += rest[i]
            }
            i++
        }
        if (positional.size != 2) {
            out("usage: $FLAG <file.bnmlab> <out.db> [--key BNMD-XXXX-XXXX-XXXX-XXXX | --code XXXXX-XXXXX-XXXXX-XXXXX-XXXXX]")
            return 1
        }
        return try {
            export(File(positional[0]), File(positional[1]), key, code, prefs, out)
            0
        } catch (e: Exception) {
            out("export failed: ${e.message ?: e::class.simpleName}")
            1
        }
    }

    fun export(source: File, target: File, key: String?, code: String?, prefs: BackupPrefs, out: (String) -> Unit) {
        if (!source.isFile) error("no such file: ${source.path}")
        val (header, _) = BackupContainer.readHeader(source)
        out("backup of ${header.lab ?: "(unnamed lab)"} · generation ${header.seq} · made ${header.created} · BNM Lab ${header.appVersion}")
        val unlock: Unlock = when {
            key != null -> Unlock.LicenceKey(key)
            code != null -> Unlock.RecoveryCode(code)
            else -> Unlock.ThisPc
        }
        val thisPcDek = prefs.dek?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
        val dek = BackupContainer.unlock(header, unlock, thisPcDek)
            ?: error(if (unlock is Unlock.ThisPc) "this computer holds no key for that backup — pass --key or --code" else "that secret does not open this backup")
        target.parentFile?.mkdirs()
        val (_, stream) = BackupContainer.open(source, dek)
        val head = stream.use { BackupBundle.extract(it, target) }
        val prefsFile = File(target.parentFile ?: File("."), "prefs.json")
        prefsFile.writeText(BackupBundle.encodePrefs(head.prefs))
        val verdict = runCatching { BackupSnapshot.quickCheck(target) }.getOrElse { "could not be checked: ${it::class.simpleName}" }
        val c = head.manifest.counts
        out("database written: ${target.absolutePath} (${target.length() / 1024} KB, quick_check $verdict)")
        out("preferences written: ${prefsFile.absolutePath} (${head.prefs.size} keys)")
        out("counts: patients=${c.patients} orders=${c.orders} results=${c.results} staff=${c.staff} tests=${c.tests}")
    }
}
