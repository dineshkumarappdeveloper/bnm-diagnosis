package com.bnm.diagnosis.report

import platform.Foundation.NSURL
import platform.UIKit.UIApplication

actual fun openUrl(url: String): String {
    val nsUrl = NSURL.URLWithString(url) ?: return "That link is not valid"
    UIApplication.sharedApplication.openURL(nsUrl)
    return "Opened WhatsApp"
}
