/**
 * GemGlasses backend — a single Cloudflare Worker with two jobs:
 *
 *   POST /token   → mint a short-lived ephemeral token for the Gemini Live API
 *   POST /places  → proxy a grounded `generateContent` (Maps grounding) call
 *
 * The Google API key lives ONLY here (as a Worker secret) and never reaches the
 * Android client. Both endpoints are gated by a shared app secret.
 */

export interface Env {
  /** Google AI Studio API key. `wrangler secret put GEMINI_API_KEY` */
  GEMINI_API_KEY: string;
  /** Shared secret the app sends in `X-App-Secret`. `wrangler secret put TOKEN_APP_SECRET` */
  TOKEN_APP_SECRET: string;
}

const GEMINI_API = "https://generativelanguage.googleapis.com/v1beta";
const LIVE_MODEL = "models/gemini-2.0-flash-live-001";
const GROUNDING_MODEL = "gemini-2.5-flash";

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);

    if (request.method !== "POST") {
      return json({ error: "method_not_allowed" }, 405);
    }
    if (!authorized(request, env)) {
      return json({ error: "unauthorized" }, 401);
    }

    try {
      switch (url.pathname) {
        case "/token":
          return await mintToken(env);
        case "/places":
          return await searchPlaces(request, env);
        default:
          return json({ error: "not_found" }, 404);
      }
    } catch (err) {
      return json({ error: "internal", detail: String(err) }, 500);
    }
  },
};

function authorized(request: Request, env: Env): boolean {
  const provided = request.headers.get("X-App-Secret") ?? "";
  return timingSafeEqual(provided, env.TOKEN_APP_SECRET);
}

/**
 * Exchanges the API key for an ephemeral auth token scoped to the Live API.
 * The token is single-session, short-TTL, and safe to hand to the client.
 */
async function mintToken(env: Env): Promise<Response> {
  const now = Date.now();
  const body = {
    // ~30 min window to start a session; a single new session may be opened.
    expireTime: new Date(now + 30 * 60_000).toISOString(),
    newSessionExpireTime: new Date(now + 2 * 60_000).toISOString(),
    uses: 1,
    liveConnectConstraints: {
      model: LIVE_MODEL,
      config: { responseModalities: ["AUDIO"] },
    },
  };

  const res = await fetch(
    `${GEMINI_API}/auth_tokens?key=${env.GEMINI_API_KEY}`,
    {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify(body),
    },
  );

  if (!res.ok) {
    return json({ error: "token_upstream", status: res.status, detail: await res.text() }, 502);
  }

  const data = (await res.json()) as { name?: string; token?: string; expireTime?: string };
  // The API returns the token as `name` (tokens/xxx) or `token` depending on version.
  const token = data.token ?? data.name;
  if (!token) return json({ error: "token_missing" }, 502);

  return json({ token, expiresAt: data.expireTime ?? body.expireTime });
}

/**
 * Runs a grounded place search and returns a short spoken summary plus the
 * cited places (title + uri) the app must display per Maps ToS.
 */
async function searchPlaces(request: Request, env: Env): Promise<Response> {
  const { query, latitude, longitude } = (await request.json()) as {
    query?: string;
    latitude?: number;
    longitude?: number;
  };
  if (!query) return json({ error: "missing_query" }, 400);

  const payload: Record<string, unknown> = {
    contents: [{ role: "user", parts: [{ text: query }] }],
    tools: [{ googleMaps: {} }],
    systemInstruction: {
      parts: [
        {
          text:
            "Responda em português, curto, listando 2-4 lugares reais e próximos. " +
            "Não invente estabelecimentos.",
        },
      ],
    },
  };

  if (typeof latitude === "number" && typeof longitude === "number") {
    payload.toolConfig = {
      retrievalConfig: { latLng: { latitude, longitude } },
    };
  }

  const res = await fetch(
    `${GEMINI_API}/models/${GROUNDING_MODEL}:generateContent?key=${env.GEMINI_API_KEY}`,
    {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify(payload),
    },
  );

  if (!res.ok) {
    return json({ error: "places_upstream", status: res.status, detail: await res.text() }, 502);
  }

  const data = (await res.json()) as GeminiResponse;
  const candidate = data.candidates?.[0];
  const summary =
    candidate?.content?.parts?.map((p) => p.text ?? "").join(" ").trim() ||
    "Não encontrei lugares.";

  const places = (candidate?.groundingMetadata?.groundingChunks ?? [])
    .map((chunk) => chunk.maps ?? chunk.web)
    .filter((c): c is { title?: string; uri: string } => !!c?.uri)
    .map((c) => ({ title: c.title ?? c.uri, uri: c.uri }));

  return json({ summary, places });
}

// --- helpers ---------------------------------------------------------------

interface GeminiResponse {
  candidates?: Array<{
    content?: { parts?: Array<{ text?: string }> };
    groundingMetadata?: {
      groundingChunks?: Array<{
        maps?: { title?: string; uri: string };
        web?: { title?: string; uri: string };
      }>;
    };
  }>;
}

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json" },
  });
}

/** Constant-time string comparison to avoid leaking the secret via timing. */
function timingSafeEqual(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}
