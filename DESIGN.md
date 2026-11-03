---
name: I-Hear-You
description: A two-channel instrument panel for a pair of hearing aids — offline, account-free, read-back honest.
colors:
  panel-ground: "#E4E7DB"
  panel-plate: "#EDEFE5"
  panel-plate-alt: "#DFE2D5"
  panel-recess: "#D2D6C7"
  ink: "#14170F"
  ink-muted: "#4C5247"
  rule: "#B0B6A6"
  rule-strong: "#8C9383"
  seal: "#B23A1E"
  on-seal: "#F7F4E7"
  lamp-live: "#256B45"
  lamp-warn: "#7A5200"
  fault: "#A3221A"
  night-ground: "#15180F"
  night-plate: "#1C2016"
  night-ink: "#ECE9DA"
  night-ink-muted: "#A9AE9F"
  night-rule: "#3B4134"
  night-seal: "#E8633F"
typography:
  display:
    fontFamily: "Barlow Condensed, sans-serif"
    fontSize: "34sp"
    fontWeight: 700
    lineHeight: 1
    letterSpacing: "0"
  wordmark:
    fontFamily: "Barlow Condensed, sans-serif"
    fontSize: "22sp"
    fontWeight: 700
    lineHeight: 1.1
    letterSpacing: "0.06em"
  headline:
    fontFamily: "Barlow, sans-serif"
    fontSize: "20sp"
    fontWeight: 600
    lineHeight: 1.2
  title:
    fontFamily: "Barlow, sans-serif"
    fontSize: "16sp"
    fontWeight: 600
    lineHeight: 1.25
  body:
    fontFamily: "Barlow, sans-serif"
    fontSize: "15sp"
    fontWeight: 400
    lineHeight: 1.4
  label:
    fontFamily: "Barlow, sans-serif"
    fontSize: "10sp"
    fontWeight: 600
    lineHeight: 1.4
    letterSpacing: "0.13em"
  data:
    fontFamily: "Barlow Condensed, sans-serif"
    fontSize: "12sp"
    fontWeight: 600
    lineHeight: 1.25
    fontFeature: "tnum"
rounded:
  xs: "2px"
  sm: "2px"
  md: "3px"
  lg: "4px"
spacing:
  hair: "1px"
  xs: "4px"
  sm: "8px"
  md: "12px"
  lg: "16px"
  xl: "24px"
  xxl: "32px"
components:
  key-neutral:
    backgroundColor: "{colors.panel-plate}"
    textColor: "{colors.ink}"
    typography: "{typography.label}"
    rounded: "{rounded.xs}"
    padding: "14px"
    height: "48px"
  key-primary:
    backgroundColor: "{colors.seal}"
    textColor: "{colors.on-seal}"
    typography: "{typography.label}"
    rounded: "{rounded.xs}"
    padding: "14px"
    height: "48px"
  key-strong:
    backgroundColor: "{colors.ink}"
    textColor: "{colors.panel-plate}"
    typography: "{typography.label}"
    rounded: "{rounded.xs}"
    padding: "14px"
    height: "48px"
  plate:
    backgroundColor: "{colors.panel-plate}"
    textColor: "{colors.ink}"
    rounded: "{rounded.xs}"
    padding: "14px"
  level-window:
    backgroundColor: "{colors.panel-recess}"
    textColor: "{colors.ink}"
    typography: "{typography.display}"
    rounded: "{rounded.xs}"
    padding: "10px"
    height: "52px"
  program-key:
    backgroundColor: "{colors.panel-plate-alt}"
    textColor: "{colors.ink-muted}"
    typography: "{typography.label}"
    rounded: "{rounded.xs}"
    padding: "7px"
    width: "104px"
---

# Design System: I-Hear-You

## Overview

**Creative North Star: "The Sealed Meter"**

