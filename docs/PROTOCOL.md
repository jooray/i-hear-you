# GN hearing-aid protocol: what the app sends and receives

This is an interoperability description of the Bluetooth LE traffic between I-Hear-You and a GN
hearing aid, as implemented in `protocol/` and `app/src/main/java/app/ihearyou/ble/`. It covers
only what the app uses. Key material lives in code (`Crypto.kt`), not here.

## GATT services

GN control service `0000fefe-0000-1000-8000-00805f9b34fb`:

| characteristic | UUID | use |
|---|---|---|
| GN Version | `97C1C193-EA53-4312-9BD9-E52207D5E03D` | read, 2 bytes: protocolVersion, handleListVersion |
| Security Capabilities | `12257119-DDCB-4A12-9A08-1CD4DF7921BB` | write `04 00 00 00 00`, then read 5 bytes |
| Trusted App Challenge | `ADD69BFC-EDC7-40A4-BA5E-5F0107C3B3AC` | read the 42-byte challenge, write the auth packet |
| HI Public Key | `98E3949E-D4DD-421C-87B2-5A5DDC1AC26F` | read, P-256 point X‖Y (64 bytes) |
| GN Command | `1959A468-3234-4C18-9E78-8DAF8D9DBF61` | write, encrypted handle commands |
| GN Notify | `8B51A2CA-5BED-418B-B54B-22FE666AADD2` | notify, replies and events |
| Feature Support | `650C3A00-CB6D-467D-A20B-3544F189D8AF` | read, unencrypted; tells a CROS transmitter apart |
| Fit Protocol Config | `E09369EC-150B-40B0-ABD5-841CA383D7FA` | read, 1 byte; `& 3 != 0` means packets carry a MIC |

Second GN service `7D74F4BD-C74A-4431-862C-CCE884371592` (unencrypted): battery
`24E1DFF3-…` (1–10) and side `8D17AC2F-…` (0 left, 1 right). Model, firmware and serial come
from the standard Device Information service (`180A`).

## Handle model

Everything else is a numbered *handle* (a property) reached through GN Command / GN Notify.
Commands are encrypted; the plaintext is:

| command | plaintext | reply on GN Notify |
|---|---|---|
| set notify vector | `01` + 16-byte bit vector, bit `handle - 1` | none |
| write handle | `03` + handle + data | none, or an error frame |
| read handle | `04` + handle | type 3 |

A frame on GN Notify is `[type][body]`:

| type | body | encrypted |
|---|---|---|
| 1 | auth result: `[code][payload]`, payload `[sharedIndex]"HI says hi"` when code is 0 | payload only |
| 2 | notify vector | yes |
| 3, 4 | repeated `[handle][len][data…]` (read reply, change notification) | yes |
| 5 | blob chunk `[handle][…][data…]` (long reads) | yes |
| 6, 7 | repeated `[handle][size][flags][uuid16]` (handle discovery, 7 = last) | yes |
| 8 | repeated `[source][handle][errorCode]` | no |
| 9 | MIC error | no |

Every encrypted frame must be decrypted in order, duplicates included, or the counters drift and
all later frames fail the integrity check.

### Handles the app uses

Shared by all generations:

| handle | property | app use |
|---|---|---|
| 1 | battery level (1–10) | read, notify |
| 2 | side | read |
| 5 | microphone attenuation `[program, attenuation 0–255]` | read, notify, **write** (volume, mute) |
| 7 | available programs (4-byte mask) | read |
| 8 | active program | read, notify, **write** |
| 10 | program name (UTF-8, zero padded) | read after selecting with 12 |
| 12 | program name selector | **write** |
| 17 | program environment IDs (u32 LE per program) | read |
| 21 | stream status | read, notify |
| 31 | stream type | read, notify |

A program mask whose first two bytes are zero is stored bit-reversed per byte and byte-reversed.

Per generation, chosen from GN Version (protocolVersion; for 5, handleListVersion 0 = 5.0,
otherwise 5.1):

| generation | push button | HI state | tap config | standard features (36) | start DFU |
|---|---|---|---|---|---|
| GN protocol 1 | none | none | none | no | 53 |
| GN protocol 2 | 57 | 54 | none | no | 53 |
| GN protocol 3 | 57 | 54 | none | no | 53 |
| GN protocol 4 | 53 | 50 | none | yes | 49 |
| GN protocol 5.0 | 53 | 50 | 66 | yes | 49 |
| GN protocol 5.1 | 53 | 50 | 66 | yes | 49 |

- Push button: `01 …` press, `00 …` release, as notifications.
- Tap config (u16): bit 0 program tap, bit 1 call tap, bits 8/9 whether each is allowed. The app
  changes only bits 0/1 with read-modify-write.
- Standard features (36): 9 bytes per program slot, each byte a feature-value id, `FF` unused.
  The app only reads it.

The app writes only handles 5, 8, 12 and the tap config handle. An unknown generation is kept
read-only. The start-DFU handle and everything related to firmware or fitting are never written.

## Encryption

Each connection gets two 32-byte session keys (16-byte AES key + 16-byte initial counter), one per
direction. Payloads are AES-128-CTR; the counter is a 128-bit block whose last four bytes are
incremented big-endian per block. When Fit Protocol Config asks for it, each packet also carries
a 4-byte MIC (CBC-MAC over the plaintext, masked with one keystream block). The session keys are
derived with SHA-256 from an application key (selected by the security version), the challenge
(HI ID 20 bytes ‖ nonce 16 bytes ‖ BT address 6 bytes), on the first boot auth an ephemeral ECDH
P-256 shared secret, and on later auths the stored shared key. See `deriveKeys` in `Crypto.kt`.

Security Capabilities read: `version u16 LE, appKeyIndex u16 LE, flags u8`; `flags & 3` is the
bond method (1 boot, 2 passcode, 3 DFU). The app supports the boot bond only.

## Auth packet

Written to Trusted App Challenge:

```
00 00 04 00 <connType> <sharedIndex> ‖ Encrypt("APP says hi ") ‖ [our P-256 public key, 64 bytes]
```

Connection types with MIC: 9 boot start, 10 boot finalize, 12 session; without MIC: 1, 2, 4. The
public key is sent only with the boot start. The aid answers with a type 1 frame.

## Pairing (boot bond)

1. The aid is paired with Android first (link-layer bond).
2. **First auth** (type 9/1, ECDH): the aid answers code 21, "waits for reboot".
3. **Restart the aid** (charger for a few seconds, or power cycle).
4. **Final auth** (type 10/2) on a fresh connection with a fresh challenge. Code 0 returns the
   shared index; the app stores the derived shared key and index (sealed with an Android
   Keystore key). Code 21 again means the aid has not restarted yet; the app retries up to five
   times, 2 to 16 s apart.

A wrong final auth invalidates the pending bond, so the first attempt counts.

## Session bond

Every later connection does a session auth (type 12/4) with the stored key and index; it
completes in about a second. Then the app sets the notify vector and reads the handles above.

## Result codes

| code | meaning | what the app tells the user |
|---|---|---|
| 0 | ok | |
| 3 | auth blocked | power-cycle the aid and wait |
| 13 | credentials rejected | reset the bond and pair again |
| 19 | auth not accepted | reset the bond and pair again |
| 20 | shared index not supported | reset the bond and pair again |
| 21 | waits for reboot | restart the aid |

A handle read answered with ATT error `0x82` (resource busy) is retried.

## Two apps

The aid accepts one trusted app at a time. When another app authenticates on the same link, frames
start failing the integrity check; the app then stops and offers to take the aids back.
