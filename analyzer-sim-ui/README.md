# BNM Analyzer Simulator — the window

The same simulator as [`../analyzer-sim`](../analyzer-sim/README.md), with a
window instead of a command line. One installer, no terminal, no flags.

This is a **front end, not a fork**: every frame, every fault and every byte on
the wire comes from `:analyzer-sim`, and the form is checked by the core's own
`Cli.validate` before Send will do anything. The window and
`java -jar analyzer-sim.jar` cannot drift into sending different things, or into
refusing different ones.

---

## Safety — read this before you type an address

**Everything this tool sends is invented, and BNM Lab cannot tell.** A frame
names an accession; the app files the values onto the order with that accession,
attributed to the instrument, and the result can then be approved, printed and
handed to a patient. Nothing in the frame, the traffic log, the audit trail or
the report says the numbers were generated.

So the window:

- refuses to send anywhere but this computer until you tick **"Yes, send
  invented results to &lt;address&gt;"**, a red block that appears under the
  address box the moment it stops being loopback. The tick is cleared whenever
  the address changes — consent is per-target — and **no preset carries it**, so
  a preset can never re-consent for you months later;
- defaults the specimen id to `BNMTEST-0001`, which no accession series
  produces. Keep test ids obviously fake: the app matches a bare number against
  the tail of an accession, so an id of `42` will find `ACC-S1-00042` on the
  lab's own series;
- says in the transcript, before the first sample, anything the run will *not*
  do — ticking QC on a Mispa, for instance, whose format has no field to carry
  it.

Rehearse on a **bench install or the Demo Store data**, never on a lab seeing
patients. If you must prove something on a production machine, register a
throwaway order and send to that accession only.

---

## Using it

**Before you start**, in BNM Lab: Settings ▸ Instruments ▸ add an analyzer, pick
the driver, set the transport and port, enable it. The Instruments screen should
say *listening*. The simulator names the driver key you need, top left.

1. **Open it.** Presets ▸ *Mindray on this PC* is the usual starting point.
2. **Pick the analyzer.** Mindray BC-5130 or Agappe Mispa Count X. The port
   follows (5500 / 5501) unless you typed your own, and the line underneath
   tells you exactly which instrument row the lab must have created.
3. **Type the lab PC's address.** `127.0.0.1` if BNM Lab is on this machine,
   otherwise its IP — and read the red consent block if one appears (see
   Safety). Press **Test link** — it opens the connection and closes
   it again, sending nothing:
   - *Reached BNM Lab at 192.168.1.50:5500.* — the link is fine; anything that
     goes wrong from here is the frame, not the network.
   - *Nothing is listening on 5500 …* — BNM Lab is not running, the instrument
     row is disabled, the port is a digit out, or Windows Firewall is eating it.
4. **Set the sample.** The specimen id is the accession on the tube. Leave
   **+1 per run** on and every SAMPLE takes the next one (SIM-0001, SIM-0002 …),
   so a five-sample rehearsal is five accessions rather than five results
   fighting over one, and the next run starts after the batch this one used.
   A pattern such as `ACC-S1-000{1..5}` is an enumeration and is left exactly as
   typed. Turn the tick off and every sample carries the one id — useful for
   proving the app does not double-apply, and the transcript warns you that it
   cannot prove anything about results being lost. Pick a profile; `critical` is the one that should reach the
   call-out list.
5. **Press Send.** Each sample appears in the transcript on the right: the id,
   the headline values, the bytes that left this machine, and what BNM Lab said
   back. A green bar is an accepted ACK; a red bar is a sample a real analyzer
   would have marked *transmission failed*. **Copy** puts the whole transcript
   on the clipboard for a bug report.
6. **Read the green line under the transcript** before you decide it worked. It
   says what BNM Lab should be showing — *"Expect: Queued for manual claim — no
   order matches 'SIM-0001'"* — and it changes with every option you tick. A run
   that does not crash is not a run that passed.

**Faults** is collapsed until you need it, and it is the reason the tool exists.
Each checkbox says what it proves. They are the calls: half a frame, a dribbled
cable, a barcode nobody keyed, a firmware code no driver has met. On a serial
link, *Truncated frame*, *Burst* and *Hang* grey out: all three are about a
connection, and a cable has none — worse, a half frame on serial is never logged
and merges with your next sample, losing both.

**CBC only** (Mindray) is a run *mode*, not a display option: no differential
rows at all, which is a different frame from "no histograms".

**Stop** interrupts a run. It lands at the next gap between samples, so a sample
already waiting out its ACK timeout finishes first — the button says
*Stopping…* rather than pretending otherwise.

**Presets** save everything on screen under a name, in
`presets.json` under this tool's own per-user data folder (`BNMAnalyzerSim` —
never BNM Lab's). Three ship with it: *Mindray on this PC*, *Mispa on this PC*
and *Commissioning rehearsal* (five samples, two seconds apart, ids advancing).

---

## Building the installer

```bash
# Windows — MUST be run ON Windows; jpackage cannot cross-build an MSI
./gradlew :analyzer-sim-ui:packageMsi -PappVersion=1.0.0

# macOS / Linux, same rule — the format must match the machine
./gradlew :analyzer-sim-ui:packageDmg -PappVersion=1.0.0
./gradlew :analyzer-sim-ui:packageDeb -PappVersion=1.0.0

# the unpacked app, for a quick check without making an installer
./gradlew :analyzer-sim-ui:createDistributable -PappVersion=1.0.0

# during development
./gradlew :analyzer-sim-ui:run
```

