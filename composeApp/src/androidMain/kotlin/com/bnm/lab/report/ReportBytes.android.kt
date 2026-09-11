package com.bnm.lab.report

import java.io.File

actual fun readReportBytes(path: String): ByteArray? =
    runCatching { File(path).takeIf { it.isFile }?.readBytes() }.getOrNull()
