# GemGlasses backend

A single Cloudflare Worker that keeps the Google API key off the phone.

| Endpoint | Purpose |
|----------|---------|
| `POST /token` | Mints a short-lived **ephemeral token** for the Gemini Live API. The app opens the Live WebSocket with this token, never the API key. |
| `POST /places` | Proxies a `generateContent` call with **Maps grounding**, returning a short summary + cited places. |

Both endpoints require the `X-App-Secret` header to match `TOKEN_APP_SECRET`.

## Setup

```bash
npm install

# Secrets (production)
npx wrangler secret put GEMINI_API_KEY
npx wrangler secret put TOKEN_APP_SECRET

# Local dev — create .dev.vars (gitignored):
#   GEMINI_API_KEY="..."
#   TOKEN_APP_SECRET="dev-secret"
npm run dev        # http://localhost:8787

npm run deploy
```

Point the Android app at the Worker via `GEMGLASSES_BACKEND_URL` in `local.properties`
(use `http://10.0.2.2:8787` from the emulator for local dev).

## Request shapes

```http
POST /token
X-App-Secret: <secret>
→ { "token": "...", "expiresAt": "2026-..." }

POST /places
X-App-Secret: <secret>
{ "query": "café aberto agora", "latitude": 53.34, "longitude": -6.26 }
→ { "summary": "...", "places": [ { "title": "...", "uri": "https://maps..." } ] }
```