Artifacts land in `analyzer-sim-ui/build/compose/binaries/main/<format>/`. The
installed app is *BNM Analyzer Simulator*; it carries its own `upgradeUuid`, so
the MSI can never be mistaken for an upgrade of BNM Lab.

### The jlink trap

`nativeDistributions.modules` decides which JDK modules are in the bundled
runtime. A missing one crashes the **packaged** app on launch, and
`./gradlew run` — which uses the whole JDK — never shows it. BNM Lab shipped
that bug once.

So this app has a `--selftest` flag that opens no window: it builds both frames,
round-trips a preset through a real file, renders the whole screen off-screen
through Compose and Skia, and enumerates the serial ports. After changing
anything about packaging:

```bash
./gradlew :analyzer-sim-ui:createDistributable
"analyzer-sim-ui/build/compose/binaries/main/app/BNM Analyzer Simulator.app/Contents/MacOS/BNM Analyzer Simulator" --selftest
# selftest OK — mindray frame 3519 bytes, mispa frame 1245 chars, 4 presets,
# screen rendered 630000 px, 6 serial port(s) visible.
```

Exit 0 and one line, so CI can assert on it with no display attached.

---

## Commissioning rehearsal — the script to follow at a client's site

Ten minutes, before anyone plugs an analyzer in. Load the **Commissioning
rehearsal** preset and change the address; then walk it.

| # | Do this | BNM Lab must show |
| --- | --- | --- |
| 1 | **Test link** | *Reached BNM Lab at …*. If not, stop: it is the port, the firewall, or the instrument row being disabled. Nothing below matters until this passes. |
| 2 | Faults ▸ **Hang**, Send | The simulator's address as the peer, **bytes 0, frames 0**. Untick it again. |
| 3 | Id `SIM-0001`, Send | The **claim queue**, reason *no order matches 'SIM-0001'*. The transcript must show `ACK after Nms: MSA|AA|…` — an ACK that never comes is what makes a real analyzer stop sending. |
| 4 | Register a patient and a CBC in BNM Lab; put that accession in the id box, tick **Scattergram**, Send | The result on that order, in the **catalog's** units — HGB around 14, not 140; WBC around 7000, not 7.1 — with three histograms and the scattergram on the report. ~40 KB: this is the message big enough to find a buffer bug. |
| 5 | Faults ▸ **No specimen id**, Send | Another claim-queue row, reason *no specimen id keyed on the analyzer*. This is what a lab sees all day when the tube is run before the barcode is scanned, so show the operator the one-tap claim. |
| 6 | Faults ▸ **Truncated frame**, Send | The traffic log saying *Connection closed with N unframed bytes (ignored)*, and the frame counter not moving. Nothing filed. |
| 7 | Faults ▸ **Slow chunks** 40 ms, with the scattergram, Send | **One** result. A fragmenting network or a slow USB-serial adapter delivers a long message in hundreds of pieces, and it still has to arrive once. |
| 8 | Profile **critical**, a fresh accession, Send | The result on the dashboard's **critical call-out list**. Labs are required to phone criticals; if it does not appear, the catalog's critical ranges are not set. |
| 9 | Samples **5**, seconds apart **2**, **+1 per run** on, Send | Five results at pace, with five accessions, none lost. |

For a Mispa install, choose **Agappe Mispa Count X** and **Serial (RS-232)**,
then pick the port and press **Test link**. Step 1 becomes "does the port open
at all" — the simulator names the adapter it found, and if it cannot open it,
that is the cable or a terminal program still holding it. Remember what the
window says under the patient boxes: a Mispa frame carries no patient name, and
no units at all, so check the entered value against the catalog's unit on the
first `leukocytosis` sample.

---

## What is tested, and how

```bash
./gradlew :analyzer-sim-ui:build          # the lot
```

- **`SimFormTest`** — the rules behind the boxes: defaults, the port following
  the analyzer (and never overwriting one you typed), serial being a Mispa-only
  link, the auto-increment id sequence, every validation message, and the form
  becoming exactly the `Options` the CLI would have parsed.
- **`HintsTest`** — every "what should happen in BNM Lab" sentence. Each one is
  a claim about the app's behaviour; a claim that has gone stale is worse than
  no hint.
- **`PresetStoreTest`** — a full round-trip through a real file, the built-ins,
  a corrupt file, a file from a newer build, and a hand-edited preset that names
  an impossible link.
- **`TranscriptTest`** — the fold from core events to what is drawn, including
  which block a burst failure colours.
- **`SimScreenRenderTest`** — draws the whole window, one `ImageComposeScene`
  per test (the repo's convention): the default, the faults panel expanded, a
  transcript with one success and one failure, and the serial link with no
  adapter. A layout-time crash never shows in a unit test and would close the
  window the moment a human opened it. The frames land in
  `build/screen-render/` for a human look.
- **`LiveRunTest`** — opt-in, the window's own path against a REAL BNM Lab:
  ```bash
  ./gradlew :analyzer-sim-ui:test -Dbnm.lab.live=true --tests '*LiveRunTest*'
  ```
  It prints the transcript the window would have drawn, which is also the
  fastest way to produce one for a commissioning report.

The core's own suite (`./gradlew :analyzer-sim:build`) still covers the frames,
the faults and the CLI — including `CliTranscriptParityTest`, which pins the
command line's transcript word for word so adding this window could not quietly
reword it.
