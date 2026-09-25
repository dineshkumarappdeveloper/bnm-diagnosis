# BNM Analyzer Simulator

A small standalone JVM app that pretends to be a lab analyzer, so BNM Lab can be
commissioned, debugged and demonstrated **with no hardware on the bench**.

It speaks the two protocols BNM Lab ships drivers for, emits clinically
plausible and internally consistent results, draws real histograms — and, more
usefully, it misbehaves on demand: half a frame, garbage, a dribbled message, a
firmware code nobody has met, a sample nobody keyed a barcode for.

Those are the calls. "It will not link" is almost never a bug in the parser; it
is the wrong port, the wrong driver, the barcode not keyed, the analyzer giving
up because it never got its ACK, or a cable that delivers a frame in pieces.
Until now none of that could be reproduced anywhere but at a client's bench.

---

## Safety — read this before `--host`

**Everything this tool sends is invented, and BNM Lab cannot tell.** A frame
names an accession; the app files the values onto the order with that accession,
attributed to the instrument, and the result can then be approved, printed and
handed to a patient. Nothing in the frame, the traffic log, the audit trail or
the report says the numbers were generated.

So:

- `--host` defaults to `127.0.0.1`, and **any other address is refused** unless
  you also pass `--live-lab`. That flag is not a formality — it is the thing
  standing between a command recalled from shell history and synthetic results
  on a real patient's order. With it, the run opens with a banner naming the
  machine and the accessions it is about to write to.
- The interactive menu asks the same question in words and needs a typed `YES`.
- `--id` defaults to `BNMTEST-0001`, which no accession series produces. Keep
  test ids obviously fake: the app matches a bare number against the tail of an
  accession, so `--id 42` will find `ACC-S1-00042` on the lab's own series.
- Rehearse on a **bench install or the Demo Store data**, never on a lab seeing
  patients. If you must prove something on a production machine, register a
  throwaway order and send to that accession only.

---

## Run it

```bash
# one file, any machine with a JRE
java -jar analyzer-sim.jar mindray --host 192.168.1.50

# no flags to remember: an interactive menu
java -jar analyzer-sim.jar
```

Build it:

```bash
./gradlew :analyzer-sim:fatJar      # -> analyzer-sim/build/dist/analyzer-sim.jar  (~2.8 MB, self-contained)
./gradlew :analyzer-sim:installDist # -> analyzer-sim/build/install/analyzer-sim/bin/analyzer-sim[.bat]
```

The fat jar is the one to hand to a field engineer: it carries the Kotlin
runtime and jSerialComm (with its bundled native libraries), so a bare Windows
PC with only a JRE runs it. The `installDist` start scripts are the same
program with the jars kept separate — handier on a dev machine.

Java 17 or newer.

---

## The two protocols

### Mindray BC-5130 / BC-5000 / BC-5150 — `mindray`

5-part hematology. One HL7 v2.3.1 `ORU^R01` per sample over TCP, MLLP-framed
(`<VT>` … `<FS><CR>`). **The analyzer is the client**: it dials the lab PC,
sends, waits for an ACK on the same socket, and hangs up. BNM Lab listens
(default port 5500). A sample that gets no ACK is marked "transmission failed"
on the analyzer and may be retransmitted — which is why `--no-ack-wait` exists
as a fault and not as a convenience.

Segments: `MSH` → `PID` → `PV1` → `OBR` → `OBX`×N. `OBR-3` is the specimen id
BNM Lab matches to an accession; `PID-3.1` and `PID-5` are the patient;
`MSH-11 = Q` marks a QC material run. The OBX rows mix four kinds and only
`OBX-3`'s first component tells them apart — numeric results under LOINC and
Mindray `99MRC` codes, `IS` rows for run settings (08001/08002/08003/01002) and
alerts (12011..12052), `NM` 15xxx rows for histogram meta lengths and
discriminator channels, and `ED` rows carrying the base64 binaries.

A worked frame (histogram base64 shortened, `<CR>` shown as line breaks):

