# Product

<!-- impeccable:product-schema 1 -->

## Platform

android

## Users

**Primary:** wearers of GN hearing aids (ReSound and sister brands) who want everyday control from
an Android phone without a vendor account. They use it in the moment: phone in hand on the
street, or phone in a pocket with the screen off while the aid's own button drives media. Some are
technical, so a hex value is welcome where it explains something, on the advanced surfaces.

**Secondary:** GN owners who sideload open-source apps from Zapstore, Obtainium or GitHub
Releases. They arrive with no context and an aid pair that may be a different GN protocol
generation. Screens are optimised for a tested pair but must survive a stranger's first run.

Inferred, not confirmed: most of this audience will be hearing-aid wearers who are older than
the typical smartphone power user, sometimes one-handed and in a hurry.

## Product Purpose

An offline-first remote control for GN hearing aids. It shows what the aids are doing (battery,
side, model, firmware, program, stream state, signal) and lets the wearer change what is
changeable over BLE: program, volume/mute and tap control. It adds per-aid mapping of the aid's
physical button to phone actions (play/pause, next/previous, phone volume, the aid's program, a
voice note), with compensation so the aid's own program or volume change is undone.

It needs no account and no cloud, and has no analytics. The `INTERNET` permission exists only
for the optional voice-note destinations the user sets up (an HTTPS webhook, a White Noise
group); controlling the aids never uses the network.

**Success:** bond both aids once, then every everyday adjustment (program, volume, tap, button
mapping) is fast and clear, and the aid's button reliably drives the phone without fighting the
aid's built-in action.

## Positioning

A hearing-aid remote that combines (a) no vendor account, cloud or Play Services, (b) full aid
control with no network use, and (c) phone-side gesture mapping with compensation of the aid's
own action, plus voice notes recorded from the aids' microphones and transcribed on the phone.

The app reports exact auth result codes and their fixes instead of generic failures.

## Operating Context

- **Tested pair:** ReSound Nexia NX560S and NX460S receivers with the Nexia CROS CX160S
  transmitter, GN protocol 5.1.
- **Bonding ritual:** OS-level pairing, then a GN *boot* bond; the aid must be power-cycled and
  the bond finalized on a fresh connection with a fresh challenge. Later connections do a ~1 s
  *session* bond. Each aid is a separate peripheral with its own bond; a wrong finalize
  invalidates the pending bond, so the first attempt is one-shot.
- **Two apps at once:** only one app can hold the aids at a time. If the ReSound Smart 3D app is
  installed the app warns about it, yields when the other app takes the aids over, and offers to
  take them back.
- **The usage scene:** quick checks standing up, one hand, bright outdoor light or a dark bedroom;
  the phone often locked in a pocket while the aid's button is pressed.

## Capabilities and Constraints

**Implemented and verified on hardware:** GN trusted bond (boot + session, ECDH P-256,
AES-128-CTR + MIC), handle command channel, reconnecting supervisor, per-aid device cards,
battery, RSSI signal bars, stream status/type, program list and switch with read-back, volume
slider, mute, tap toggles (calls/programs; only bits 0/1/8/9 are touched and other bits are
preserved by read-modify-write), button bridge (short/long/double → phone action) with
compensation, per-aid mapping UI, voice notes, live log, read-only Sound Enhancer display,
in-app pairing, Quick Settings mute tile, unit tests with protocol test vectors.

**Not implemented:** equalizer editing, Auracast, program rename, favourites, accessory (TV
Streamer/MultiMic) control, firmware update, durable button actions stored in the aid. The app
has four destinations (Now / Buttons / Voice / Setup) plus a per-aid detail screen reached from
Now.

**Technical truth that shapes every screen:**

- Kotlin + Jetpack Compose (Material 3), single activity; `:app` (UI, service) + `:protocol`
  (pure JVM, unit-testable). minSdk 31, target/compileSdk 36, builds with JDK 21 (bytecode
  target 17). No Play Services, Firebase, analytics or crash reporting. A foreground
  `connectedDevice` service owns scan, both GATT links, the bond record and the button event
  stream, and survives screen-off.
- Media actions use a `NotificationListenerService` that resolves the active media
  notification's `PendingIntent`s (user grants access in system settings).
- Volume is an attenuation byte 0–255 (`0xAA` typical default, `0xFF` max); mute writes `0x00`
  and unmute restores the last non-zero value. Battery is a level on a 1–10 scale, shown as a
  five-cell meter and a word, never as a percentage. Firmware version is reported, never written.
