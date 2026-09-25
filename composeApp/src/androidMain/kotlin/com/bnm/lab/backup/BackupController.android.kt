package com.bnm.lab.backup

/** No pendrive backup on Android — the desktop lab PC is the system of record. */
actual fun platformBackupController(): BackupController? = null
