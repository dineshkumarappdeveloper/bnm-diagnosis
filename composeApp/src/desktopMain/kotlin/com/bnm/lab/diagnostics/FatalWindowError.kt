package com.bnm.lab.diagnostics

import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JOptionPane

/**
 * What happens when the main window's UI throws — in composition, layout, drawing
 * or input — including during the very first frame.
 *
 * WHY NOT THE IN-APP DIALOG: that dialog is part of App()'s composition. When the
 * failure is in App() itself (a corrupt or locked database at startup, say), that
 * composition never completes, so neither the crash prompt nor Help > Report a
 * problem can ever appear — and a lab whose app dies on every launch would have
 * no way to send the one report that explains it. Plain Swing does not depend on
 * any of that, so this offers the report right here, before closing.
 */
object FatalWindowError {

    private val handled = AtomicBoolean(false)

    fun handle(window: java.awt.Window, error: Throwable, exit: () -> Unit) {
        // A broken composition can throw again on every frame; answer once.
        if (!handled.compareAndSet(false, true)) return
        DesktopDiagnostics.recordCrash(Thread.currentThread(), error, fatal = true)
        runCatching {
            val choice = JOptionPane.showConfirmDialog(
                window,
                "BNM Lab ran into a problem and has to close.\n\n" +
                    "Create a support report for BNM now? It contains the app's activity log —\n" +
                    "no patient names, phone numbers or test results.",
                "BNM Lab — something went wrong",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.ERROR_MESSAGE,
            )
            if (choice == JOptionPane.YES_OPTION) {
                val result = runCatching {
                    runBlocking { SupportReporter.createAndEmail("The app closed with an error (${error::class.simpleName}).") }
                }
                val text = result.fold(
                    onSuccess = { "${it.message}\n\nSend it to: ${SupportReporter.supportEmail}" },
                    onFailure = {
                        "The report could not be created. The log files are in:\n${SupportReporter.logsLocation}\n\n" +
                            "Send them to: ${SupportReporter.supportEmail}"
                    },
                )
                JOptionPane.showMessageDialog(window, text, "BNM Lab — support report", JOptionPane.INFORMATION_MESSAGE)
                // Already offered and sent: don't ask again at the next launch.
                if (result.getOrNull()?.filePath != null) SupportReporter.acknowledgeCrash()
            }
        }.onFailure { AppLog.e("CRASH", "could not show the failure dialog", it) }
        runCatching { window.dispose() }
        exit()
    }
}