- The CROS is detected from the feature-support characteristic. It exposes the same handle map
  but is a transmitter: show a single volume control and hide receiver-only features (programs,
  Sound Enhancer, tap).
- The app writes only microphone attenuation, the active program, the program-name selector and
  the tap configuration. Firmware, DFU and fitting handles are never written; an unknown protocol
  generation keeps the aid read-only.
- BLE reality: MTU 247, handle reads answered with "resource busy" (`0x82`) are retried, and
  every encrypted frame must be decrypted, including duplicates, or the counters desync.
- Failure codes are product copy, not logs: 21 = restart the aid, 13/19/20 = reset the bond,
  3 = blocked (power-cycle and wait).
- **Light and dark are both first-class schemes**: two hand-set palettes (enamel day, meter-cupboard
  night), every screen designed and reviewed in both, system-following by default with a persisted
  System/Light/Dark override applied before the first frame. Dynamic colour is deliberately not
  used: the panel palette is the identity. Dark is never a mechanical inversion. Semantic states are
  never colour-only: icon + text ("L"/"R", "Low"), WCAG AA for body text, 3:1 for large text/icons,
  font scale to 200 %, 48 dp targets.
- Languages: English, Slovak, Czech, German, Spanish.
- Distribution is sideload only: Zapstore, Obtainium, GitHub Releases. No Play Store listing.

**Decisions:**

- **Licence: MIT.** No device serials, MAC addresses or vendor branding in the repository.
- **Identity:** product and package identity are `app.ihearyou`.
- **Brand palette and launcher icon:** an enamel grey-green panel, printed near-black ink, one
  sealing vermilion, and a two-channel meter mark for the launcher. Tokens and rules live in
  `DESIGN.md` and `.impeccable/design.json`.

## Brand Commitments

- **Name: I-Hear-You.** A statement of purpose, not a vendor reference: no ReSound/GN branding
  may appear in the product name, launcher label, or wordmark. The project is not affiliated
  with GN Hearing; ReSound, Beltone, Jabra Enhance, Danalogic and Interton are trademarks of
  their owners.
- **Voice:** plain, factual, technically honest. Errors name the exact code and the fix. No
  marketing adjectives, no gamification, no reassurance where a fact is available. The tone
  respects a wearer who has already had to learn too much about their own ears.
- **The promise is a brand asset:** no account, no cloud, never silently change a setting, every
  write read back and shown. Say it in the UI where a user would expect a cloud, not only in the
  README.
- **Constraint:** no vendor assets, artwork, screenshots or APKs are redistributed.

## Evidence on Hand

- `docs/PROTOCOL.md`: the over-the-air interface the app uses (GATT service, handles, frames,
  pairing).
- `docs/VOICE-NOTES.md`, `docs/TESTING-VOICE.md`: voice notes design and test guide.
- Unit tests with protocol test vectors pass. Current app build is `0.2.0`, versionCode 2.
- **Absences to respect:** no analytics, reviews, press, user quotes or testimonials exist; no
  marketing imagery, photography or illustration exists; no measured performance numbers exist
  beyond observed reconnect timings. Do not fabricate any of these.

## Product Principles

1. **Local is the product.** No account, nothing leaves the phone unless the user sets up a
   voice-note destination, and the interface says so where a cloud would normally be expected.
2. **Never silently change a setting.** Writes are explicit, sliders commit on release, every
   write is read back and shown; optimistic UI reverts when the aid disagrees.
3. **Explain the device.** Battery, side, model, firmware, program, stream state and the raw
   values behind them, in words a wearer can act on; raw hex on the advanced surfaces, not on
   the primary one.
4. **Fail loudly but safely.** Every operation has a timeout and a visible error with a concrete
   next step: restart the aid, wait, reset the bond, retry.
5. **The aid stays in charge of sound; the phone adds control.** Compensation exists so a mapped
   phone action never leaves the aid in a state the wearer did not ask for.

## Accessibility & Inclusion

- The audience has hearing loss. The app is used one-handed, in motion, in bright light and in
  the dark, often with the phone locked in a pocket.
- WCAG AA contrast for body text, ≥ 3:1 for large text and iconography; state is never encoded by
  colour alone; pair icon, text and "L"/"R"/"Low".
- Font scale up to 200 %, minimum 48 dp touch targets, TalkBack labels on every control (no
  symbol-only buttons), and the system high-contrast text setting respected.
