# Changelog

## 0.2.0 (2026-11-03)

First public release.

### What the app does

- Remote for GN hearing aids (tested on a ReSound Nexia pair with a CROS transmitter): volume and
  mute per aid or for both ears, program switching, battery and charging state, signal, stream
  state, tap control and a read-only view of the Sound Enhancer features.
- The aid's own button mapped to phone actions per ear and per gesture (short, long, double
  press): play/pause, next/previous track, phone volume, the aid's program, or a voice note. The
  app can undo the program or volume change the aid makes on its own.
- Voice notes recorded from the aids' microphones with the aid's button, transcribed on the phone
  by Offline Speech to Text and saved, shared, sent to an HTTPS webhook or posted to a White Noise
  (Marmot) group. Spoken cues tell you when to start talking and where the note went.
- Reconnects on its own after a dropped link, including after reboot and app updates.
- Pairing inside the app (GN boot bond, power cycle in the charger, finalize). Bond keys are
  sealed with an Android Keystore key.
- English, Slovak, Czech, German and Spanish. Light, dark and system themes.
- Quick Settings mute tile and notification actions.

### Fixed in this release

- Aids that are already paired with Android are now listed for pairing right away. Before, the
  app relied on a scan, and the scan could not find them because a connected aid stops
  advertising.
- Button mappings and voice-note endings belong to the left or right ear, read from the aid
  itself, instead of to a Bluetooth address. They survive re-pairing and a replaced aid.
- On a fresh install the app found no aids until it was restarted, because it listed them before
  the Bluetooth permission was granted. It now lists them as soon as the permission arrives.
- Buttons size to their label. A full-width button could push its neighbour off screen (the
  nsec "Hide" button) and stretched the warning band on the Now screen down the whole screen.

### Release

- Release APKs are signed with the publisher key (certificate SHA-256
  `79750fb49349ec3729be5a3e825507a15e47c6d987e854ae91d5d50a20b3a24d`).
- The version shown in the app comes from the build (`versionName` 0.2.0, `versionCode` 2).
