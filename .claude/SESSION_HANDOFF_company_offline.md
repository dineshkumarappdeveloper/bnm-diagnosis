# SESSION HANDOFF — Company offline-first + unified sync (resume after /clear)

Written 2026-06-20 because the live session got rate-limited / context too large. After you `/compact` or `/clear`, tell me: **"resume from .claude/SESSION_HANDOFF_company_offline.md"** and I'll continue.

## CURRENT TASK
Make the **Company** section of BNMAdmin offline-first and part of the SAME unified sync (SyncEngine), then build → uninstall/reinstall on device → verify the first-sync loads company data. Mirrors the ecommerce-offline pattern.

## ✅ DONE (all code written, NOT yet committed)

### Backend (BusinessStudio) — already DEPLOYED to BOTH refs
- **Migration** `supabase/migrations/20260620_company_offline_seq.sql` — adds `seq` + `deleted_at` + `bump_seq_generic` BEFORE-UPDATE trigger to: `company_employees`, `company_departments`, `company_expenses`, `company_expense_categories`, `company_salary_payments`. **Applied to India (74adc7e0) + main (ec9e3367) via execute_sql.**
- **`supabase/functions/admin-company/index.ts`** — added a `?sinceSeq=` delta branch to the top of 5 GET routes: `/employees`, `/departments` (added `search` param to signature), `/expenses`, `/expense-categories` (added `search` param; keeps the seed RPC), `/salary`. Each: `.eq('business_id').gt('seq', sinceSeq).order('seq', asc).limit(100000)`, returns same wrapper key (`employees`/`departments`/`expenses`/`categories`/`payments`). **DEPLOYED both refs** (`bash scripts/deploy-edge-fn.sh admin-company`). Verified working via curl (HTTP 200, returns rows).

### Client (BNMAdmin) — compiles (desktop+android), NOT committed
- **`api/BusinessStudioApi.kt`** — added private `companyDelta(path,bid,sinceSeq,key)` + `getEmployeesDelta/getDepartmentsDelta/getExpensesDelta/getExpenseCategoriesDelta/getSalaryDelta` (after `getCategoriesDelta`).
- **`chat/CommerceRepository.kt`** — imports (CompanyDepartment/Employee/Expense/ExpenseCategory, SalaryPayment); constants `EMPLOYEE/DEPARTMENT/EXPENSE/EXPENSE_CATEGORY/SALARY`; flows `employeesFlow/departmentsFlow/expensesFlow/expenseCategoriesFlow/salaryFlow`; sync methods `syncEmployees/syncDepartments/syncExpenses/syncExpenseCategories/syncSalary` (via the existing private `syncDelta`).
- **`App.kt`** — registered 5 company tasks in `syncEngine` (employee/department/expense/expense_category/salary).
- **Screens wired to local flows**: `CompanyEmployeesScreen` (employeesFlow), `CompanyExpensesScreen` (expensesFlow + expenseCategoriesFlow), `CompanySalaryScreen` (salaryFlow, filtered by period locally), `CompanyOverviewScreen` (derives KPIs locally from employees/departments/expenses/salary + ordersFlow for revenue; `nowMonth` = current YYYY-MM). Removed their `isLoading`/`LoadingScreen`/`LaunchedEffect` API loads.
- **STILL LIVE (not converted, note to user):** `CompanyProfitLossScreen` (detailed P&L report) + `CompanyInvoicesScreen` (customer invoices). Acceptable follow-up.

