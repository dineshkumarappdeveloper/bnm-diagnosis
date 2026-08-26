# BNMDiagnosis — feedback round 1 (2026-08-26)

Nine items from the lab owner, mapped against what the codebase actually has.
Scouted before planning; every "already exists" below was verified at file:line.

---

## 1 · Flag direction arrows + critical symbol

**Ask:** show ↑/↓ alongside H/N, and a distinct symbol when critical.

**Have:** `computeFlag()` is a single brain (`LabRepository.kt:734`) emitting
`N|L|H|CL|CH|A`; criticals already beat highs/lows. Four independent renderers:
Compose chips (`LabUi.kt:128`), the PDF (`ReportDoc.flagLabel`, one function
feeding both PDFBox and Android), and ESC/POS (`LabReport.kt:72`).

**Do:** derive direction from the existing code — **no new column**. `H|CH → up`,
`L|CL → down`, `A → neither`. Add a `⚠` for criticals in Compose + PDF; ESC/POS
stays ASCII (`^`/`v`/`!!`) because thermal printers have no glyph font.
Flags are FROZEN onto `lab_results` at entry (legal requirement) — the arrow must
be *derived from the stored code*, never recomputed against today's ranges.

## 9 · Reference ranges by Male / Female / child

**Have:** `RefRange{sex, ageMinY, ageMaxY, low, high, criticalLow, criticalHigh,
text}` (`LabModels.kt:17`) and a picker that genuinely filters on patient sex AND
age (`LabRepository.pickRange:717`). Sex-split ranges are real and seeded.

**Missing / broken:**
- 🔴 **Specificity scoring is wrong for children.** `score = (sex?2:0)+(age?1:0)`
  makes a sex-split ADULT range outrank a sex-null PAEDIATRIC band — a child is
  flagged against adult ranges. Age must outrank sex.
- Zero age-banded ranges are seeded, despite the helper supporting them.
- No reference-range editor anywhere in the catalog UI.
- Sex `'O'` matches only sex-null ranges → no range at all on sex-split params.
- Overlapping equal-specificity ranges resolve arbitrarily (`maxByOrNull` takes
  the first) with no validation.

## 2 · Employee logins (username + password, offline, no commission access)

**Have:** `staff` table with roles `owner|pathologist|technician|receptionist`,
salted SHA-256 `pin_hash` (`s1$salt$hex`, salt travels inside so any seat can
verify). **Offline login is already solved** — `verifyPin` touches SQLDelight
only. Do not add a network dependency.

**Missing:** no username column; PIN is numeric-only in the UI; **commission and
B2B rate screens are completely ungated**; `canVerify` is dead code; no per-route
gate in the nav graph.

**Do:** add `username`, add an `s2$` password scheme *additively* (labs already
have `s1$` PINs — keep verifying them), gate the money surfaces by role.
Two-place schema change: `Staff.sq` CREATE **and** `AppDatabaseFactory.addColumn`,
appended after `deleted_at` (the queries are `SELECT *`, so order is positional).

## 3 · Reported Time

**Have:** `reported_at` exists, is populated, and **already prints** on the PDF
and thermal slip.

**Missing:** every *on-screen* surface shows only Registered.
🔴 **Bug:** `buildDoc()` snapshots before `markReportedIfApproved()`, so the
FIRST printed report always shows `Reported: -`.

**Do:** surface Reported on order detail / worklist / referrer drill-down, and
fix the first-print bug. No schema change, no server change.

## 4 · Part payment (advance payment)

**Have:** single-tender only. `PaymentSheet` blocks any cash tender below total;
`record-payment` reads only `{total, status}` and never persists an amount.

**Missing:** `paid_amount`, a payments child collection, an offline path (the
outbox drains only `create_invoice` and silently DROPS other ops), and any
balance surfacing.

🔴 **Sync will erase a local-only partial** — `syncDelta` replaces the whole
local invoice doc with the server row. The payment must reach the server through
the outbox, using `depends_on` when the bill itself hasn't synced yet.
Must not touch invoice-number allocation. Must not reuse
`amount_tendered`/`change_due` (those are cash-drawer audit on a *fully paid* bill).

