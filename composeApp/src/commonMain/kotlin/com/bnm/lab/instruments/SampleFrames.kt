package com.bnm.lab.instruments

/**
 * One bundled, PHI-free result frame per driver for the Link check's "Test
 * connection": pushed through [InstrumentEngine.dryRun] it proves the driver
 * parses and shows which keys the lab's catalog maps — without an analyzer
 * on the bench. The specimen id is `BNMTEST-1`, which dryRun never matches to
 * an order; there is no patient name or id in either frame.
 */
object SampleFrames {
    const val SPECIMEN = "BNMTEST-1"

    fun forDriver(driver: String): String? = when (driver) {
        "mispa_count_x" -> mispa()
        "mindray_hl7" -> mindrayOru()
        else -> null
    }

    /** `$$$Date$Seq$Specimen$Patient$<20 params>###` — the header section only, no histograms. */
    fun mispa(): String {
        val values = listOf(
            "7.2", "4.6", "245", "13.8", "41.5", "90.2", "30.0", "33.3",
            "42.1", "13.1", "9.4", "32.0", "6.0", "62.0",
            "2.3", "0.4", "4.5", "0.23", "12.5", "24.0",
        )
        check(values.size == MispaCountX.PARAM_ORDER.size)
        return "$$$" + listOf("20260101", "1", SPECIMEN, "").joinToString("$") + "$" + values.joinToString("$") + "###"
    }

    /** A BC-5130 ORU^R01 with the CBC + 5-part differential and an EMPTY PID. Segments end in CR, no MLLP wrap. */
    fun mindrayOru(): String = listOf(
        "MSH|^~\\&|BC-5130|Mindray|||20260101090000||ORU^R01|BNMTEST0001|P|2.3.1||||||UNICODE",
        "PID|1||||||||",
        "OBR|1||$SPECIMEN|00001^Automated Count^99MRC||20260101085500|20260101085900|||||||||||||||||HM||||||||",
        "OBX|1|IS|08003^Test Mode^99MRC||CBC+5DIFF||||||F",
        "OBX|2|NM|6690-2^WBC^LN||7.20|10*9/L|4.00-10.00|N|||F",
        "OBX|3|NM|789-8^RBC^LN||4.60|10*12/L|3.50-5.50|N|||F",
        "OBX|4|NM|718-7^HGB^LN||138|g/L|110-150|N|||F",
        "OBX|5|NM|4544-3^HCT^LN||0.415|L/L|0.370-0.540|N|||F",
        "OBX|6|NM|787-2^MCV^LN||90.2|fL|80.0-100.0|N|||F",
        "OBX|7|NM|785-6^MCH^LN||30.0|pg|27.0-34.0|N|||F",
        "OBX|8|NM|786-4^MCHC^LN||333|g/L|320-360|N|||F",
        "OBX|9|NM|777-3^PLT^LN||245|10*9/L|100-300|N|||F",
        "OBX|10|NM|736-9^LYM%^LN||32.0|%|20.0-40.0|N|||F",
        "OBX|11|NM|770-8^NEU%^LN||62.0|%|50.0-70.0|N|||F",
        "OBX|12|NM|5905-5^MON%^LN||6.0|%|3.0-12.0|N|||F",
        "OBX|13|NM|713-8^EOS%^LN||2.3|%|0.5-5.0|N|||F",
        "OBX|14|NM|706-2^BAS%^LN||0.4|%|0.0-1.0|N|||F",
        "OBX|15|NM|731-0^LYM#^LN||2.30|10*9/L|0.80-4.00|N|||F",
        "OBX|16|NM|751-8^NEU#^LN||4.46|10*9/L|2.00-7.00|N|||F",
        "OBX|17|NM|742-7^MON#^LN||0.43|10*9/L|0.12-1.20|N|||F",
        "OBX|18|NM|711-2^EOS#^LN||0.17|10*9/L|0.02-0.50|N|||F",
        "OBX|19|NM|704-7^BAS#^LN||0.03|10*9/L|0.00-0.10|N|||F",
    ).joinToString("\r") + "\r"
}
