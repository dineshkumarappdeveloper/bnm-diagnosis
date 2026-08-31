# CLAUDE.md — BNMDiagnosis

**BNMDiagnosis** is a desktop-first KMP (Compose Multiplatform) LIMS for
diagnostic laboratories: patients → test orders (accession/barcode) → results
entry with reference ranges → pathologist verify/approve → printable report +
GST billing. **Complete offline-first**: SQLDelight is the SYSTEM OF RECORD —
every workflow must work with zero network, indefinitely. Sync (BNM ecosystem,
EMR `clinical_lab_orders` bridge) is additive, never required.

Product/build plan: `/Users/dineshkumarr/BNM/BNMLAB_PLAN.md` (phases P0-P5).
Scaffolded 2026-08-18 from the BNMBilling skeleton (package renamed
`com.bnm.billing` → `com.bnm.diagnosis`) — billing's offline invoice/outbox/
printing/payment-sheet machinery is deliberately KEPT and reused: a lab bill
IS a GST invoice whose line items are tests.

- Targets: **jvm desktop = primary** (labs run Windows PCs), android
  secondary, iOS later. `./gradlew :composeApp:run` (desktop),
  `:composeApp:assembleDebug` (android), `:composeApp:packageMsi|Dmg|Deb`.
- Conventions follow BNMAdmin/BNMBilling: expect/actual per platform,
  `Result<...>` + runCatching API methods, MaterialTheme tokens, snake_case
  `@SerialName`. jlink needs `modules("java.sql","java.naming","jdk.unsupported")`.
- Licensing (P2): Ed25519-signed license payloads; lab name is admin-set and
  READ-ONLY in-app; seats = devices. Perpetual licenses never lock; lab data
  is always exportable regardless of license state.
- Server side lives in BusinessStudio: `admin-lab` edge fn + `lab_licenses`/
  `lab_devices` tables (both Supabase refs, LOCKSTEP).
- Git: `dineshkumarappdeveloper/bnm-diagnosis`, branch `main`; commit local,
  push only when asked.
