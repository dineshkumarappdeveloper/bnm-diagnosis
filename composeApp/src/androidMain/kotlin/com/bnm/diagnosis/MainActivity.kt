package com.bnm.diagnosis

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
        com.bnm.diagnosis.db.initDbContext(this)
        com.bnm.diagnosis.connectivity.initConnectivityContext(this)
        com.bnm.diagnosis.print.initPrintContext(this)
        com.bnm.diagnosis.print.initBtPrinterContext(this)
        com.bnm.diagnosis.report.initReportContext(this) // A4 PDF reports (open/print)
        setContent { App() }
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    App()
}
