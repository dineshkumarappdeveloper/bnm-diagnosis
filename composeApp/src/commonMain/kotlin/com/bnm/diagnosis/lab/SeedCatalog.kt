package com.bnm.diagnosis.lab

/**
 * Starter catalog: ~40 standard Indian diagnostic tests with real parameters,
 * units and reference ranges — sex-split where medically standard (Hb, PCV,
 * RBC, uric acid, creatinine, SGOT/SGPT, HDL, ESR) and AGE-BANDED for the six
 * parameters whose paediatric values genuinely differ (see [HB_RANGES] and
 * friends) — plus the five bread-and-butter panels. Seeded once on first run
 * (count==0 guard) so a lab can bill and report on day one; everything remains
 * editable in the catalog's range editor.
 *
 * Ranges are typical intervals from standard Indian lab practice —
 * labs should adjust to their own method/analyzer inserts.
 */
object SeedCatalog {

    /** Idempotent: seeds only when the catalog is completely empty. */
    suspend fun seedIfEmpty(repo: LabRepository) {
        if (repo.countTests() > 0L) return
        tests().forEachIndexed { i, t -> repo.upsertTest(t.copy(sortOrder = i)) }
        panels().forEach { repo.upsertPanel(it) }
    }

    // ── builders ──
    private fun testId(code: String) = "seed-" + code.lowercase().replace(" ", "-")

    private fun test(
        code: String, name: String, category: String, price: Double,
        sample: String = "blood", method: String? = null, vararg params: TestParameter,
    ) = LabTest(
        id = testId(code), code = code, name = name, category = category, price = price,
        sampleType = sample, method = method, parameters = params.toList(),
    )

    private fun p(key: String, name: String, unit: String?, decimals: Int, vararg ranges: RefRange) =
        TestParameter(key, name, unit, decimals, ranges.toList())

    /** Same parameter, ranges shared from one of the age-banded lists below —
     *  the same analyte appears in several tests (hb in CBC and HB, creat in
     *  CREAT and KFT…) and the bands must not drift between them. */
    private fun p(key: String, name: String, unit: String?, decimals: Int, ranges: List<RefRange>) =
        TestParameter(key, name, unit, decimals, ranges)

    private fun rr(
        low: Double? = null, high: Double? = null, sex: String? = null,
        criticalLow: Double? = null, criticalHigh: Double? = null,
        ageMinY: Double? = null, ageMaxY: Double? = null,
    ) = RefRange(sex, ageMinY, ageMaxY, low, high, criticalLow, criticalHigh, null)

    private fun qual(expected: String) = RefRange(text = expected)

    private const val HEMA = "Hematology"
    private const val BIO = "Biochemistry"
    private const val ENDO = "Endocrinology"
    private const val SERO = "Serology"
    private const val PATH = "Clinical Pathology"

    // ── Age-banded (paediatric) reference ranges ─────────────────────────────
    //
    // Source convention: the paediatric intervals conventionally quoted by Indian
    // labs — Dacie & Lewis (haematology) and the Nelson/Tietz paediatric tables
    // (chemistry). Analyser- and method-dependent, so a lab is expected to tune
    // them in the catalog's range editor; these are a safe, widely published
    // starting point, not a claim about any one analyser.
    //
    // Three rules make these lists work with LabRepository.pickRange:
    //
    //  1. BANDS ARE CONTIGUOUS. One band's ageMaxY is the next band's ageMinY.
    //     Bounds are inclusive at both ends, so the shared endpoint matches two
    //     bands — pickRange breaks that tie on band width, deterministically. A
    //     GAP would be the real bug: the patient would match NO range, and an
    //     unranged value is never flagged at all.
    //  2. THE ADULT RANGE STAYS AGE-OPEN. It is the fallback for a patient whose
    //     age is unknown (no DOB, no age entered), and pickRange ranks any age
    //     band above an open range, so a child is never judged against it.
    //  3. Bands split by sex only where the sexes genuinely diverge before 18
    //     (Hb and ALP from puberty). Elsewhere one sex-neutral band per age is
    //     both truer and safer — a sex-split band excludes patients recorded
    //     as 'O', who match sex-neutral ranges only.

