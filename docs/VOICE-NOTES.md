# Voice notes

Record a voice note with a press on the aid, end it with another press, and have it saved and
optionally sent: to a webhook, a White Noise group (for example one with an AI agent) or any app
through the share sheet. Replies from the White Noise group can be played back into the aids.

## Flow

1. **Start.** A gesture mapped to *Voice note* (Buttons screen) starts recording. The input is
   chosen from Android's communication devices, in order: the aids (LE Audio, matched by
   Bluetooth address), any other LE Audio headset, a classic SCO headset; with none, the phone's
   microphone. That list can hold the aids even while other headphones are the active audio
   device, so making the aids the communication device (`setCommunicationDevice`) should move
   the call-mode route, and the microphone, to them (to be confirmed on the phone). The switch is
   asynchronous and reconfigures the LE Audio link into a call-like bidirectional mode, during
   which audio drops out: the recorder waits until Android reports the new communication device
   (at most 3 s; then it records anyway) plus 500 ms to settle, opens the microphone, and only
   then a recorded voice says "go ahead" in the app's language
   (`res/raw-<lang>/voice_go_ahead.ogg`; the rising tone if it can't play). It plays while already
   recording, so you can talk over it. The voice clips start with 2 s of silence, so the stream
   is already open while the aids' link settles and the first word isn't clipped. On LE Audio
   the recorder also plays 3 s of silence on the call route as the microphone opens: Pixel's
   audio HAL skips its "BLE restart" when an LE input starts with no LE output active, and the
   input then delivers only digital zeros (seen in OSTT, which plays nothing there). A press during
   that wait ends the note as soon as it has started. Whatever is playing (any media session in
   the playing state) is paused at the press and resumed 1.5 s after the stop confirmation, if it
   is still paused and nothing else has started meanwhile.
2. **Record.** 16 kHz mono PCM16 into `filesDir/voice/<id>/audio.wav` with `VOICE_COMMUNICATION`
   (which follows the communication device; `MIC` for the phone), on an urgent-audio thread. The
   communication device is cleared when recording ends. While recording:
   - five seconds of exact digital zeros (privacy toggle, muted aids, a call or another app holding
     the input) play the warning cue once and post a notification; recording continues, and the
     notification clears when sound returns. Android's own "client silenced" state does the same
     at once;
   - a read error, or no data for about a second, ends the note as a saved recording with the
     failure cue and a notification;
   - every input route change, the route wait result and silencing go into the event log.
3. **End.** While recording, *every* press on either aid ends the note, whatever it is mapped to;
   the aid's own program/volume step is undone as for any mapped press. Which aid and gesture
   ended it selects the destination (Voice screen, *Ending a note*). The length limit (2, 5, 10,
   25 or 60 min) ends it like a short press on the starting aid; one minute before, a warning cue
   plays. The ongoing notification has *Stop and save*. There is no cancel: a misclick costs a
   file, never a lost rant. After the stop cue the same voice says what the press chose, in as
   few words as possible: "Saved." / "Saved, transcribing." / "Sending to White Noise." /
   "Transcribing and sending to White Noise." (and webhook, share), so a press read as another
   gesture is noticed at once (`voice_done_<destination>[_tr].ogg`).
4. **Save.** Always first: AAC-LC 16 kHz mono 32 kbps `.m4a` (`audio/mp4`, the shape White Noise
   records its own voice notes in) copied to `Documents/I Hear You/` or a folder the user picks
   (SAF), named `yyyy-MM-dd HH-mm-ss voice note.m4a`.
5. **Transcribe** (optional: *Audio*, *Audio + text*, *Text*). The WAV's PCM is streamed to an
   on-device recognition service through Android 13's caller-supplied audio
   (`RecognizerIntent.EXTRA_AUDIO_SOURCE`), headless, with the screen locked. Offline Speech to
   Text supports this contract; the writer is closed after the last frame and OSTT answers once in
   `onResults`. The transcript is saved next to the audio as `.txt`. *Text* sends only the
   transcript and falls back to audio when transcription fails. With *Save only*, the transcript is also copied to the clipboard. OSTT runs one transcription at a
   time: while its keyboard dictates it refuses (`ERROR_AUDIO`), and a newer request from another
   app can retire ours without an answer. Those (busy errors and the timeout) are retried after
   5, 15, 30, 60 s, then every 2 min, about 10 min in all; later notes queue behind.
   Notes are processed one at a time; an ongoing silent notification shows the step (saving,
   transcribing, waiting for the transcription app, sending to …) and how many notes wait.
6. **Deliver** to the chosen destination. A falling cue means saved; three high notes mean sent;
   a long low note means something failed (the saved copy stays, with *Retry* in the notification
   and in *Recent notes*).

The process can die mid-note; on the next start, unfinished notes are recovered (WAV header
repaired) and finished as saved recordings.

## Destinations

| Destination | What happens | Notes |
|---|---|---|
| Save only (default) | nothing beyond saving | |
| Share | Android share sheet with the `.m4a` (and transcript as text) | Android blocks activity starts from the background and on the lock screen, so then a "ready to share" notification opens it |
| Webhook | `POST` multipart/form-data | see below |
| White Noise | encrypted upload + message in the chosen group | own identity, see below |

### Webhook format

`multipart/form-data`, optional `Authorization` header (value as entered, e.g. `Bearer …`):

- `metadata` — `application/json`:
  `{"app":"i-hear-you","id":"…","recorded_at":"2026-10-28T00:39:55.542Z","duration_seconds":6.8,"microphone":"hearing_aid|bluetooth|phone","content":"audio|audio+text|text"}`
- `transcript` — `text/plain; charset=utf-8`, when there is one and the mode includes text
- `audio` — `audio/mp4`, the `.m4a`, unless the mode is *Text* and a transcript exists

Any 2xx is success. Only `https://` URLs are accepted.

### White Noise

MarmotKit 0.14.0 (MDK's UniFFI bindings, `tools/marmotkit/fetch.sh`) runs one runtime in the app
process, with White Noise's deployment preset (its media servers, recovery relays, key-package
preferences). The app creates its own identity on first use: relay lists (10002, 10050 inbox) and
a key package are published to `wss://relay.damus.io`, `wss://relay.primal.net`, `wss://nos.lol`.
The secret key is AES-GCM encrypted under an Android Keystore key (no backup).

Key backup (same White Noise section): *Show nsec* reveals the identity's private key, plain, for
a password manager (copied as a sensitive clip). *Import nsec* replaces the identity with one
(after a confirmation). A key restores the npub, so contacts and agent allowlists keep working,
but not group membership: that is MLS state on the device, so after an import the groups have to
invite the npub again, and messages from before can't be read.

Setup: Voice screen → Advanced → White Noise → *Use White Noise*. Copy the npub, add it to a group
in White Noise (or a dedicated group with your agent), accept the invite in the app, pick the
group. Agents behind MDK's connector (Hermes etc.) must allow the app's npub with
`--allow-welcomer` / `--allow-user` if they gate invites and senders.

Voice notes go out with `uploadMediaFiles(send = true)`: one kind-9 message with the encrypted
`.m4a` (`audio/mp4`) and the transcript as caption when the mode includes text; *Text* mode sends a
plain text message.

### Replies in the aids

Messages from others in the chosen group are played into the aids: audio attachments as they
are, text through the phone's text-to-speech engine (markdown stripped, language guessed among
the app's languages from the text, falling back to the phone's). Other audio pauses for the
length of a reply (transient focus). A short press during a reply stops it. Modes: *Off*,
*After a note* (15 minutes after you sent one; default), *Always*. Messages older than the
runtime's start are never read out.

