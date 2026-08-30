# RemSound for Android

> **AI disclaimer:** This project was written using Claude, and it is a port of [RemSoundApple](https://github.com/jonathans859/RemSoundApple), which was written the same way. Any usage is at your own risk and nothing can be guaranteed. Pull requests and issues are welcome.

A native receiver for [RemSound](https://github.com/Ednunp/RemSound) — listen to the audio a RemSound sender (Windows) is transmitting, on an Android phone or tablet. Built screen-reader-first, with low-latency playback and the same end-to-end encryption as the Windows app.

It speaks the RemSound v3.x wire protocol (header version 1) and current formats only. Besides receiving, it can **send the microphone** back to your RemSound peers (Opus, encrypted). Recording and legacy/v2.x compatibility are out of scope.

## Features

* **Receives both RemSound codecs** — PCM 48 kHz 24-bit (multi-part frame reassembly) and Opus, including Opus inband-FEC recovery of single lost packets, the same recovery the Windows receiver does.
* **Mandatory end-to-end encryption** — AES-256-GCM with the key derived from a shared password (PBKDF2, identical parameters to Windows). Wrong password = silence, never noise. Password fingerprints tell you *why* a peer is silent ("password does not match" / "peer app needs update").
* **LAN discovery + manual peers** — announces itself on the RemSound discovery port so the Windows side sees it; add a Tailscale IP or the public relay hostname manually for anything broadcast cannot reach. Heartbeats (1 Hz ping/pong with RTT) run on the single canonical UDP port 47830, which also keeps the NAT pinhole open for relay mode.
* **Background playback** — a foreground service keeps reception running when you switch apps or turn the screen off, with a notification that says what the receiver is doing.
* **Connect/disconnect sound cues** (the Windows app's own WAVs) plus spoken announcements when a peer connects or is lost.
* **Latency control** — jitter buffer with target-latency arming, click-trim, and faded underrun concealment. Default 80 ms like Windows; adjustable 5–500 ms, with an optional continuous auto-tune.
* **Microphone sending** — stream the mic to the peers you have selected, encrypted with the same shared password. Opus 48 kHz stereo at 192 kbps, the same encoder settings as the Windows sender. Note: while sending from a Bluetooth headset's own mic, its playback quality drops to the bidirectional link — that is a Bluetooth limitation, not an app bug.
* **Saved profiles** — named snapshots of peers + selection, password, receive/send toggles, microphone, delay, and auto-tune, optionally applied at launch.

## Install

CI builds an **unsigned** release APK plus a debug APK on every push and pull request; both are attached to the workflow run as artifacts. The debug APK installs directly; the release APK has to be signed before a device will accept it. There is no Play Store release and nothing in this repository publishes one.

## Repository layout

| Path | Purpose |
| ---- | ------- |
| `remsoundkit/` | Android library with everything shared: wire protocol, crypto, discovery, heartbeat, jitter buffer, decode pipeline, audio in/out, and the `ReceiverController` façade. Unit-testable with plain JVM tests. |
| `app/` | The app: Compose UI, the foreground service, and the entry point. |
| `app/src/main/res/raw/` | Connect/disconnect cue sounds (from the RemSound repo, MIT). |
| `.github/workflows/ci.yml` | Unit tests, lint, and the two APKs. No Play Store step anywhere. |

Opus comes from [Concentus](https://github.com/lostromb/concentus), a pure-Java port of libopus — no NDK, no prebuilt binaries in this repo, and the codec path is unit-testable on a plain JVM.

## Building

Open the project in Android Studio and run, or from the command line:

```sh
./gradlew :remsoundkit:testDebugUnitTest   # protocol / crypto / pipeline / profile tests
./gradlew :app:assembleDebug               # installable debug APK
./gradlew :app:assembleRelease             # unsigned release APK
```

The outputs land in `app/build/outputs/apk/`.

## Connecting to a Windows sender

1. Set the **same password** on both ends (RemSound on Windows stores it on the profile).
2. Same network: the devices discover each other automatically — tick the Android device in the Windows peer list, tick the Windows machine in this app.
3. Tailscale / WAN: add the other machine's IP under "Add a peer by address" (the port is automatic), or add the public relay hostname on both ends.
4. Audio plays only from peers you have ticked, matching the Windows allow-list behaviour.

### A note on LAN discovery

Android's Wi-Fi hardware filters broadcast frames once the screen is off, so the foreground service holds a multicast lock while it runs. The app also announces itself by unicast to every known peer, and the Windows side auto-learns our address from any unicast announcement — so after you add the Windows machine's IP once (or it adds yours), discovery works both ways even where broadcast does not.

## Differences from the Apple port

Everything on the wire is identical; these are the platform-shaped differences.

* **No iCloud profile sync.** Profiles are local to the device. The Apple design depends on iCloud Keychain to carry profile passwords end-to-end encrypted while the profile JSON stays password-free; Android has no first-party equivalent, and syncing profiles through anything that is not end-to-end encrypted would break the rule that design exists to protect.
* **No Shortcuts/Siri actions.** The Apple port exposes App Intents; the equivalent here is the media-button and notification control.
* **Packet arrival gaps are thread-timed, not kernel-timed.** The JDK's datagram API exposes no `SO_TIMESTAMP`, so the Diagnostics panel says "thread-timed" — under background throttling a burst of on-time packets can read as one large gap.
* **Opus runs in pure Java** (Concentus) rather than through libopus.

## Deliberate simplifications

* Clock drift between sender and receiver is bounded by buffer trim + re-arm rather than a resampler; non-48 kHz PCM senders are resampled linearly.
* Remote volume control (Control packets) is parsed and ignored.
* Relay support is the v1 pairwise mode (send heartbeats at the relay host, audio reflects back on the same socket). The relay's v2 lobby protocol is not implemented — the Windows client does not emit it either.
* No PCM send: the send path is Opus only, one mixed lane.

## Licence

MIT, same as RemSound.