The interface is a calibrated domestic instrument: an enamel panel with a glass window, printed
scale, a fader per ear, engraved labels, and one vermilion tamper seal. It comes from the utility
meter and the audiometer — objects whose whole job is to be read correctly, once, by someone standing
in a hallway or sitting in a clinic. The wearer does not admire it; they read it. Two channels, two
lives, no cloud.

The world is flat and printed. There are no shadows, no cards floating on a background, no pills and
no gradients. Depth is a tonal step and a one-pixel rule: ground, plate, recess. Every control is a
machined key with a two-pixel bottom edge, and every value sits in a recessed window behind glass.
The panel is the same object in daylight and in a dark bedroom: light is pale grey-green enamel with
near-black ink; dark is the meter cupboard at night, with bone ink and the seal lit from inside.

Nothing is decorative. The seal is the product's promise made visible (no network, no account); the
lamp is the link; the scale is the aid's own attenuation; the vermilion tick marks the recalled
program. A state is never colour alone — every mark has a word beside it.

**Key Characteristics:**
- Two-channel composition: left and right are two plates on one panel, never a card stack.
- Hairline and tonal depth: 1 px rules, recessed windows, no shadows anywhere.
- Engraved capitals for labels; counter-drum condensed figures for values.
- One accent, used as a seal, an engaged key, or an index mark — never as decoration.
- Every write is shown as sent, then as read back.

## Colors

A pale industrial enamel ground with black printed ink, a single vermilion seal, and three functional
state inks that always travel with a word.