### 🔑 THE CRITICAL SYNC BUG + FIX (`chat/SyncEngine.kt`) — code written, NEEDS REBUILD+TEST
Symptom: the **first-sync dropped modules at random** (company data, sometimes stock/product, missing until a MANUAL sync; manual sync always worked). Root cause = TWO bugs:
1. **Result discarded** → `SyncTask.sync` was typed `-> Unit`, but `syncDelta`/`syncConversations` return `Result<Unit>` and DON'T throw on failure. The register lambdas coerced the Result to Unit, so the engine's `runCatching` always saw "success" → **the retry never fired and every fetch failure was invisible**.
2. **Cold-start stampede** → 11 delta tasks fired in parallel; concurrent cold edge-function starts dropped some.
FIX applied (in SyncEngine.kt, run() + SyncTask + register):
- `SyncTask.sync` and `register(... sync:)` changed `-> Unit` → **`-> Result<Unit>`** (register lambdas already return Result, no App.kt lambda body change needed).
- Added concurrency gate **`Semaphore(3)`** + `gate.withPermit { ... }` around each task.
- Retry loop now INSPECTS the result: `val ok = runCatching { task.sync(businessId) }.getOrElse { Result.failure(it) }.isSuccess; if (ok || ++attempt >= 4) break; delay(800L*attempt)`.
- Imports added: `kotlinx.coroutines.delay`, `kotlinx.coroutines.sync.Semaphore`, `kotlinx.coroutines.sync.withPermit`.

## ⏭️ PENDING — RESUME HERE
1. **Rebuild**: `cd /Users/dineshkumarr/BNM/BNMAdmin && ./gradlew :composeApp:assembleFullDebug` (also `:composeApp:compileKotlinIosSimulatorArm64` — SyncEngine/repo are commonMain).
2. **Reinstall CLEAN** on device `3083929265000FH`: `adb uninstall com.bnm.admin` then `adb install <apk>`.
3. **Clean first-sync test** (login, NO manual sync) then poll the local DB: `adb exec-out run-as com.bnm.admin cat databases/bnm_chat.db > /tmp/poll.db; sqlite3 /tmp/poll.db "select entity,count(*) from ecom_entity group by entity"`. **EXPECT all 10 now**: order=14, product=8, ledger=7, category=4, customer=5, employee=3, department=3, expense=8, expense_category=32, salary=2 (matches India server for Demo Store). Last failed run (before the Result fix) only had a partial subset — the Result-propagation fix should make the retry actually recover the cold-start failures.
4. **Commit**: BNMAdmin → `main` + push (the company-offline + SyncEngine fix). BusinessStudio → commit `admin-company` + migration LOCALLY (don't push master). Update memory `bnmadmin-sync-engine.md` (the Result-propagation/retry/gate fix) + add a company-offline note.

## TEST HARNESS (also in `.claude/skills/device-test/SKILL.md` + memory bnmadmin-device-testing)
- adb: `/Users/dineshkumarr/Library/Android/sdk/platform-tools/adb`; `export ANDROID_SERIAL=3083929265000FH`.
- Device **I2011** `3083929265000FH`, screen **PIN 2196**. Unlock = wake → swipe up (540,2000→700) → tap PIN-pad coords (2=(539,1399),1=(280,1399),9=(798,1807),6=(798,1603),Enter=(798,2011)) in ONE bash call.
- Login STATIC coords @1080x2408: Email (540,810), Password (540,1019), Sign in (540,1306); Allow-notif dialog (540,1266). Creds **testing@bnmapp.com / DemoStore@2026** → Demo Store (India ref, _id `f4b9f9acc655c6a22a530a75`). Fresh install → Select Business card (540,405).
- Cold start after fresh install is slow (~9s). `input text` handles '@' on this device. Full-res screenshots may exceed the image-read limit → use the DB poll / `uiautomator dump` instead.

## BROADER SESSION (already committed to BNMAdmin main, for reference)
1.4.0 released (`release/1.4.0`). This session also shipped: invoice scan (Gemini), scroll-perf (@Immutable skippable rows), email+password login + forgot-password, edge-to-edge, unified SyncEngine + app-level first-sync banner (dropped per-page SyncHeaders), chat customer-alias names, MyStore Overview hang fix (capped recent movements), Gemini model fix (gemini-2.0-flash free tier = limit 0 → switched platform_ai_config google to gemini-2.5-flash on both refs + new key). All committed to main except the in-flight Company work above.