## Permissions

- `RECORD_AUDIO`, `FOREGROUND_SERVICE_MICROPHONE`: the service holds the `microphone` foreground
  type next to `connectedDevice`. Android 14 grants it only while the app is visible, so after a
  reboot (service started by `BOOT_COMPLETED`) voice notes start working once the app has been
  opened; a press before that plays the failure cue and posts a notification saying so.
- `INTERNET`: webhook and White Noise only. On GrapheneOS the per-app *Network* toggle starts off
  for an update that newly requests it; the Voice screen shows a notice with a settings shortcut.
- `MODIFY_AUDIO_SETTINGS`: `setCommunicationDevice`.

## Debug driving (debug builds only)

`app.ihearyou/.debug.DebugReceiver` accepts broadcasts so flows can be tested without pressing the
aids:

```sh
R="adb shell am broadcast -n app.ihearyou/.debug.DebugReceiver"
$R -a app.ihearyou.debug.GESTURE --es side left --es gesture LONG     # simulated press
$R -a app.ihearyou.debug.VOICE_START / VOICE_STOP
$R -a app.ihearyou.debug.VOICE_SET --es key transcription --es value AUDIO_AND_TEXT
$R -a app.ihearyou.debug.VOICE_IMPORT --es path files/test.wav --es dest WEBHOOK
$R -a app.ihearyou.debug.TRANSCRIBE --es path files/test.wav
adb shell run-as app.ihearyou cat files/events.log                    # persistent event log
```

## Future ideas (not built)

- **Live captions from the aids' microphones.** Stream the aids' input into a streaming
  recogniser and show (or read) captions of what people around say. For hearing-aid users this
  may be the most valuable feature of all. Needs a low-latency streaming model and a caption
  surface (overlay or lock-screen notification).
- **Live translation (subtitles).** The same stream through a local translation model, or an STT
  model that translates directly (Whisper's translate task, SeamlessM4T, Canary's AST); show
  translated subtitles, optionally speak them into the aids.
- **Missed-it replay.** A rolling buffer of the last 30–60 s from the aids' microphones, kept only
  in memory, so a press can replay or transcribe what was just said. Costs battery and keeps the
  aids in call mode; would need a careful design.
- **Voice-note intent for White Noise.** MDK issue #1252 tracks marking an attachment as a voice
  note; once it lands, set it so White Noise shows our notes with its voice-note player.
