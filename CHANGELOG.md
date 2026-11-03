# Changelog

## 0.3.0 (unreleased)

### Added

- Sound buttons on the program card: Noise filter and Speech clarity (and, depending on the
  program, Hear everyone, Bass boost or Treble boost). One can be on at a time; pressing it again
  restores the sound from before. Bass, middle and treble sliders (−6 to +6 dB) with a reset to
  the audiologist's settings. On top-tier aids the buttons also set noise reduction, speech focus
  and wind noise. GN protocol 5 only; every change is read back from the aid, and nothing is
  written while an aid is charging. Works on GN protocols 4 and 5.

### Fixed

- Bottom sheets (Buttons action choice, voice-note destination, sound) no longer put their last
  row under the navigation bar, and close on a swipe down.
- The mute key's label is no longer cut off on narrow screens.
- With an aid in the charger the Now screen says so, instead of claiming only one program is
  fitted (an aid in the charger offers only its first program).

## 0.2.2 (2026-11-03)

### Fixed

- Linked hearing aids: when a press on one aid also changes the other one (aids set to change
  volume or program on both ears), the app now restores both aids, not only the one that was
  pressed. A mapped volume or program step is applied to both, as the aids would do themselves.

### Changed

- Every transcript is copied to the clipboard, whatever the destination (before: only for "Save").

## 0.2.1 (2026-11-03)

### Fixed

- A voice note no longer records silence when the hearing aids' microphone fails. If the aids
  deliver no sound within 8 seconds, or go silent mid-recording (for example an aid switched off
  by holding its button too long), the recording moves to the phone's microphone and continues in
  the same file. Three falling notes tell you it happened. Before, the app only warned and kept
  recording silence.
- While the aids' microphone is still coming up (it takes 2 to 3.5 seconds on LE Audio), an empty
  read no longer ends the recording as "no audio from the microphone".

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
