package com.bnm.lab.remote

import com.bnm.lab.instruments.MispaCountX
import kotlinx.coroutines.delay
import java.net.ServerSocket
import kotlin.test.fail

/** Shared bits for the remote-support tests: frames, ports, polling. */
internal object RemoteTestFixtures {

    /** A BC-5130 ORU with the CBC parameters and a full PID — the shape the scrubber must clean. */
    fun oru(
        specimen: String,
        processingId: String = "P",
        patientName: String = "Patient^Mindray",
        controlId: String = "20260908101500001",
    ): String = listOf(
        "MSH|^~\\&|BC-5130|Mindray|||20260908101500||ORU^R01|$controlId|$processingId|2.3.1||||||UNICODE",
        "PID|1||E2E-001^^^^MR||$patientName||19840101000000|Female|||12 Gandhi St^^Chennai||9876543210||||||123-45-6789",
        "NK1|1|Kin^Next|SPO",
        "PV1|1||OPD",
        "OBR|1||$specimen|00001^Automated Count^99MRC||20260908101000|20260908101400|||Tester|||||||||||||HM||||||||",
        "OBX|1|IS|08003^Test Mode^99MRC||CBC+5DIFF||||||F",
        "OBX|2|NM|6690-2^WBC^LN||9.55|10*9/L|4.00-10.00|N|||F",
        "OBX|3|NM|789-8^RBC^LN||4.51|10*12/L|3.50-5.50|N|||F",
        "OBX|4|NM|718-7^HGB^LN||135|g/L|110-150|N|||F",
        "OBX|5|NM|4544-3^HCT^LN||0.412|L/L|0.370-0.540|N|||F",
        "OBX|6|NM|787-2^MCV^LN||88.0|fL|80.0-100.0|N|||F",
        "OBX|7|NM|785-6^MCH^LN||30.0|pg|27.0-34.0|N|||F",
        "OBX|8|NM|786-4^MCHC^LN||340|g/L|320-360|N|||F",
        "OBX|9|NM|777-3^PLT^LN||381|10*9/L|100-300|H|||F",
        "OBX|10|NM|736-9^LYM%^LN||22.0|%|20.0-40.0|N|||F",
        "OBX|11|NM|770-8^NEU%^LN||62.0|%|50.0-70.0|N|||F",
        "OBX|12|NM|5905-5^MON%^LN||6.0|%|3.0-12.0|N|||F",
        "OBX|13|NM|713-8^EOS%^LN||3.0|%|0.5-5.0|N|||F",
        "OBX|14|NM|706-2^BAS%^LN||1.0|%|0.0-1.0|N|||F",
        "OBX|15|ST|01001^Remark^99MRC||Patient fainted during draw||||||F",
    ).joinToString("\r") + "\r"

    /** A Mispa Count X frame: the 20 header params, no histograms. */
    fun mispa(specimen: String, patient: String = "PAT456"): String {
        val values = listOf("9.5", "4.5", "250", "13.5", "40", "88", "30", "34", "40", "13", "9",
            "30", "5", "65", "2", "0.5", "6.5", "0.2", "12", "25")
        check(values.size == MispaCountX.PARAM_ORDER.size)
        return "$$$" + listOf("20260908", "12", specimen, patient).joinToString("$") + "$" + values.joinToString("$") + "###"
    }

    fun freePort(): Int = ServerSocket(0).use { it.localPort }

    suspend fun waitFor(what: String, timeoutMs: Long = 8_000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return
            delay(25)
        }
        if (!cond()) fail("timed out waiting for: $what")
    }
}
