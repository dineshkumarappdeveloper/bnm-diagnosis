package com.bnm.lab.backup

/** The desktop engine — one per process, started by `main()`. */
actual fun platformBackupController(): BackupController? = BackupService.shared