## 5 · Approver digital signature

**Have:** a sign-off block printing "Verified by" / "Approved by (Pathologist)"
names — text only, no dates.

**Missing:** signature storage (no column anywhere), image drawing in EITHER PDF
renderer (neither ever draws a bitmap), and any capture UI.

**Do:** `staff.signature_png` (base64) + optional `qualifications`,
`registration_no` (Indian pathologist sign-off convention). Pass bytes through
`ReportDoc` — the renderers only draw, they never read domain models.

## 6 · QR code → download the report

**Missing entirely, and the most architecturally involved item.** The PDF is
written to a local temp dir and never uploaded; there is no public resolver.

**Constraints that shape the design:**
- 🔴 **This is PHI.** The `invoice-pdfs` public-bucket precedent is the WRONG
  model. Accessions (`ACC-S1-00042`) are sequential and guessable — never the token.
- 🔴 **Standalone licences cannot use any cloud endpoint** (`requireConnected()`
  → 409). A standalone lab must print no QR rather than a dead link.
- Offline-first: the QR must be printable with zero network, so the token has to
  be minted locally and deterministically, with upload queued.

## 7 + 8 · Commission detail, dashboard, per-test override

**Have:** one flat `referrers.commission_pct`. `referrer_rates` is a per-(referrer,
test) **PRICE** list — not commission.

🔴 **The percentage is not snapshotted.** Gross is frozen on the order line, but
the % is read live at report time, so editing a doctor's rate rewrites history.
🔴 `referrers` has no `updated_at`, so an EDITED referrer never re-pushes.
🔴 `referrer_rates` is device-local — not synced at all.

**Do:** lab-wide base % → per-referrer % → per-(referrer,test) override, resolved
by ONE `resolveCommissionPct()` (repo convention: a single resolve function is the
only place money is decided). Snapshot the resolved % onto the order line.
Preserve the fall-through invariant: **never store a copy of the inherited value**,
or a base-rate change stops flowing through.

---

## Order of work

1. Schema + shared brains (one pass, all `.sq` + `AppDatabaseFactory` together —
   local migrations are two-place and positional).
2. Correctness bugs first: pickRange scoring, first-print Reported, commission
   snapshot, referrer `updated_at`.
3. Feature surfaces.
4. Server: part-payment columns + report publish/resolve, both refs in lockstep.
5. Release pipeline → `bnmadmin-releases`, then push.

---

## Outcome (2026-08-26)

All nine items implemented. Desktop + Android + test sources compile; 41 tests,
0 failures.

Three of the nine turned out to be **live defects**, fixed first: children were
being flagged against adult reference ranges (`pickRange` ranked sex above age);
the first printed report always said `Reported: -`; and commission was read live
rather than from the snapshot, so renegotiating a rate rewrote past payouts.
Three more were found on the way: `referrers` had no `updated_at` so edits never
re-pushed, the order push dropped `commission_pct`, and `invoices_status_check`
has no `'unpaid'` — a value my own first draft of the rollup trigger wrote.

Items 4, 5 and 6 have server halves live on BOTH Supabase refs and verified over
real HTTPS (part-payment: 7/7 assertions; QR resolver: unknown and malformed
tokens return an identical 404, anonymous publish 401).

### Known limits, deliberately left

* **Existing installs get no paediatric bands.** `seedIfEmpty()` is guarded by
  `countTests() == 0`, so a lab already running must author the bands in the new
  editor. Merging into a catalog a lab may have customised is a product call.
* **Sex `'O'` still matches sex-neutral ranges only** — on haemoglobin every band
  is M/F, so those patients get no range and no flag. The editor now says so and
  makes a neutral fallback easy to author; inventing a clinical rule here was not
  ours to make.
* **Nothing is device-tested.** The Android PDF renderer in particular is a hand-
  mirror of the verified desktop one and has never been run on hardware.
* **No revoke UI** for a report share link, though the repository and API methods
  exist.
* Signature import (pick a scanned PNG) is not implemented on desktop; drawing is.
