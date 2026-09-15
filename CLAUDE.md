# CLAUDE.md — BNM Lab (repo directory: BNMLab)

**The product is BNM Lab** (renamed from "BNM Diagnosis", 2026-09-12). The
rename went all the way: the Kotlin package is `com.bnm.lab`, this repo
directory is `BNMLab`, the Studio extension slug is `lab`, its routes are
`/lab` and `/api/lab/*`, releases are `lab-v*` / `lab-latest` with `BNMLab-*`
assets, and the desktop data dir is `BNMLab`.

🔴 **FIVE COMPATIBILITY SHIMS EXIST AND MUST NOT BE DELETED.** Each protects
something already installed in the field; none is dead code:

1. `_shared/auth.ts` (BusinessStudio) keeps the legacy `diagnosis` infinite-idle
   key beside `lab`. Devices activated before the rename carry `app='diagnosis'`
   in a 10-year JWT and never re-mint — remove the key and they silently drop to
   a 60-minute idle and 401 out of sync, the EMR inbox and billing.
2. `UpdateChecker.TAG_PREFIXES` lists `lab-v` AND `diagnosis-v`; `assetNames`
   lists the `BNMLab-*` name then the `BNMDiagnosis-*` one. The tag prefix is
   compiled into every shipped build, so a pre-rename install only knows the old
   names — without these it reports "you are up to date" forever.
3. `.github/workflows/release.yml` dual-publishes: the `lab-*` channel plus a
   legacy `diagnosis-v*` / `diagnosis-latest` alias with `BNMDiagnosis-*` copies
   and checksum lines for both. Retire only when no pre-rename install remains.
4. `DriverFactory.desktop.kt` copies the old data dir on first run (`BNMLab` →
   `BNMDiagnosis` → `BNMAdmin`, COPY never move, `-wal`/`-shm` included).
   SQLDelight is the SYSTEM OF RECORD and a standalone licence has no server
   copy: without this the app creates an empty database and a lab's work appears
   to vanish, with no error.
5. `navigation-config.ts` maps both `lab` and `diagnostics` slugs to `/lab`.

Still deliberately NOT renamed: `role='lab_device'` (write-once in a signed
token — changing it permanently bricks activated devices), `upgradeUuid` (a
GUID, not a name — changing it breaks MSI upgrades), and `lab_licenses.lab_name`
(customer lab names). Gradle's `rootProject.name` is `BNMLab` without a space
because Gradle rejects spaces in project names.

**BNM Lab** is a desktop-first KMP (Compose Multiplatform) LIMS for
diagnostic laboratories: patients → test orders (accession/barcode) → results
entry with reference ranges → pathologist verify/approve → printable report +
GST billing. **Complete offline-first**: SQLDelight is the SYSTEM OF RECORD —
every workflow must work with zero network, indefinitely. Sync (BNM ecosystem,
EMR `clinical_lab_orders` bridge) is additive, never required.

Product/build plan: `/Users/dineshkumarr/BNM/BNMLAB_PLAN.md` (phases P0-P5).
Scaffolded 2026-08-18 from the BNMBilling skeleton (package renamed
`com.bnm.billing` → `com.bnm.lab`) — billing's offline invoice/outbox/
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
  is always exportable regardless of license state. That promise is enforced
  by `LicenseStanding` (NONE / CURRENT / LAPSED) + `navigation/LicenceGate`:
  ONLY a computer with no genuine licence (never activated, deactivated, bad
  signature) opens on Activation. A subscription past `lic_exp + gr` (LAPSED)
  or a BNM-blocked device opens on staff sign-in READ-ONLY — the new-work
  routes (NewOrder, CreateInvoice, Cart, CustomerDetails) refuse it, everything
  else stays open. 🔴 Never gate the entry screen on `isLicensed()` again (use
  `isActivated()`), and never compute expiry/grace outside `standingOf` /
  `subscriptionStatusOf` — the warning and the lock must share the signed `gr`
  and the guarded clock (`trustedNowSeconds`).
- Server side lives in BusinessStudio: `admin-lab` edge fn + `lab_licenses`/
  `lab_devices` tables (both Supabase refs, LOCKSTEP).
- Git: `dineshkumarappdeveloper/bnm-diagnosis`, branch `main`; commit local,
  push only when asked.