```
MSH|^~\&|BC-5130|Mindray|||20260101090000||ORU^R01|SIM20260101090000001|P|2.3.1||||||UNICODE
PID|1||PAT-9001^^^^MR||Menon^Asha|||Female
PV1|1||OPD
OBR|1||ACC-S1-00042|00001^Automated Count^99MRC||20260101090000|20260101090000|||Operator|||||||||||||HM||||||||
OBX|1|IS|08001^Take Mode^99MRC||Open Vial||||||F
OBX|2|IS|08002^Blood Mode^99MRC||Whole Blood||||||F
OBX|3|IS|08003^Test Mode^99MRC||CBC+5DIFF||||||F
OBX|4|IS|01002^Ref Group^99MRC||General||||||F
OBX|5|NM|30525-0^Age^LN||34|Year|||||F
OBX|6|NM|6690-2^WBC^LN||7.13|10*9/L|4.00-10.00|N|||F
OBX|7|NM|704-7^BAS#^LN||0.04|10*9/L|0.00-0.10|N|||F
...
OBX|21|NM|789-8^RBC^LN||4.54|10*12/L|3.50-5.50|N|||F
OBX|22|NM|718-7^HGB^LN||140|g/L|110-150|N|||F
OBX|23|NM|4544-3^HCT^LN||0.400|L/L|0.370-0.540|N|||F
OBX|29|NM|777-3^PLT^LN||263|10*9/L|100-300|N|||F
OBX|38|NM|15004^WBC Histogram. Meta Length^99MRC||1||||||F
OBX|39|NM|15010^WBC Lym left line.^99MRC||14||||||F
OBX|43|ED|15000^WBC Histogram. Binary^99MRC||^Application^Octer-stream^Base64^AAAAAA…||||||F
OBX|52|ED|15200^WBC DIFF Scattergram. BMP^99MRC||^Image^BMP^Base64^Qk02dgEA…||||||F
```

Note the units: the analyzer leaves the factory speaking SI (`g/L`, `L/L`,
`mL/L`, `10*9/L`) and an Indian catalog wants `g/dL`, `%` and `cells/cumm`.
Getting that conversion tested is half the point of sending a real frame —
138 g/L read as 138 g/dL is a phone call to a healthy patient.

**Histogram byte layout.** The app decodes an `ED` payload of
`metaLength + N` bytes as *N single unsigned bytes*, one per channel, so the
simulator sends `metaLength` zero filler bytes followed by 128 counts of
0..255 (meta length 1 for WBC, 2 for RBC, 4 for PLT). Send 256 UInt16 channels
instead and the decoder takes a different branch and draws a different curve.
`AnalyzerSimFidelityTest` pins this.

### Agappe Mispa Count X — `mispa`

3-part hematology. One-way auto-transmission over RS-232 at **115200-8-N-1**,
no flow control, no ACK, after every run. The vendor's `$`-delimited format:

```
$$$Date$Seq$Specimen$Patient$<20 params>#<WBC 128>#<RBC 128>#<PLT 128>#<discriminators>#<disease flags>#<param flags>###
```

The 20 header parameters are, in order: WBC, RBC, PLT, HGB, HCT, MCV, MCH,
MCHC, RDW-SD, RDW-CV, MPV, LYMP%, MID%, GRAN%, LYMP#, MID#, GRAN#, PCT, PDW,
LPCR. Histograms are 128 `$`-separated channels each; discriminators are
`@`-separated; disease flags are `?`-separated. A specimen id of `0` means
**nothing was keyed on the analyzer**.

A worked frame (histograms shortened):

```
$$$20260101090000$1$ACC-S1-00042$PAT-9001$6.68$4.82$256$14.0$43.9$91.1$29.0$31.9$42.7$13.6$9.7$32.1$9.2$58.7$2.14$0.61$3.92$0.25$13.1$25.9#0$0$0$…$0#0$0$…$0#0$0$…$0#13@40@57@114@32@61@12@95##N$N$N$N$N$N$N$L$N$N$N$N$N$N$N$N$N$N$N$N###
```

Real Mispa units are the Indian ones already (g/dL, %, 10^3/µL), and the
protocol carries no unit field at all, which is why `--bad-units` is a Mindray
flag.

