package com.bnm.diagnosis.report

import java.io.File

actual fun readReportBytes(path: String): ByteArray? =
    runCatching { File(path).takeIf { it.isFile }?.readBytes() }.getOrNull()
