package com.bnm.lab.report

/**
 * No logo picker on this target yet. The Settings row is hidden rather than
 * shown-and-dead: labs set their letterhead on the lab PC, which is where the
 * reports are printed, and a button that silently does nothing is worse than
 * no button.
 */
actual suspend fun pickLetterheadLogoPng(): ByteArray? = null

actual fun letterheadLogoPickerSupported(): Boolean = false
