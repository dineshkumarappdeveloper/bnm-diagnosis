package com.bnm.lab.report

/** iOS renders no report PDF yet, so there is nothing to file. */
actual fun defaultReportsDir(): String = ""

actual fun archiveReportFile(sourcePath: String, dir: String, relativePath: String): String = ""

actual fun revealInFileManager(path: String): String = "Filing reports arrives on iOS later"

actual fun pickFolder(title: String): String? = null
