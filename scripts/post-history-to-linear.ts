#!/usr/bin/env node
/**
 * BNMAdmin/scripts/post-history-to-linear.ts
 *
 * Posts BNMAdmin commit history to its Linear project's Activity tab,
 * grouped by 6 phases (mirrors doc 04 — Build history).
 *
 * BNMAdmin has no node_modules. Run from the Studio repo which has tsx:
 *
 *   export LINEAR_API_KEY="lin_api_..."          # https://linear.app/settings/api
 *   cd /Users/dinesh/BNM/studio
 *   npx tsx /Users/dinesh/BNM/BNMAdmin/scripts/post-history-to-linear.ts
 *   npx tsx /Users/dinesh/BNM/BNMAdmin/scripts/post-history-to-linear.ts --apply
 *
 * Idempotency:
 *   Posted phases tracked in scripts/.linear-history-posted.json (alongside this
 *   script). Re-runs skip already-posted phases. --reset clears that file.
 */

import { spawnSync } from 'node:child_process';
import * as fs from 'node:fs';
import * as path from 'node:path';
import { fileURLToPath } from 'node:url';

// ─────────────────────────────────────────────────────────────────────────────
//  Constants
// ─────────────────────────────────────────────────────────────────────────────

const LINEAR_API = 'https://api.linear.app/graphql';
const PROJECT_ID = '7b0e6591-283c-426f-a164-7abfe01d31ab'; // Apps → BNM Admin (KMM)
const PROJECT_URL = 'https://linear.app/bnmapp/project/bnm-admin-kmm-488a0c0346e2';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const REPO_DIR = path.resolve(__dirname, '..'); // BNMAdmin repo root
const STATE_FILE = path.resolve(__dirname, '.linear-history-posted.json');

// ─────────────────────────────────────────────────────────────────────────────
//  Phase config — mirrors doc 04
// ─────────────────────────────────────────────────────────────────────────────

interface Phase {
  id: string;
  doc: string;
  title: string;
  dateFrom: string;
  dateTo: string;
  paths: string[];
  blurb: string;
}

