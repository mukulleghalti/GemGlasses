<h1 align="center">Veyra</h1>

<p align="center">
  <strong>Gemini as the assistant on your Meta Ray-Ban glasses.</strong><br>
  A native Android app that turns the glasses into eyes, ears and a voice for a
  real-time Gemini Live agent — speech-to-speech, on-demand vision, and actions
  on your phone.
</p>

<p align="center">
  <img alt="Platform" src="https://img.shields.io/badge/platform-Android%2010%2B-3DDC84?logo=android&logoColor=white">
  <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-2.0-7F52FF?logo=kotlin&logoColor=white">
  <img alt="Jetpack Compose" src="https://img.shields.io/badge/UI-Jetpack%20Compose-4285F4">
  <img alt="Gemini Live" src="https://img.shields.io/badge/Gemini-Live%20API-8A5BFF">
  <img alt="License" src="https://img.shields.io/badge/license-MIT-blue">
</p>

---

## Why I built this

I wanted to see how little scaffolding you actually need to put a genuinely
useful voice assistant *on your face*. The Meta Ray-Bans already have a great
mic, speaker and camera — but the built-in "Hey Meta" assistant is a walled
garden. Meanwhile, the Gemini Live API can do speech-to-speech, voice-activity
detection, barge-in, tool calling and visual grounding over a **single
WebSocket**.

So the whole bet of Veyra is: treat the glasses as a dumb I/O peripheral,
put *all* the logic on the phone, and let one Gemini Live connection be the
brain. No wake-word engine, no Whisper, no ElevenLabs, no on-device model.
Just the glasses, your phone, and Gemini.

> **Note:** this runs *alongside* the native "Hey Meta" assistant — Meta's
> toolkit doesn't let you replace it. Veyra is a parallel assistant you
> activate yourself from the app.

## What it does

- 🎙️ **Real-time voice conversation** through the glasses — you talk, it talks
  back, and you can interrupt it mid-sentence (native barge-in).
- 👁️ **On-demand vision** — ask "what am I looking at?" and it turns on the
  glasses camera for a few seconds to see what you see.
- 🗺️ **Grounded local search** — "good coffee near me" returns *real* places
  from Google Maps, with the sources shown in the transcript.
- 🧭 **Actions** — start Maps navigation or draft a message, hands-free.
- 🔁 **Sessions that don't drop** — reconnects invisibly across the Live API's
  time limits so a 40-minute conversation feels like one.

## The four tools

The agent has exactly four function tools — small, sharp, and each one does one
thing well:

| Tool | What it does |
|------|--------------|
| `capturar_visao` | Turns on the glasses camera on demand and streams frames for ≤ 20 s. Keeps the session nominally audio-only, dodging the Live API's 2-minute video cap. |
| `iniciar_navegacao` | Launches turn-by-turn navigation in Google Maps. |
| `buscar_lugares` | Grounded place search via Gemini + Maps grounding (proxied server-side), returns a spoken summary + cited places. |
| `enviar_mensagem` | Drafts an SMS/WhatsApp message pre-filled — you confirm the send. Nothing is sent automatically. |

## Architecture at a glance

```
 Ray-Ban Meta  ──BT audio / DAT camera──►  Android phone (all logic)  ──ephemeral token──►  Gemini Live
   mic · speaker · camera                    AgentController                                (1 WebSocket:
                                             ├─ audio: MicStreamer / SpeakerSink            STT+LLM+TTS+VAD)
                                             ├─ gemini: LiveSession + SessionKeeper
                                             ├─ tools: 4 function tools
                                             └─ ui: Compose (Home/Transcript/Settings)
                                                            │
                                            Google AI Studio ── your own API key,
                                            stored encrypted on the phone; the app
                                            mints its own short-lived tokens
```

The full write-up is in [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md). The
design principles I held myself to:

1. **Audio-persistent, vision-on-demand** — the socket is audio-only by default
   and reconnects transparently via session resumption.
2. **The API key never leaves your phone** — it's stored encrypted on-device
   and sent only to Google; the app mints its own short-lived ephemeral
   tokens directly.
3. **One place for the model ID** — Live model IDs churn; they live in
   `gemini/Models.kt` and nowhere else.
4. **Glasses are swappable** — everything sits behind a `GlassesBackend`
   interface, so the whole app runs against a mock backend with no hardware.

## Tech stack

Kotlin · Coroutines + Flow · Jetpack Compose · Hilt · OkHttp (raw Live
WebSocket) · kotlinx.serialization · DataStore · Meta Wearables Device Access
Toolkit.

## Getting started

### Run it without hardware (mock backend)

You don't need the glasses — or even the Meta SDK — to build and run. The app
ships with a `MockGlassesBackend` that reports a fake pair of glasses and
synthesises camera frames, so the full pipeline (audio loop, tools, vision path)
is exercisable on an emulator.

```bash
git clone https://github.com/lpecom/GemGlasses.git
cd GemGlasses
cp local.properties.example local.properties   # set sdk.dir

# Mock glasses backend is the default — no github_token or hardware needed
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

### Run it for real (with glasses)

1. **Gemini** — get an API key from Google AI Studio and paste it into the
   app's Settings → Gemini API key (it's verified on save). The key is stored
   encrypted on the phone and sent only to Google.
2. **Meta Wearables** — create an app at
   [wearables.developer.meta.com](https://wearables.developer.meta.com), grab
   the `APPLICATION_ID` / `CLIENT_TOKEN`, and add a classic GitHub PAT with
   `read:packages` as `github_token` in `local.properties` (this unlocks the DAT
   SDK from GitHub Packages), then build with the real backend enabled:
   ```bash
   ./gradlew installDebug -Pveyra.useRealGlasses=true
   ```
3. **Developer Mode** — in the Meta AI app: *Settings → App Info → tap the
   version 5×* to enable it, then pair your glasses.
4. Build and install onto your phone (`-Pveyra.useRealGlasses=true` links
   the DAT SDK):
   ```bash
   ./gradlew installDebug -Pveyra.useRealGlasses=true
   ```

`local.properties.example` documents every field.

## Roadmap

The app was built milestone by milestone — each one independently demoable:

- [x] **M0** — Compose + Hilt + DAT skeleton, glasses registration, device list
- [x] **M1** — Audio loop: glasses mic → Gemini Live → voice back, with barge-in
- [x] **M2** — Robust session: resumption + `goAway` + foreground service
- [x] **M3** — Vision: `capturar_visao` + on-demand camera burst
- [x] **M4** — Actions: `iniciar_navegacao` + `enviar_mensagem`
- [x] **M5** — Places: `buscar_lugares` with Maps grounding + sources UI
- [x] **M6** — Polish: settings (language/voice), audible error handling

**Out of scope (for now):** custom wake word, on-device LLM, smart-home,
persistent cross-session memory, public distribution (Meta's developer preview
only allows sharing with testers in your org).

## Privacy & security

- The Google API key lives **only** on the Worker. The app uses ephemeral,
  short-TTL tokens — never a key in the APK or `BuildConfig`.
- Transcripts stay **on the device** and are never synced.
- Audio, frames and tokens are never logged.

## Disclaimer

Independent, unofficial project for learning and demonstration. Not affiliated
with or endorsed by Meta or Google. "Ray-Ban" and "Meta" are trademarks of
their respective owners. Using the glasses camera to record others may be
subject to local laws — be a decent human about it.

---

<p align="center">Built by <a href="https://github.com/lpecom">Lucas Piffer</a> · MIT licensed</p>