    /** 4 weeks, in years — the neonatal cut-off shared by several bands. */
    private const val NEWBORN = 0.077

    /** Haemoglobin g/dL. The 1–6 month trough is physiological (nadir ~2 months),
     *  not anaemia; flagging a well infant against adult limits is the classic
     *  paediatric false positive this whole feature exists to stop. */
    private val HB_RANGES = listOf(
        rr(14.0, 22.0, ageMinY = 0.0, ageMaxY = NEWBORN, criticalLow = 10.0, criticalHigh = 24.0),
        rr(10.0, 14.0, ageMinY = NEWBORN, ageMaxY = 0.5, criticalLow = 7.0, criticalHigh = 20.0),
        rr(10.5, 13.5, ageMinY = 0.5, ageMaxY = 2.0, criticalLow = 7.0, criticalHigh = 20.0),
        rr(11.5, 13.5, ageMinY = 2.0, ageMaxY = 6.0, criticalLow = 7.0, criticalHigh = 20.0),
        rr(11.5, 15.5, ageMinY = 6.0, ageMaxY = 12.0, criticalLow = 7.0, criticalHigh = 20.0),
        rr(13.0, 16.0, sex = "M", ageMinY = 12.0, ageMaxY = 18.0, criticalLow = 7.0, criticalHigh = 20.0),
        rr(12.0, 16.0, sex = "F", ageMinY = 12.0, ageMaxY = 18.0, criticalLow = 7.0, criticalHigh = 20.0),
        rr(13.0, 17.0, sex = "M", criticalLow = 7.0, criticalHigh = 20.0),
        rr(12.0, 15.0, sex = "F", criticalLow = 7.0, criticalHigh = 20.0),
    )

    /** Total WBC /cumm. Children are physiologically leucocytosed by adult
     *  standards; the newborn ceiling is triple the adult one. */
    private val WBC_RANGES = listOf(
        rr(9000.0, 30000.0, ageMinY = 0.0, ageMaxY = NEWBORN, criticalLow = 2000.0, criticalHigh = 50000.0),
        rr(6000.0, 17500.0, ageMinY = NEWBORN, ageMaxY = 2.0, criticalLow = 1500.0, criticalHigh = 50000.0),
        rr(5000.0, 15500.0, ageMinY = 2.0, ageMaxY = 6.0, criticalLow = 1000.0, criticalHigh = 50000.0),
        rr(4500.0, 13500.0, ageMinY = 6.0, ageMaxY = 12.0, criticalLow = 1000.0, criticalHigh = 50000.0),
        rr(4500.0, 13000.0, ageMinY = 12.0, ageMaxY = 18.0, criticalLow = 1000.0, criticalHigh = 50000.0),
        rr(4000.0, 11000.0, criticalLow = 1000.0, criticalHigh = 50000.0),
    )

    /** Platelets /cumm. Only the upper limit moves with age; the critical
     *  thresholds (bleeding risk) are physiology, not age, so they don't. */
    private val PLT_RANGES = listOf(
        rr(150000.0, 550000.0, ageMinY = 0.0, ageMaxY = 1.0, criticalLow = 20000.0, criticalHigh = 1000000.0),
        rr(150000.0, 450000.0, ageMinY = 1.0, ageMaxY = 18.0, criticalLow = 20000.0, criticalHigh = 1000000.0),
        rr(150000.0, 410000.0, criticalLow = 20000.0, criticalHigh = 1000000.0),
    )

    /** ESR mm/hr (Westergren). Adolescents fall through to the adult sex-split
     *  ranges, which is standard — the sexes diverge from puberty, not before. */
    private val ESR_RANGES = listOf(
        rr(0.0, 2.0, ageMinY = 0.0, ageMaxY = NEWBORN),
        rr(0.0, 10.0, ageMinY = NEWBORN, ageMaxY = 12.0),
        rr(0.0, 15.0, sex = "M"),
        rr(0.0, 20.0, sex = "F"),
    )

