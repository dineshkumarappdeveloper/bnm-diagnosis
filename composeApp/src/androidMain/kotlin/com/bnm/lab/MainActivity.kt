package com.bnm.lab

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Offline DB + connectivity contexts (platform actuals need the Android context).
        com.bnm.lab.db.initDbContext(this)
        com.bnm.lab.connectivity.initConnectivityContext(this)
        com.bnm.lab.print.initPrintContext(this)
        com.bnm.lab.print.initA4Print(this) // A4 sheet billing needs the Activity
        com.bnm.lab.print.initBtPrinterContext(this)
        com.bnm.lab.report.initReportContext(this) // A4 PDF reports (open/print)
        setContent { App() }
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    App()
}