### Primary
- **Seal Vermilion** (#B23A1E): the tamper seal in the header, the engaged mute key, the active
  program's index tick, the fill of the one primary action on a surface, and the "SENT" state.
  It is a sealing-wax red, not an alarm red.
- **Night Seal** (#E8633F): the same role under the dark scheme, lifted to read as a lit lamp.

### Secondary
- **Link Green** (#256B45 / night #74C596): the lamp and the word for a live session, a successful
  bond, and the "Network: None" row. Paired with a filled lamp, text, or both.
- **Warning Ochre** (#7A5200 / night #E0A94A): a non-blocking caution (media access missing).
- **Fault Red** (#A3221A / night #FF9A87): a blocking failure, always in a filled notice band that
  names the code and the fix. Deliberately distinguishable from the seal by form, not hue alone.

### Neutral
- **Panel Ground** (#E4E7DB): the enamel faceplate; the page background in light.
- **Plate** (#EDEFE5): a mounted plate, key face, navigation surface.
- **Plate Alt** (#DFE2D5): a recessed key at rest, an alternating row.
- **Recess** (#D2D6C7): a window, a well, a log slot.
- **Ink** (#14170F): all primary text, the fader cap, an active program key.
- **Ink Muted** (#4C5247): labels, units, secondary copy; never below 4.5:1 on ground.
- **Rule** (#B0B6A6) / **Rule Strong** (#8C9383): hairlines, cell dividers, key borders.
- **Night Ground** (#15180F), **Night Plate** (#1C2016), **Night Ink** (#ECE9DA),
  **Night Ink Muted** (#A9AE9F), **Night Rule** (#3B4134): the dark scheme is a separate set of
  choices, not an inversion.

### Named Rules
**The Seal Rule.** Sealing vermilion appears on at most three things per screen: the tamper stamp, an
engaged control, and the active index. It is the accent's rarity that makes it read as a seal.

**The Two Truths Rule.** No state is carried by colour or shape alone. A live link is a filled lamp
*and* the word LINKED. Low battery is two cells *and* the word LOW. Muted is a filled key *and* the
word MUTE in the window.

## Typography

**Display Font:** Barlow Condensed (with system sans-serif fallback)
**Body Font:** Barlow (with system sans-serif fallback)
**Label/Mono Font:** Barlow for engraved caps; platform monospace for the log only.

**Character:** Barlow is drawn from public-signage and plate lettering — flat-sided figures, sturdy
stems, long descenders. The condensed cut carries the counter-drum readings; the normal cut carries
everything a person reads in a sentence. The pairing reads as printed, not as software.

### Hierarchy
- **Display** (Bold 700, 34 sp, 1.0, tabular figures): the level reading inside a window. It is the
  largest thing on the panel because it is the thing being adjusted.
- **Wordmark** (Bold 700, 22 sp, 0.06 em): "I-HEAR-YOU", once per surface, top left.
- **Headline** (SemiBold 600, 20 sp, 1.2): screen titles only ("Setup", "Left aid").
- **Title** (SemiBold 600, 16 sp): plate row names, gesture names.
- **Body** (Regular 400, 15 sp, 1.4): explanatory copy; measure held by the 16 dp panel margin, never
  full-bleed on expanded width.
- **Label** (SemiBold 600, 10 sp, 0.13 em, upper case): engraved plate labels, units, state words.
  9 sp is the floor and only for a sub-label under a 10 sp label.

### Named Rules
**The Engraved Capitals Rule.** Anything that names a thing on the panel — LEVEL, READ BACK, PROGRAM,
LINK — is set in tracked upper case. Anything a person says out loud — "Pair a new aid", "Restart the
aid" — is set in sentence case at body size.

**The Tabular Figure Rule.** Every number that can change while it is on screen is set with tabular
figures (`tnum`): level, dBm, time, hex. A figure that shifts width is a defect.

## Layout

A single panel with a 16 dp inner margin, divided by hairlines rather than by cards. On compact width
the destinations sit in a bottom switch plate (62 dp + system inset); at 600 dp and above they move
to an 88 dp navigation rail and the content column caps at 900 dp. The Now surface is built to fill
one viewport without scrolling at default scale: wordmark and seal at the top, a link ruler, two
channel plates sharing one baseline grid, a program recall strip, and a pinned three-cell ruler at the
bottom. When the font scale reaches 145 %, or the viewport is short, the channels stack and the whole
panel scrolls; the primary control is never smaller than 176 dp.

The rhythm is 4 / 8 / 12 / 16 / 24 / 32 dp. Space above a plate title is larger than the space below
it. Two channel plates are separated by 12 dp — enough to read as separate instruments, close enough
to read as one pair.

## Elevation & Depth

There are no shadows in this system. Depth is tonal layering plus hairline: `ground` sits behind
`plate`, `recess` sits inside `plate`, and a 1 dp `rule` draws every boundary. A key carries a 2 dp
darker bottom edge and travels 2 dp when pressed; that physical detail is the only "elevation" the
interface allows. A modal sheet is not a floating card: it is a full-width plate that rises from the
bottom edge over a scrim of the ink colour at 46 %.

### Named Rules
**The No Floating Rule.** Nothing hovers. No shadow, no blur, no translucency except the modal scrim.
If a thing needs separation, it gets a rule or a tonal step.

## Shapes

Corners are 2 dp — the radius of a pressed-steel plate, not a rounded app card. Borders are 1 dp.
The recurring silhouettes are the plate (a bordered rectangle with a label interrupting its top
rule), the well (a recessed window with a printed label), the key (a rectangle with a bottom edge),
the slot (a narrow vertical channel with a cap), the cell (a five-part battery ladder), the field
(four ascending signal bars), and the lamp (a filled or ringed circle). No pill, no circle-only
control, no rounded-corner card above 4 dp.

## Components

### Buttons — `Key`
- **Shape:** 2 dp corner, 1 dp border, 2 dp darker bottom edge; 48 dp minimum height.
- **Neutral:** plate face, ink label, rule-strong edge. The default for utility actions.
- **Primary:** seal face, bone label. At most one per surface, for the action the surface exists for.
- **Strong:** ink face, plate label. Used inside a coloured band where a neutral key would disappear.
- **Ghost:** no fill, rule border, ink label. For "Stop", "Open settings".
- **States:** pressed moves the key down 2 dp and darkens the edge; disabled drops the content to 38 %.
- **Type:** dense keys use 12 sp tracked caps; full keys use 14 sp sentence case.

### Plates — `Plate`
- **Style:** 1 dp rule border, 2 dp radius, plate fill, optional corner screws (2 dp radius dots).
- **Title:** a tracked 10 sp label set on the top rule, interrupting it — the plate's engraving.
- **Padding:** 14 × 12 dp, with 8 dp extra top padding when titled.
- **Nesting:** never. A plate may sit beside another plate, never inside one.

### Level window + fader — the signature component
- **Window:** recess fill, 1 dp rule, 10 × 6 dp padding, label left, reading right in 34 sp display.
- **Fader:** a 10 dp recessed channel with a printed scale of 20 minor / 5 major ticks on both sides,
  and a 22 dp machined cap in ink with a 2 dp vermilion index line. The whole track is the touch
  target; vertical drag maps directly to the attenuation byte; the value is sent only on release.
- **Read-back:** the window's second line reads `READ BACK`, `SENT`, `STALE hh:mm`, or `WAITING`.
- **Accessibility:** exposes `ProgressBarRangeInfo` and `setProgress`; ± keys give 8-byte steps; the
  drag is never the only way to change a value.

### Program recall key
- **Style:** 54 dp tall, 104 dp wide, plate-alt fill with a rule border; number in tabular display
  type, name in 9 sp caps below; a 14 × 2 dp index tick on the left.
- **Active:** ink fill, plate text, seal index tick. This is the only place a key inverts to ink.
- **Behaviour:** the strip shows every program the aid reports; more than four scroll horizontally.

### Lamps, meters, stamps
- **Lamp:** 8 dp filled circle with a 1.55× halo at 18 % for live; a 1 dp ring when off; a slow 1.4 s
  breathing only while a link is coming up.
- **Battery:** five cells, filled per the aid's level code (1 critical, 5 low, otherwise full) plus a
  word; never a percentage.
- **Signal:** four ascending bars plus the RSSI in dBm; a rendered field, not a sparkline.
- **Stamp:** 1 dp vermilion border, 9 sp tracked caps, 6 × 3 dp padding — used for NO NETWORK and
  SAMPLE.

### Navigation
- **Compact:** a 62 dp switch plate on the panel ground, three equal destinations, each a 19 dp glyph
  over a 9 sp tracked label; the active destination carries a 30 × 3 dp seal bar above the glyph.
- **Expanded:** an 88 dp rail with the same three destinations, 72 dp tall, the active one carrying a
  3 dp seal bar on its left edge and a plate-alt fill.
- **Back:** the system gesture and a 48 dp chevron key on detail surfaces; the app never traps Back.

## Do's and Don'ts

### Do:
- **Do** read every state twice: a mark plus a word.
- **Do** keep the panel flat: 1 dp rules and tonal steps carry all separation.
- **Do** set changing numbers in tabular figures and keep the level reading the largest value on screen.
- **Do** name the exact byte or code on the aid detail surface, and keep raw hex off the primary one.
- **Do** show `SENT` until the aid confirms, then `READ BACK`; revert the number if the aid disagrees.
- **Do** let a CROS read as a transmitter: no program strip, no tap control, no Sound Enhancer.
- **Do** keep the light and dark schemes as two hand-set palettes; both are first-class review targets.

### Don't:
- **Don't** use pills, rounded cards above 4 dp, gradients, glass, blur, or drop shadows.
- **Don't** use vermilion as an alarm colour; faults use the fault ink inside a named notice band.
- **Don't** use a battery percentage, a fuel gauge, or any invented unit for attenuation.
- **Don't** put cards inside cards, or float a plate on a gradient.
- **Don't** use an icon without a label for anything that changes the aids.
- **Don't** animate a value that has not been confirmed by the aid.
- **Don't** introduce a second accent colour; the world has one seal and three state inks.
