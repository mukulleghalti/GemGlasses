# Architecture

GemGlasses is deliberately lean. The glasses are a dumb I/O peripheral; all the
intelligence is one Gemini Live WebSocket. There is no wake-word engine, no
separate STT/TTS, no on-device model — Gemini Live absorbs speech-to-speech,
VAD, and barge-in in a single connection.

```
 Ray-Ban Meta glasses                Android phone (all logic)                 Cloud
 ┌───────────────────┐   BT audio    ┌──────────────────────────────────┐   ┌───────────────┐
 │  mic / speaker  ◄──┼───────────────┤ BluetoothAudioRouter             │   │ Cloudflare    │
 │  camera         ───┼──DAT SDK──────┤ MicStreamer / SpeakerSink        │   │ Worker        │
 └───────────────────┘   (frames)     │ GlassesManager / CameraSource    │   │  /token       │
                                       │                                  │   │  /places      │
                                       │        AgentController           │   └──────┬────────┘
                                       │   ┌──────────────────────────┐   │          │ API key
                                       │   │ SessionKeeper            │   │  ephemeral│ (server only)
                                       │   │   └─ LiveSession (WS) ───┼───┼───────────┼──► Gemini
                                       │   │ ToolRegistry → 7 tools   │   │  token    │    Live API
                                       │   └──────────────────────────┘   │          │
                                       │ ConversationStore → Compose UI   │          │
                                       └──────────────────────────────────┘
```

## Modules

| Package | Responsibility |
|---------|----------------|
| `glasses` | `GlassesBackend` abstraction over the Meta DAT SDK, with a `mock` backend (always available) and a real backend compiled only when the SDK is linked. |
| `audio` | Bluetooth routing + PCM capture (16 kHz) and playback (24 kHz). |
| `gemini` | `LiveSession` (WebSocket + wire protocol), `SessionKeeper` (resumption + reconnect), `ToolRegistry`, `TokenProvider`, `Models`. |
| `tools` | The seven function tools (vision, navigation, places, message, save/list memories, mic mute) and their JSON schemas. |
| `agent` | `AgentController` — the conductor that wires audio ⇄ session ⇄ tools ⇄ vision. |
| `state` | `ConversationStore` — in-memory transcript + Maps-cited places. |
| `settings` | DataStore-backed language/voice preferences. |
| `service` | Foreground service keeping the session alive in the background. |
| `ui` | Compose screens (Home / Transcript / Settings) + `AgentViewModel`. |

## Key design decisions

- **Audio-persistent, vision-on-demand.** The Live session is audio-only by
  default (15-min cap, transparently resumed). Video only turns on when the
  `capturar_visao` tool fires, streams for ≤ 20 s, and turns off — sidestepping
  the 2-minute video cap.
- **Reconnection is invisible.** `SessionKeeper` stores the resumption handle on
  every update and reconnects proactively on `goAway`, keeping the `AudioTrack`
  open so there is no audible gap.
- **API key never on the device.** The app only ever holds ephemeral tokens
  minted by the Worker. Even Maps-grounded search is proxied server-side.
- **One model constant.** Live model IDs churn; `gemini/Models.kt` is the only
  place an ID appears.
- **Glasses are swappable.** Everything depends on the `GlassesBackend`
  interface, so the whole app runs on the mock backend for CI and hardware-free
  development.

## Session lifecycle

1. `TokenProvider` fetches an ephemeral token from the Worker.
2. `LiveSession` opens the WS and sends `setup` (model, system instruction,
   tools, audio config, transcription, resumption enabled).
3. `MicStreamer` streams PCM continuously; Gemini owns VAD and turn-taking.
4. Assistant audio plays through `SpeakerSink`; on `interrupted`, playback is
   flushed immediately (barge-in).
5. Tool calls are dispatched by `ToolRegistry`; responses go back over the same
   socket.
6. On `goAway`/drop, `SessionKeeper` reconnects with the stored handle.