**Worth knowing on a Mispa install:** because the frame carries no units, the
app has nothing to convert *from* and stores the number exactly as sent. A
catalog whose WBC is in `cells/cumm` will therefore show `21.67` where the
operator expects `21670`. Sending one `--profile leukocytosis` sample and
looking at the entered value is the fastest way to catch that before the lab
does — set the catalog parameter to `10^3/µL` (or `thou/cumm`) to match what the
analyzer actually reports.

Mispa also accepts `--host/--port` so the whole loop can be rehearsed on one
machine with no serial adapter — but make sure the instrument row in BNM Lab is
set to the TCP transport when you do, or nothing is listening.

---

## Every flag

```
analyzer-sim <mindray|mispa> [options]
analyzer-sim                      # interactive menu
analyzer-sim --help
analyzer-sim --list-profiles
```

### Where to send

| Flag | Meaning |
| --- | --- |
| `--host <addr>` | the PC running BNM Lab (default `127.0.0.1`; any other address needs `--live-lab`) |
| `--live-lab` | "yes, that address really is another machine" — see [Safety](#safety--read-this-before---host) |
| `--port <n>` | the port it listens on (default 5500 mindray, 5501 mispa) |
| `--serial <port>` | send down a serial cable instead — mispa only (`COM3`, `/dev/tty.usbserial-110`) |
| `--baud <n>` | serial speed (default 115200, always 8-N-1, no flow control) |

`--serial` is refused for `mindray`: that driver waits for an ACK on the same
socket and a one-way cable has no way to carry one.

### What to send

| Flag | Meaning |
| --- | --- |
| `--id <ids>` | specimen id: one, a comma list, or a pattern — `'ACC-S1-000{1..5}'` expands to five, leading zeros kept. **Quote the pattern**: bash, zsh and Git Bash expand braces before the JVM sees them, and the tool would get five loose words |
| `--count <n>` | how many samples (default: one per id; the ids cycle if count is larger) |
| `--interval <s>` | seconds between samples |
| `--profile <name>` | see below |
| `--patient "<name>"` | patient name — Mindray `PID-5`; the Mispa format carries none |
| `--patient-id <id>` | patient id |
| `--qc` | a QC material run (Mindray `MSH-11 = Q`); the app ACKs and ignores it. On the Mispa it changes nothing and the run says so — see [Known liberties](#known-liberties) |
| `--no-histograms` | numbers only, no curves — still a 5-part run |
| `--cbc-only` | Mindray run mode `CBC`: no differential at all, not merely no curves |
| `--image` | attach the DIFF scattergram BMP (Mindray, ~40 KB) |
| `--seed <n>` | the patient. Same seed, same frame, forever |

### The ACK (Mindray)

| Flag | Meaning |
| --- | --- |
| `--ack-timeout <s>` | how long to wait for the app's ACK (default 5) |
| `--no-ack-wait` | send and hang up without reading it |

### Faults

| Flag | What it does | What BNM Lab should show |
| --- | --- | --- |
| `--truncated` | cuts the frame at ~55% and closes (**TCP only**) | bytes counted, **frames 0**, log row "Connection closed with N unframed bytes (ignored)" |
| `--garbage` | sends bytes that are not a frame at all | bytes counted, frames 0, no result |
| `--slow-chunks <ms>` | 64-byte pieces, `ms` apart | one normal result — this is the reassembly test |
| `--unknown-code` | Mindray: an OBX under a code no driver map holds. Mispa: a 21st header field | a normal result; the surprise value is kept under the analyzer's own label (Mindray) or dropped (Mispa) — never mapped onto a real parameter |
| `--bad-units` | Mindray: HGB in a unit the converter cannot bridge | the value still lands, plus a red log row "Unit not converted — stored as the analyzer sent it" |
| `--no-specimen` | Mispa specimen `0` / empty Mindray `OBR-3` | the **claim queue**, reason "no specimen id keyed on the analyzer" |
| `--duplicate` | the same frame twice on one connection | two frames; the second must not double-apply |
| `--burst <n>` | n connections at once (**TCP only**) | n results, none lost |
| `--hang` | connects, sends nothing, holds the socket (`--interval` seconds, default 30) (**TCP only**) | the peer address, bytes 0, frames 0 |

`--truncated`, `--burst` and `--hang` are all about a *connection*, and a serial
cable has none, so all three are refused with `--serial`. `--truncated` is the
one that would do harm: the app keeps **one** frame assembler for the whole
serial listener and only reports leftover bytes when the port closes, so the
half frame is never logged, never discarded, and merges with the next sample —
losing both. Rehearse those three over `--host/--port`, on either analyzer.

### Seeing what happens

| Flag | Meaning |
| --- | --- |
| `--dry-run` | print the frame, send nothing |
| `--verbose` | print the frame as it goes out (base64 blobs longer than 200 characters are abbreviated, or a scattergram would fill the screen) |
| `--list-profiles` | describe the value profiles and exit |

---

## Value profiles

| Profile | Picture |
| --- | --- |
| `normal` | healthy adult, everything inside the reference range |
| `anaemia` | microcytic hypochromic — low HGB and MCV, wide RDW, reactive platelets |
| `leukocytosis` | WBC ~22 with a neutrophil left shift |
| `thrombocytopenia` | platelets ~40 with a large MPV |
| `critical` | pancytopenia at panic values — below the catalog's critical lows (HGB 7 g/dL, WBC 1000 /cumm, PLT 20 000 /cumm), so the result reaches the call-out list |
| `random` | a plausible patient drawn anywhere across the ranges — HGB is derived from a physiological MCHC rather than drawn, or the indices come out impossible |

Every profile is **internally consistent**, because a report that disagrees with
itself teaches nobody anything about the link:

- the differential sums to exactly 100.0, and the three-part view (LYMP / MID /
  GRAN) sums to 100.0 too;
- absolute counts are WBC × percent;
- HCT = RBC × MCV / 10, MCH = HGB / RBC, MCHC = HGB / HCT;
- PCT = PLT × MPV / 10 000, PDW and PLCR track MPV;
- RDW-SD = 3.45 × RDW-CV × MCV / 100.

Histograms are drawn from the same numbers: WBC as three populations sized by
the differential with its discriminators read off the valleys, RBC as a
Gaussian centred on MCV and widened by RDW, PLT as a log-normal whose mean is
the reported MPV. A microcytic sample really does sit left of a normal one.

`--seed` fixes the patient; the same seed emits the same bytes on any machine,
so a seed in a bug report is a reproduction.

---

## Commissioning rehearsal

The sequence to prove a fresh install works, before anyone plugs in an
analyzer. Takes about five minutes.

**Before you start**, in BNM Lab: Settings ▸ Instruments ▸ add an analyzer,
driver **Mindray BC-5130 / BC-5000 / BC-5150**, transport TCP, port **5500**,
enabled. The Instruments screen should say *listening*.

1. **The link itself.**
   ```bash
   java -jar analyzer-sim.jar mindray --host <lab PC> --live-lab --hang --interval 10
   ```
   The app should show the simulator's address as the peer, bytes 0, frames 0.
   If this fails, nothing else matters: it is the port, the firewall, or the
   analyzer row being disabled.

2. **A result with no order — the claim queue.**
   ```bash
   java -jar analyzer-sim.jar mindray --host <lab PC> --live-lab --id NOT-AN-ORDER
   ```
   The simulator must print `ACK after Nms: MSA|AA|…`. The app must show one
   unmatched row in the claim queue, reason *no order matches 'NOT-AN-ORDER'*.
   An ACK that never comes is the fault that makes a real analyzer stop sending.

3. **A result on a real order.** Register a patient and a CBC in BNM Lab, note
   the accession, then:
   ```bash
   java -jar analyzer-sim.jar mindray --host <lab PC> --live-lab --id ACC-S1-00042 --image
   ```
   The result must land on that order, in the **catalog's** units — HGB around
   14 (not 140), WBC around 7000 (not 7.1) — with three histograms and the
   scattergram on the report. This is also the message big enough to find a
   buffer bug: it is ~40 KB.

4. **Nothing keyed on the analyzer.**
   ```bash
   java -jar analyzer-sim.jar mindray --host <lab PC> --live-lab --no-specimen
   ```
   Another claim-queue row, reason *no specimen id keyed on the analyzer*. This
   is what a real lab sees all day when the tube is run before the barcode is
   scanned, so the operator has to have been shown the one-tap claim.

5. **Half a frame.**
   ```bash
   java -jar analyzer-sim.jar mindray --host <lab PC> --live-lab --truncated
   ```
   The traffic log should say *Connection closed with N unframed bytes
   (ignored)* and the frame counter must not move. Nothing should be filed.

6. **A dribbled frame.**
   ```bash
   java -jar analyzer-sim.jar mindray --host <lab PC> --live-lab --id ACC-S1-00043 --slow-chunks 40 --image
   ```
   A slow USB-serial adapter or a fragmenting network delivers a long message in
   hundreds of pieces. It must still arrive as one result.

7. **A critical, end to end.**
   ```bash
   java -jar analyzer-sim.jar mindray --host <lab PC> --live-lab --id ACC-S1-00044 --profile critical
   ```
   The result must reach the dashboard's critical call-out list. Labs are
   required to phone criticals; if this does not appear, the catalog's critical
   ranges are not set.

8. **A small run**, to watch it work at pace:
   ```bash
   java -jar analyzer-sim.jar mindray --host <lab PC> --live-lab --id 'ACC-S1-000{1..5}' --interval 3
   ```

For a Mispa install, the same list with `mispa --serial COM3` in place of
`mindray --host …`, **except steps 1 and 5**: `--hang` and `--truncated` need a
connection and are refused on a cable (see the faults table above). Step 1
becomes "does the port open at all" — the simulator names the adapter it found,
and if it cannot open the port, that is the cable or a terminal program holding
it. To rehearse truncation on a Mispa install, point it at the app over TCP
instead (`mispa --host <lab PC> --port 5501 --truncated`, with the instrument
row set to TCP for the duration).

---

## Keeping it honest

A simulator nobody checks drifts, and then an engineer commissions a lab
against a frame no analyzer would ever send. Three layers stop that:

- `./gradlew :analyzer-sim:test` — frame structure, value consistency, curve
  shape, the CLI, and what each fault actually puts on the wire.
- `composeApp/src/desktopTest/.../AnalyzerSimFidelityTest.kt` — every profile on
  both analyzers, built by this tool and read back by the **real** drivers
  (`MispaCountX.parse`, `MindrayBc5x.parse`). Values, units, ids, patient
  fields, the QC flag and the histograms must come back equal to what the
  simulator intended — not merely parse. Each fault must fail in exactly the
  way it claims.
- `composeApp/src/desktopTest/.../AnalyzerSimTcpE2ETest.kt` — a real
  `InstrumentEngine` on a real port over a real database, with this tool's own
  TCP client dialling it: ACK, claim queue, unit conversion, truncation.

There is also a manual bench for the packaged jar (`AnalyzerSimBench`), opt-in
via environment variables — see its KDoc.

The two things most worth knowing if you change this tool:

1. **`MISPA_PARAM_ORDER` is a copy** of the driver's `PARAM_ORDER` and the
   fidelity test is what keeps them equal. Reorder one and the other must move.
2. The **histogram byte layout** is chosen to match what `decodeHistogram`
   accepts, not the other way round.

## Known liberties

Honest about what is imitated rather than reproduced:

- **Alert codes.** The driver only reads the alert's *name* (`IS` rows whose
  value is `T`), so the codes used here sit inside the documented 12011..12052
  band but are not pinned to one firmware's table.
- **QC on the Mispa.** That format has no processing-id field, so `--qc` has
  nothing to set and a QC material arrives as another sample — BNM Lab will
  file it as a patient result, correctly. A `mispa --qc` run therefore opens
  with a warning saying exactly that, and the transcript does not mark the
  sample `QC`; the menu's QC rehearsal is relabelled *(Mindray only)*. QC
  suppression can only be exercised on the Mindray link.
- **Histogram channel count.** Real BC-5130 curves are 256 channels; this tool
  sends 128, in the byte layout the app decodes. The shape, the discriminators
  and the whole transport path are the real thing; the resolution is half.
- **The scattergram** is a synthetic five-cluster plot, not an optical
  measurement. It is a genuine 24-bit BMP of the right size, which is what the
  transport and the report need to survive.
