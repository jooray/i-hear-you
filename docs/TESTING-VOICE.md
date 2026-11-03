# Voice notes: quick test guide

About 15 minutes. Wear the aids and make them the phone's active audio device (play something
through them first). Debug build installed.

## 1. Setup (once)

1. Open I Hear You → **Voice** tab → **Allow microphone**. Leave the screen open a second; the
   orange "open the app once" notice must not be there.
2. On GrapheneOS: Android settings → Apps → I Hear You → turn **Network** on (needed only for
   webhook / White Noise).
3. **Buttons** tab → left aid → **Long press** → **Voice note**.

## 2. Save only (default)

1. Lock the phone. Long-press the left aid: rising cue. Talk ~10 s.
2. Short-press either aid: falling cue.
3. Expect: `Documents/I Hear You/<date> voice note.m4a`, playable. Voice tab → Recent notes shows
   it as SAVED. The status line during recording said HEARING-AID MICROPHONE (if it said
   BLUETOOTH/PHONE, the aids were not the active audio device).
4. Music: while recording it may sound flatter (call mode); it must return to normal after.

## 3. Per-press destinations

Voice tab → **Ending a note**: right aid short press → Share. Record (left long), end with a right
short press while locked → falling cue, then a "ready to share" notification; unlock, tap it,
share sheet opens with the audio. Repeat unlocked with the app open: the share sheet opens
directly.

## 4. Transcription

Install Offline Speech to Text with a model. Voice tab → Transcription → **Audio + text**. Record
a sentence, end with a left short press (save only). Expect a `.txt` next to the `.m4a` within
~10–40 s; Recent notes shows TRANSCRIPT. Try **Text** too. A 25-minute rant: set Longest note →
25 min; at 24 min a double cue warns, at 25 it ends by itself.

## 5. Webhook

Advanced → Webhook → URL (e.g. `https://webhook.site/<id>` or n8n/Home Assistant), Save. Map a
press to Webhook, record, end with it: three high notes = sent. The request has `metadata`,
`transcript`, `audio` parts. Break the URL and retry: long low note, notification with Retry;
fix the URL, Retry from Recent notes.

## 6. White Noise + replies

1. Advanced → White Noise → **Use White Noise** → Connected, npub shown. Copy it.
2. In White Noise, create a group with your agent (or add the npub to an existing one). Allow the
   npub in Hermes (`--allow-welcomer` and `--allow-user`) if your connector gates them.
3. Back in the app the invite appears → **Accept** (it becomes the voice-note group).
4. Map a press to White Noise, record, end with it: three high notes; the note appears in the
   group as an audio attachment (plus the transcript as caption with Audio + text).
5. Replies: mode **After a note**. When the agent answers within 15 min, a soft note plays and
   the reply is read (or its voice note played) into the aids, music paused. A short press stops
   it.

## 7. Edge cases worth a minute

- Reboot the phone, don't open the app, long-press: failure cue + notification "open I Hear You
  once". Open the app, try again: works.
- Kill the app mid-recording (`adb shell am force-stop app.ihearyou`), reopen: the partial note is
  recovered as SAVED.
- Aids and other Bluetooth headphones both connected, music playing on the headphones: start a note. Expected (new): the
  route moves to the aids, status shows HEARING-AID MICROPHONE; the log line
  `voice: recording … route <aid>: confirmed in N ms` shows how long the switch took. If it
  still shows BLUETOOTH · <headphones>, the aids are not offered as a communication device then.
- Turn on the microphone privacy toggle (quick settings), start a note: after ~5 s the warning
  cue and a "recording silence" notification; turn it off: the notification clears.

Logs: Setup tab → log, or `adb shell run-as app.ihearyou cat files/events.log`.