    /** Creatinine mg/dL. Tracks muscle mass, so a child's UPPER limit is well
     *  below an adult's — an adult critical high of 7.4 on a toddler would let
     *  frank renal failure print as a plain high. */
    private val CREAT_RANGES = listOf(
        rr(0.3, 1.0, ageMinY = 0.0, ageMaxY = NEWBORN, criticalHigh = 3.0),
        rr(0.2, 0.5, ageMinY = NEWBORN, ageMaxY = 3.0, criticalHigh = 2.0),
        rr(0.3, 0.7, ageMinY = 3.0, ageMaxY = 12.0, criticalHigh = 3.0),
        rr(0.5, 1.0, ageMinY = 12.0, ageMaxY = 18.0, criticalHigh = 5.0),
        rr(0.7, 1.3, sex = "M", criticalHigh = 7.4),
        rr(0.6, 1.1, sex = "F", criticalHigh = 7.4),
    )

    /** Alkaline phosphatase U/L. Bone-growth driven: a healthy 12-year-old runs
     *  three times the adult ceiling, and the pubertal spurt (and so the fall
     *  back to adult values) happens earlier in girls — hence the sex split. */
    private val ALP_RANGES = listOf(
        rr(150.0, 450.0, ageMinY = 0.0, ageMaxY = 1.0),
        rr(150.0, 420.0, ageMinY = 1.0, ageMaxY = 10.0),
        rr(130.0, 525.0, sex = "M", ageMinY = 10.0, ageMaxY = 15.0),
        rr(70.0, 330.0, sex = "F", ageMinY = 10.0, ageMaxY = 15.0),
        rr(50.0, 375.0, sex = "M", ageMinY = 15.0, ageMaxY = 18.0),
        rr(45.0, 145.0, sex = "F", ageMinY = 15.0, ageMaxY = 18.0),
        rr(44.0, 147.0),
    )

