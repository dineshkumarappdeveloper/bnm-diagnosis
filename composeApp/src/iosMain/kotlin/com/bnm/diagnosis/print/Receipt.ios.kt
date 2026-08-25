@file:OptIn(ExperimentalForeignApi::class)

package com.bnm.diagnosis.print

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGRectMake
import platform.UIKit.UIApplication
import platform.UIKit.UIFont
import platform.UIKit.UIPrintInfo
import platform.UIKit.UIPrintInfoOutputType
import platform.UIKit.UIPrintInteractionController
import platform.UIKit.UISimpleTextPrintFormatter
import platform.UIKit.UIView
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * iOS receipt printing via **AirPrint** (`UIPrintInteractionController`).
 *
 * Opens the system print sheet, which can send the receipt to any AirPrint
 * printer — and, for testing without hardware, the same sheet can **save as PDF**
 * (preview ▸ Share ▸ Save to Files) or print to a Mac with Printer Sharing on.
 *
 * THREADING: UIKit must run on the main thread. The shared call site wraps
 * printReceipt in `Dispatchers.Default` (correct for the desktop/Android I/O
 * paths), so on iOS we hop back to the main queue here — calling
 * `presentFromRect` off the main thread blocks forever and freezes the
 * "Printing…" spinner. We dispatch async and return immediately; the sheet
 * appears a moment later on the main thread.
 */
actual fun printReceipt(title: String, body: String): String {
    dispatch_async(dispatch_get_main_queue()) {
        try {
            val controller = UIPrintInteractionController.sharedPrintController
            val info = UIPrintInfo.printInfo()
            info.outputType = UIPrintInfoOutputType.UIPrintInfoOutputGeneral
            info.jobName = title
            controller.printInfo = info

            val formatter = UISimpleTextPrintFormatter(text = body)
            formatter.font = UIFont.fontWithName("Menlo", 10.0) ?: UIFont.systemFontOfSize(10.0)
            controller.printFormatter = formatter

            val rootView = topRootView()
            if (rootView != null) {
                // iPad needs a popover source rect; anchor it at the screen centre.
                // iPhone ignores the rect and presents the sheet modally.
                rootView.bounds.useContents {
                    controller.presentFromRect(
                        CGRectMake(size.width / 2.0, size.height / 2.0, 1.0, 1.0),
                        rootView,
                        true,
                        null,
                    )
                }
            } else {
                controller.presentAnimated(true, null)
            }
        } catch (e: Throwable) {
            println("AirPrint failed: ${e.message}")
        }
    }
    return "Opening AirPrint…"
}

/**
 * Resolve the active window's root view via the scene API. `keyWindow` is
 * deprecated since iOS 13 and can be null under scene-based lifecycles, which
 * would drop us onto the non-anchored `presentAnimated` path on iPad.
 */
private fun topRootView(): UIView? {
    val app = UIApplication.sharedApplication
    for (scene in app.connectedScenes) {
        val ws = scene as? UIWindowScene ?: continue
        for (w in ws.windows) {
            val view = (w as? UIWindow)?.rootViewController?.view
            if (view != null) return view
        }
    }
    @Suppress("DEPRECATION")
    return app.keyWindow?.rootViewController?.view
}
