---
version: 1
slug: "app-src-main-java-app-ihearyou-mainactivity-kt"
primary_target: "app/src/main/java/app/ihearyou/MainActivity.kt"
related_targets: []
---

# Surface: the app (single native surface, Android)

Mode: **Operate**. The wearer completes a task — check the aids, change a level, recall a program,
map a gesture — in bright light, one-handed, sometimes at night, sometimes with a stranger's pair.
Every screen is a working instrument; expression must never obscure state or affordance.

Primary target: the `:app` Compose surface (`app/src/main/java/app/ihearyou`).

## Audience and job

GN wearers first (a receiver plus a CROS or a second receiver, daily, phone in pocket); sideloading
strangers second, often older, arriving with no context and a different GN pair. The job on entry
is a two-second answer: are both ears live, what is each doing, how loud is each — then one
deliberate change, read back by the aid.

## What must remain true

No account, no cloud, network only for voice-note destinations the user sets up, and say so where a cloud would be expected. Never
silently change a setting: commit on release, show the read-back, never a battery percentage, never
a firmware write. Failures are copy with the exact code and the fix. Light and dark are both
first-class; state is never colour-only; 48 dp targets; font scale to 200 %.

## Direction contract

THESIS: The app is a calibrated instrument, not a gadget: two aids read as two metered channels on
one sealed panel, and the category default — a stack of white status cards with battery glyphs and
an account nag — is refused outright.

OWN-WORLD: Pale enamel grey-green panel, near-black ink, one vermilion seal; hairline rules, 2 dp
corners, recessed wells, screwed plates, fader slots, printed tick scales, drum-counter figures in
Barlow Condensed, engraved caps in Barlow. No pills, no gradients, no glass, no floating cards.

STORY: The wearer sees both ears live, both levels, and which program is recalled, then turns one
fader or presses one recall key — and watches the aid confirm it. The stranger sees a sealed device
that talks to nothing but the aids, and can pair one from the same screen.

FIRST VIEWPORT: Full-height panel. Header: wordmark, live lamp, NO NETWORK stamp. Body: two tall
channel plates side by side — L (CROS, drawn feeding into R) and R — each with side badge, model,
lamp, a level window, a vertical fader with tick scale, and −/MUTE/+ keys at thumb height, then
battery cells and signal field. Below: the program recall strip, numbered keys, active one inked.
Footer ruler: stream state and read time. Primary action, the fader, sits in the lower half.

FORM: Grounded candidate 6 of 7, "the sealed utility meter" — a domestic measuring instrument with
a glass window, printed scale, fader and tamper seal. Seed key `ba483f60`.

FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review, the
verdict, DESIGN.md, and every shipping raster carrying its provenance.

## Direction round record

Grounded list, resonance order: 1 audiometer front panel · 2 hearing-aid shell as technical object ·
3 studio channel strip · 4 car instrument cluster · 5 pharmacy dosage label · **6 sealed utility
meter** · 7 tactile accessibility signage. The roll assigned 6.

Challenger verdicts (fused, weighed on audience identification and product clarity vs the
assignment): ikeda-datamatics — declined; keeps the discipline of a two-tone field where the signal
is *rendered*, not described. Monochrome product marketing — declined; keeps claim-paired-with-proof
(every value shows its read-back). Labanotation — competitive; bilateral symmetry across a centreline
is the product's own structure. Saville catalogue sleeve — declined; keeps the colour-coded index
strip (programs as numbered, keyed entries). Vertical video feed — declined; keeps one-thing-per-
viewport with no scroll for the primary act. Lexicon shoulder and plate — competitive; guide-word
wayfinding and a print page as a working surface.

Raises carried into the build, each named for its donor: RSSI and stream state drawn as a real field
(ikeda); "READ 14:22" and a confirmation word beside every write (monochrome marketing); program keys
as a numbered, colour-keyed strip with the active entry decoded (saville); the Now screen at one
viewport with no scroll at default scale (vertical feed).

## Unresolved

Equaliser editing, Auracast,
accessory control and a raw handle console remain out of scope; the aid detail screen is the
"advanced" surface where raw bytes are allowed.