    private fun tests(): List<LabTest> = listOf(
        // ── Hematology ──
        test("CBC", "Complete Blood Count", HEMA, 350.0, "blood", "Automated cell counter",
            p("hb", "Haemoglobin", "g/dL", 1, HB_RANGES),
            p("rbc", "RBC Count", "mill/cumm", 2, rr(4.5, 5.5, sex = "M"), rr(3.8, 4.8, sex = "F")),
            p("wbc", "Total WBC Count", "/cumm", 0, WBC_RANGES),
            p("plt", "Platelet Count", "/cumm", 0, PLT_RANGES),
            p("pcv", "PCV (Haematocrit)", "%", 1, rr(40.0, 50.0, sex = "M"), rr(36.0, 46.0, sex = "F")),
            p("mcv", "MCV", "fL", 1, rr(80.0, 100.0)),
            p("mch", "MCH", "pg", 1, rr(27.0, 32.0)),
            p("mchc", "MCHC", "g/dL", 1, rr(32.0, 36.0)),
            p("neut", "Neutrophils", "%", 0, rr(40.0, 70.0)),
            p("lymph", "Lymphocytes", "%", 0, rr(20.0, 40.0)),
            p("mono", "Monocytes", "%", 0, rr(2.0, 10.0)),
            p("eos", "Eosinophils", "%", 0, rr(1.0, 6.0)),
            p("baso", "Basophils", "%", 0, rr(0.0, 1.0))),
        test("HB", "Haemoglobin", HEMA, 100.0, "blood", "Cyanmethaemoglobin",
            p("hb", "Haemoglobin", "g/dL", 1, HB_RANGES)),
        test("ESR", "Erythrocyte Sedimentation Rate", HEMA, 100.0, "blood", "Westergren",
            p("esr", "ESR (1st hour)", "mm/hr", 0, ESR_RANGES)),
        test("PLT", "Platelet Count", HEMA, 150.0, "blood", "Automated cell counter",
            p("plt", "Platelet Count", "/cumm", 0, PLT_RANGES)),
        test("BG", "Blood Group (ABO & Rh)", HEMA, 100.0, "blood", "Slide agglutination",
            p("abo", "ABO Group", null, 0),
            p("rh", "Rh (D) Factor", null, 0)),

        // ── Biochemistry: glucose / diabetes ──
        test("FBS", "Fasting Blood Sugar", BIO, 80.0, "blood", "GOD-POD",
            p("fbs", "Glucose (Fasting)", "mg/dL", 0, rr(70.0, 100.0, criticalLow = 50.0, criticalHigh = 400.0))),
        test("PPBS", "Post Prandial Blood Sugar", BIO, 80.0, "blood", "GOD-POD",
            p("ppbs", "Glucose (2hr Post Prandial)", "mg/dL", 0, rr(70.0, 140.0, criticalLow = 50.0, criticalHigh = 500.0))),
        test("RBS", "Random Blood Sugar", BIO, 80.0, "blood", "GOD-POD",
            p("rbs", "Glucose (Random)", "mg/dL", 0, rr(70.0, 140.0, criticalLow = 50.0, criticalHigh = 500.0))),
        test("HBA1C", "Glycosylated Haemoglobin (HbA1c)", BIO, 450.0, "blood", "HPLC",
            p("hba1c", "HbA1c", "%", 1, rr(4.0, 5.6))),

        // ── Biochemistry: kidney ──
        test("UREA", "Blood Urea", BIO, 120.0, "serum", "Urease-GLDH",
            p("urea", "Blood Urea", "mg/dL", 0, rr(15.0, 40.0, criticalHigh = 200.0))),
        test("CREAT", "Serum Creatinine", BIO, 150.0, "serum", "Modified Jaffe",
            p("creat", "Creatinine", "mg/dL", 2, CREAT_RANGES)),
        test("URIC", "Serum Uric Acid", BIO, 150.0, "serum", "Uricase-POD",
            p("uric", "Uric Acid", "mg/dL", 1, rr(3.5, 7.2, sex = "M"), rr(2.6, 6.0, sex = "F"))),
        test("KFT", "Kidney Function Test", BIO, 500.0, "serum", "Automated analyzer",
            p("urea", "Blood Urea", "mg/dL", 0, rr(15.0, 40.0, criticalHigh = 200.0)),
            p("creat", "Creatinine", "mg/dL", 2, CREAT_RANGES),
            p("uric", "Uric Acid", "mg/dL", 1, rr(3.5, 7.2, sex = "M"), rr(2.6, 6.0, sex = "F"))),
        test("ELEC", "Serum Electrolytes (Na/K/Cl)", BIO, 400.0, "serum", "ISE",
            p("na", "Sodium", "mmol/L", 0, rr(136.0, 145.0, criticalLow = 120.0, criticalHigh = 160.0)),
            p("k", "Potassium", "mmol/L", 1, rr(3.5, 5.1, criticalLow = 2.8, criticalHigh = 6.2)),
            p("cl", "Chloride", "mmol/L", 0, rr(98.0, 107.0, criticalLow = 80.0, criticalHigh = 120.0))),
        test("CA", "Serum Calcium", BIO, 200.0, "serum", "Arsenazo III",
            p("ca", "Calcium (Total)", "mg/dL", 1, rr(8.5, 10.5, criticalLow = 6.5, criticalHigh = 13.0))),

        // ── Biochemistry: liver ──
        test("LFT", "Liver Function Test", BIO, 600.0, "serum", "Automated analyzer",
            p("bil_t", "Bilirubin - Total", "mg/dL", 2, rr(0.3, 1.2, criticalHigh = 15.0)),
            p("bil_d", "Bilirubin - Direct", "mg/dL", 2, rr(0.0, 0.3)),
            p("sgot", "SGOT (AST)", "U/L", 0, rr(0.0, 40.0, sex = "M"), rr(0.0, 32.0, sex = "F")),
            p("sgpt", "SGPT (ALT)", "U/L", 0, rr(0.0, 41.0, sex = "M"), rr(0.0, 33.0, sex = "F")),
            p("alp", "Alkaline Phosphatase", "U/L", 0, ALP_RANGES),
            p("tp", "Total Protein", "g/dL", 1, rr(6.4, 8.3)),
            p("alb", "Albumin", "g/dL", 1, rr(3.5, 5.2))),
        test("BILI", "Bilirubin (Total, Direct & Indirect)", BIO, 150.0, "serum", "Diazo",
            p("bil_t", "Bilirubin - Total", "mg/dL", 2, rr(0.3, 1.2, criticalHigh = 15.0)),
            p("bil_d", "Bilirubin - Direct", "mg/dL", 2, rr(0.0, 0.3)),
            p("bil_i", "Bilirubin - Indirect", "mg/dL", 2, rr(0.2, 0.9))),
        test("SGPT", "SGPT (ALT)", BIO, 120.0, "serum", "IFCC kinetic",
            p("sgpt", "SGPT (ALT)", "U/L", 0, rr(0.0, 41.0, sex = "M"), rr(0.0, 33.0, sex = "F"))),
        test("SGOT", "SGOT (AST)", BIO, 120.0, "serum", "IFCC kinetic",
            p("sgot", "SGOT (AST)", "U/L", 0, rr(0.0, 40.0, sex = "M"), rr(0.0, 32.0, sex = "F"))),
        test("ALP", "Alkaline Phosphatase", BIO, 120.0, "serum", "PNPP kinetic",
            p("alp", "Alkaline Phosphatase", "U/L", 0, ALP_RANGES)),

        // ── Biochemistry: lipids ──
        test("CHOL", "Total Cholesterol", BIO, 150.0, "serum", "CHOD-POD",
            p("chol", "Total Cholesterol", "mg/dL", 0, rr(high = 200.0))),
        test("TG", "Triglycerides", BIO, 150.0, "serum", "GPO-POD",
            p("tg", "Triglycerides", "mg/dL", 0, rr(high = 150.0))),
        test("HDL", "HDL Cholesterol", BIO, 150.0, "serum", "Direct",
            p("hdl", "HDL Cholesterol", "mg/dL", 0, rr(40.0, 60.0, sex = "M"), rr(50.0, 60.0, sex = "F"))),
        test("LDL", "LDL Cholesterol", BIO, 150.0, "serum", "Direct",
            p("ldl", "LDL Cholesterol", "mg/dL", 0, rr(high = 100.0))),
        test("VLDL", "VLDL Cholesterol", BIO, 100.0, "serum", "Calculated",
            p("vldl", "VLDL Cholesterol", "mg/dL", 0, rr(5.0, 40.0))),

        // ── Endocrinology / vitamins ──
        test("TSH", "Thyroid Stimulating Hormone", ENDO, 250.0, "serum", "CLIA",
            p("tsh", "TSH", "µIU/mL", 2, rr(0.4, 4.2))),
        test("T3", "Total Triiodothyronine (T3)", ENDO, 250.0, "serum", "CLIA",
            p("t3", "T3 (Total)", "ng/dL", 0, rr(80.0, 200.0))),
        test("T4", "Total Thyroxine (T4)", ENDO, 250.0, "serum", "CLIA",
            p("t4", "T4 (Total)", "µg/dL", 1, rr(5.1, 14.1))),
        test("VITD", "Vitamin D (25-OH)", ENDO, 1200.0, "serum", "CLIA",
            p("vitd", "25-Hydroxy Vitamin D", "ng/mL", 1, rr(30.0, 100.0))),
        test("VITB12", "Vitamin B12", ENDO, 900.0, "serum", "CLIA",
            p("b12", "Vitamin B12", "pg/mL", 0, rr(200.0, 900.0))),

        // ── Serology ──
        test("CRP", "C-Reactive Protein (Quantitative)", SERO, 300.0, "serum", "Immunoturbidimetry",
            p("crp", "CRP", "mg/L", 1, rr(0.0, 6.0))),
        test("RA", "RA Factor (Quantitative)", SERO, 300.0, "serum", "Immunoturbidimetry",
            p("ra", "Rheumatoid Factor", "IU/mL", 1, rr(0.0, 14.0))),
        test("WIDAL", "Widal Test (Slide)", SERO, 200.0, "serum", "Slide agglutination",
            p("s_typhi_o", "S. typhi 'O'", null, 0, qual("Non-reactive")),
            p("s_typhi_h", "S. typhi 'H'", null, 0, qual("Non-reactive")),
            p("s_para_ah", "S. paratyphi 'AH'", null, 0, qual("Non-reactive")),
            p("s_para_bh", "S. paratyphi 'BH'", null, 0, qual("Non-reactive"))),
        test("HBSAG", "HBsAg (Screening)", SERO, 250.0, "serum", "Immunochromatography",
            p("hbsag", "HBsAg", null, 0, qual("Non-reactive"))),
        test("HIV", "HIV I & II (Screening)", SERO, 350.0, "serum", "Immunochromatography",
            p("hiv", "HIV I & II Antibodies", null, 0, qual("Non-reactive"))),
        test("DENGUE", "Dengue NS1 Antigen", SERO, 600.0, "serum", "ELISA/Rapid",
            p("ns1", "Dengue NS1 Antigen", null, 0, qual("Negative"))),
        test("MP", "Malaria Parasite (Smear/Antigen)", SERO, 250.0, "blood", "Smear + rapid antigen",
            p("mp", "Malaria Parasite", null, 0, qual("Not seen"))),

        // ── Clinical Pathology ──
        test("URINE-R", "Urine Routine & Microscopy", PATH, 150.0, "urine", "Strip + microscopy",
            p("colour", "Colour", null, 0, qual("Pale yellow")),
            p("appearance", "Appearance", null, 0, qual("Clear")),
            p("ph", "Reaction (pH)", null, 1, rr(4.6, 8.0)),
            p("sp_gravity", "Specific Gravity", null, 3, rr(1.005, 1.03)),
            p("protein", "Protein", null, 0, qual("Absent")),
            p("glucose", "Glucose", null, 0, qual("Absent")),
            p("ketones", "Ketones", null, 0, qual("Absent")),
            p("pus_cells", "Pus Cells", "/hpf", 0, qual("0-5")),
            p("rbc_u", "Red Blood Cells", "/hpf", 0, qual("Absent")),
            p("epithelial", "Epithelial Cells", "/hpf", 0, qual("Few"))),
        test("UPT", "Urine Pregnancy Test", PATH, 150.0, "urine", "Immunochromatography",
            p("upt", "Pregnancy Test (hCG)", null, 0, qual("Negative"))),
        test("STOOL-R", "Stool Routine & Microscopy", PATH, 200.0, "stool", "Microscopy",
            p("colour", "Colour", null, 0, qual("Brown")),
            p("consistency", "Consistency", null, 0, qual("Formed")),
            p("ova", "Ova", null, 0, qual("Not seen")),
            p("cyst", "Cysts", null, 0, qual("Not seen")),
            p("occult", "Occult Blood", null, 0, qual("Negative"))),
    )

    private fun panels(): List<LabPanel> = listOf(
        LabPanel("panel-cbc", "CBC", "CBC with ESR", 400.0,
            listOf(testId("CBC"), testId("ESR"))),
        LabPanel("panel-lft", "LFT", "Liver Function Panel", 550.0,
            listOf(testId("LFT"))),
        LabPanel("panel-kft", "KFT", "Kidney Panel (with Electrolytes)", 800.0,
            listOf(testId("KFT"), testId("ELEC"))),
        LabPanel("panel-lipid", "LIPID", "Lipid Profile", 500.0,
            listOf(testId("CHOL"), testId("TG"), testId("HDL"), testId("LDL"), testId("VLDL"))),
        LabPanel("panel-thyroid", "THYROID", "Thyroid Profile (T3 T4 TSH)", 600.0,
            listOf(testId("T3"), testId("T4"), testId("TSH"))),
    )
}