const PHASES: Phase[] = [
  {
    id: '1', doc: '04', title: 'Phase 1 — Foundation (Mar 4–5)',
    dateFrom: '2026-03-04', dateTo: '2026-03-06',
    paths: ['composeApp', 'iosApp', 'README.md', 'build.gradle.kts',
            'settings.gradle.kts', 'gradle.properties', 'gradle'],
    blurb: 'The whole v1 admin app comes up in 36 hours: KMP scaffold + full business admin panel, order detail with payment status, smart dashboard (revenue chart, period filter, multi-series activity chart, Ecommerce + Wallet sections), WhatsApp-like chat UI, AI Business Assistant with rich markdown rendering. Two `ColumnScope`-for-`SectionCard` footgun fixes ship on the same day (896023c, 989c85e).',
  },
  {
    id: '2', doc: '04', title: 'Phase 2 — Auth + customer ops (Mar 8–15)',
    dateFrom: '2026-03-08', dateTo: '2026-03-16',
    paths: ['composeApp', 'iosApp', 'build.gradle.kts',
            'settings.gradle.kts', 'gradle'],
    blurb: 'Customer Actions Panel in Chat + Business Management. Firebase App Distribution wired in (e3fbd6b) with project flavoring; release signing configured (337f0bb). Customer tags + search. **Unified Google Sign-In** with iOS nonce verification + session restoration (d3b7ad9). Auth persistence + navigation state hardening (bbd2bb1). API error handling.',
  },
  {
    id: '3', doc: '04', title: 'Phase 3 — Company + push + Supabase migration (Mar 20–24)',
    dateFrom: '2026-03-20', dateTo: '2026-03-25',
    paths: ['composeApp', 'iosApp'],
    blurb: "**Company Management module** lands — overview / employees / expenses / salary / P&L. **FCM push notifications** drive real-time chat delivery (no more polling, dd67319). Firebase Messaging pinned to 24.1.0 — `platform()` BOM was removed in Kotlin 2.3 (47ca819). Then the architectural pivot: **`f1ab8ba` migrates the API layer to Supabase Edge Functions** — aligning BNMAdmin with Studio's admin-* functions. Five-week silence follows while Studio's clinical/appointments/TZ/Flows work lands.",
  },
  {
    id: '4', doc: '04', title: 'Phase 4 — Commerce + tablet design system (May 3–4)',
    dateFrom: '2026-05-03', dateTo: '2026-05-05',
    paths: ['composeApp/src/commonMain/kotlin/com/bnm/admin/screens/commerce',
            'composeApp/src/commonMain/kotlin/com/bnm/admin/screens/inventory',
            'composeApp/src/commonMain/kotlin/com/bnm/admin/screens/ecommerce',
            'composeApp/src/commonMain/kotlin/com/bnm/admin/screens/appointments',
            'composeApp/src/commonMain/kotlin/com/bnm/admin/screens/workflows',
            'composeApp/src/commonMain/kotlin/com/bnm/admin/screens/subscriptions',
            'composeApp/src/commonMain/kotlin/com/bnm/admin/screens/dashboard',
            'composeApp/src/commonMain/kotlin/com/bnm/admin/ui/layout',
            'composeApp/src/commonMain/kotlin/com/bnm/admin/audio',
            'composeApp/src/iosMain'],
    blurb: "Commerce module ships with voice playback + inventory + suppliers (53124be). Inventory gets supplier picker + ledger-backed adjustments. **Per-business aliases consumed in the admin app** (02c166a) — display names now always use Studio's alias system. Then `14a8713` introduces the **tablet design system**; the next 6 commits apply it to every major surface (messaging 3-pane, ecommerce master/detail, appointments week-strip+agenda, workflows hub, subscriptions). iPad portrait now lights up the tablet shell (5a14101). `AudioPlayer.ios.kt` unblocks K/N 2.x AVAudioSession bindings (1df4bae).",
  },
  {
    id: '5', doc: '04', title: 'Phase 5 — Chat unification + Studio-style (May 4–5)',
    dateFrom: '2026-05-04', dateTo: '2026-05-06',
    paths: ['composeApp/src/commonMain/kotlin/com/bnm/admin/screens/chat',
            'composeApp/src/commonMain/kotlin/com/bnm/admin/screens/business',
            'composeApp/src/commonMain/kotlin/com/bnm/admin/ui/layout',
            'composeApp/src/commonMain/kotlin/com/bnm/admin/navigation'],
    blurb: 'Chat goes from "two panels side by side" to a unified Studio-shaped header (45a17d4). ChatDetailScreen gains an embedded mode with a permanent customer pane (863f976). Tablet customer pane: slim header + tab strip (146e33e). Sprint A — Invoices section in CustomerActionsPanel. **Collapsible nav rail** with icon-only mode reclaims canvas (03e9441).',
  },
  {
    id: '6', doc: '04', title: 'Phase 6 — Sprints B1–B5 + final polish (May 5–12)',
    dateFrom: '2026-05-05', dateTo: '2026-05-13',
    paths: ['composeApp/src/commonMain/kotlin/com/bnm/admin/screens/chat',
            'composeApp/src/commonMain/kotlin/com/bnm/admin/screens/company',
            'composeApp/src/commonMain/kotlin/com/bnm/admin/screens/dashboard',
            'composeApp/src/commonMain/kotlin/com/bnm/admin/navigation',
            'composeApp/src/commonMain/kotlin/com/bnm/admin/screens/subscriptions',
            'composeApp/src/commonMain/kotlin/com/bnm/admin/screens/main'],
    blurb: 'The "Sprint B" series in the chat customer panel: **B1** read-only Clinical section, **B2** clinical visit editor (vitals / Rx / labs / finalize), **B3** customer alias editor, **B4** subscription edit dialog, **B5** orders create flow with a real product picker. Order quantity becomes a stepper. Subscriptions > Orders renamed to **Deliveries** (matches Studio). **Bulk invoice generator** under Company > Invoices. **Nav regroup by intent — Sell / Engage / Operate / Build** (acae99b). Latest commit (eec4058): dashboard **KPI hero + appointment payment + AI Credits**.',
  },
];

// ─────────────────────────────────────────────────────────────────────────────
//  Args
// ─────────────────────────────────────────────────────────────────────────────

const argv = process.argv.slice(2);
const apply = argv.includes('--apply');
const reset = argv.includes('--reset');
const asUpdate = argv.includes('--as=update');
const health = (argv.find((a) => a.startsWith('--health=')) || '--health=onTrack').split('=')[1];
const onlyPhase = (argv.find((a) => a.startsWith('--phase=')) || '').split('=')[1] || '';

const apiKey = process.env.LINEAR_API_KEY;
if (!apiKey && apply) {
  console.error('ERROR: LINEAR_API_KEY is required when using --apply.');
  console.error('Get one at https://linear.app/settings/api');
  process.exit(1);
}

if (reset) {
  if (fs.existsSync(STATE_FILE)) {
    fs.unlinkSync(STATE_FILE);
    console.log(`✓ Removed state file: ${STATE_FILE}`);
  } else {
    console.log('No state file to remove.');
  }
  if (!apply) process.exit(0);
}

// ─────────────────────────────────────────────────────────────────────────────
//  Helpers
// ─────────────────────────────────────────────────────────────────────────────

interface Commit { hash: string; date: string; author: string; title: string }

function addDays(d: string, n: number): string {
  const dt = new Date(d + 'T00:00:00Z');
  dt.setUTCDate(dt.getUTCDate() + n);
  return dt.toISOString().slice(0, 10);
}

function getCommitsForPhase(p: Phase): Commit[] {
  const args = [
    '-C', REPO_DIR,
    'log', '--all', '--reverse', '--date=short',
    '--pretty=format:%h|%ad|%an|%s',
    `--after=${addDays(p.dateFrom, -1)}`,
    `--before=${addDays(p.dateTo, 1)}`,
    '--', ...p.paths,
  ];
  const result = spawnSync('git', args, { encoding: 'utf8', maxBuffer: 50 * 1024 * 1024 });
  if (result.status !== 0) {
    console.error(`git log failed for ${p.id}:`, result.stderr);
    return [];
  }
  const out = (result.stdout || '').trim();
  if (!out) return [];
  return out.split('\n').map((line) => {
    const idx1 = line.indexOf('|');
    const idx2 = line.indexOf('|', idx1 + 1);
    const idx3 = line.indexOf('|', idx2 + 1);
    return {
      hash: line.slice(0, idx1),
      date: line.slice(idx1 + 1, idx2),
      author: line.slice(idx2 + 1, idx3),
      title: line.slice(idx3 + 1),
    };
  });
}

function escapeMd(s: string): string {
  return s.replace(/[\r\n]+/g, ' ').slice(0, 240);
}

function buildBody(p: Phase, commits: Commit[]): string {
  const lines: string[] = [];
  lines.push(`## ${p.title}`);
  lines.push('');
  lines.push(p.blurb);
  lines.push('');
  lines.push(`→ Full narrative: doc ${p.doc} in this project (${PROJECT_URL}).`);
  lines.push('');
  lines.push('---');
  lines.push('');
  lines.push(`**${commits.length} commit${commits.length === 1 ? '' : 's'}** in scope between \`${p.dateFrom}\` and \`${p.dateTo}\`:`);
  lines.push('');
  for (const c of commits) {
    lines.push(`- \`${c.date}\` \`${c.hash}\` · ${escapeMd(c.title)}`);
  }
  return lines.join('\n');
}

function loadState(): Record<string, string> {
  try { return JSON.parse(fs.readFileSync(STATE_FILE, 'utf8')); } catch { return {}; }
}

function saveState(s: Record<string, string>): void {
  fs.writeFileSync(STATE_FILE, JSON.stringify(s, null, 2));
}

async function graphql(query: string, variables: unknown): Promise<any> {
  const res = await fetch(LINEAR_API, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: apiKey! },
    body: JSON.stringify({ query, variables }),
  });
  const ctype = res.headers.get('content-type') || '';
  if (!ctype.includes('application/json')) {
    const text = await res.text();
    throw new Error(`Linear API returned non-JSON (status=${res.status}): ${text.slice(0, 300)}`);
  }
  const json: any = await res.json();
  if (json.errors) {
    throw new Error(`Linear API error: ${JSON.stringify(json.errors, null, 2)}`);
  }
  return json.data;
}

async function postComment(body: string) {
  const query = `mutation($input: CommentCreateInput!) {
    commentCreate(input: $input) { success comment { id url } }
  }`;
  return graphql(query, { input: { body, projectId: PROJECT_ID } });
}

async function postUpdate(body: string) {
  const query = `mutation($input: ProjectUpdateCreateInput!) {
    projectUpdateCreate(input: $input) { success projectUpdate { id url } }
  }`;
  return graphql(query, { input: { body, projectId: PROJECT_ID, health } });
}

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

// ─────────────────────────────────────────────────────────────────────────────
//  Main
// ─────────────────────────────────────────────────────────────────────────────

async function main() {
  const state = loadState();
  const phases = onlyPhase ? PHASES.filter((p) => p.id === onlyPhase) : PHASES;

  if (phases.length === 0) {
    console.error(`No phases match --phase=${onlyPhase}`);
    process.exit(1);
  }

  console.log(
    `${apply ? 'APPLY' : 'DRY-RUN'} · ${asUpdate ? 'project Updates' : 'project Comments'} · ${phases.length} phase${phases.length === 1 ? '' : 's'} to process`,
  );
  console.log(`State file: ${STATE_FILE}`);
  console.log('');

  let posted = 0, skipped = 0, empty = 0;

  for (const p of phases) {
    if (state[p.id]) {
      console.log(`✓ phase ${p.id} already posted: ${state[p.id]}`);
      skipped++;
      continue;
    }
    const commits = getCommitsForPhase(p);
    if (commits.length === 0) {
      console.log(`○ phase ${p.id} — no commits in window, skipping`);
      empty++;
      continue;
    }
    const body = buildBody(p, commits);
    console.log(`\n=== phase ${p.id} (${commits.length} commits, ${body.length} chars) ===`);
    console.log(p.title);

    if (!apply) {
      console.log(body.split('\n').slice(0, 8).join('\n'));
      console.log('...');
      continue;
    }

    try {
      const data = asUpdate ? await postUpdate(body) : await postComment(body);
      const url = asUpdate
        ? data.projectUpdateCreate.projectUpdate.url
        : data.commentCreate.comment.url;
      state[p.id] = url;
      saveState(state);
      console.log(`✓ posted: ${url}`);
      posted++;
      await sleep(750);
    } catch (e: any) {
      console.error(`✗ phase ${p.id} FAILED:`, e.message);
      console.error('Stopping. Re-run to continue from this phase.');
      process.exit(1);
    }
  }

  console.log('');
  console.log(`Done. posted=${posted} skipped=${skipped} empty=${empty} total=${phases.length}`);
  if (!apply) console.log('Dry-run only. Re-run with --apply to POST.');
}

main().catch((e) => { console.error(e); process.exit(1); });
