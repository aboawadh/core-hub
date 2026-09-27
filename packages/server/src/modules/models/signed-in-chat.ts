/**
 * The `direct` agent on a provider signed in through Hermes (DECISIONS §118, proposed — owner to
 * confirm; the owner: «المفروض برق يستخدم اي موديل بـ API أو بدخول»).
 *
 * A signed-in provider's credential is Hermes's (§55): Hermes did the device-code sign-in, keeps
 * the token in its own auth store, and refreshes it. The hub never stores it. What changed is that
 * the hub now **borrows** it for one turn, the way the live model list (§83, `live-models.ts`) and
 * the subscription's images (§84, `image_api.py`) already do: at the start of each turn it runs
 * Hermes's own Python, in the Hermes home the provider was signed in to, and asks Hermes's own
 * resolver (`hermes_cli.auth.resolve_*_runtime_credentials`, which refreshes a token about to
 * expire). The program prints one JSON line — the wire, the base URL, the model id on the wire,
 * the headers (the credential is the `Authorization` one) and the reasoning field — and the hub
 * keeps that in the turn's memory only. After a 401, before any text arrived, Hermes is asked once
 * more with a forced refresh and the request is sent once more.
 *
 * The wire per provider, as Hermes v2026.9.14 calls it (MIT; read, described here in our words,
 * nothing copied — `hermes_cli/runtime_provider.py`, `agent/transports/codex.py`):
 *
 * - `openai-codex` — the Responses API of the Codex backend (`/responses`, streamed), with Hermes's
 *   identity headers (`agent.codex_headers`) and the account id from the token's claims;
 * - `xai-oauth` — xAI's Responses API (`https://api.x.ai/v1/responses`);
 * - `nous` — OpenAI-compatible `chat/completions` (Hermes's `nous_api_mode`, which names Anthropic
 *   Messages for an `anthropic/*` model only when `nous.anthropic_wire` is `native`);
 * - `minimax-oauth` — Anthropic Messages, with `Authorization: Bearer` rather than `x-api-key`.
 *
 * Nothing here logs, stores or returns the credential: a failure carries Hermes's or the
 * provider's words with any trace of the token replaced, and never the program's output.
 */
import { anthropicChat, openAiChat, responsesChat } from './adapters/index.js';
import type { ChatEvent, ChatRequest, ProviderContext } from './adapters/types.js';
import type { HermesPythonRun } from './live-models.js';

/** How the turn is sent, in Hermes's own `api_mode` words. */
export type SignedInWire = 'codex_responses' | 'chat_completions' | 'anthropic_messages';

/** What one turn needs from Hermes. Held in the turn's memory only; never stored or logged. */
export interface SignedInCredential {
  wire: SignedInWire;
  baseUrl: string;
  /** The model id on the wire (Hermes strips the `-900k` names it invents itself). */
  model: string;
  /** Hermes's headers for the provider; the credential is the `Authorization` one. */
  headers: Record<string, string>;
  /** `reasoning` as the endpoint takes it, clamped by Hermes's own vocabulary, or null. */
  reasoning: Record<string, string> | null;
  /** The ChatGPT Codex backend, which refuses `max_output_tokens`. */
  codexBackend: boolean;
}

export type SignedInCredentialResult =
  | { ok: true; credential: SignedInCredential }
  | {
      ok: false;
      /**
       * `hermes_unavailable` — no Hermes Python or home to ask on this hub; `not_signed_in` —
       * Hermes has no usable sign-in for the provider; `hermes_failed` — the program did not
       * answer.
       */
      reason: 'hermes_unavailable' | 'not_signed_in' | 'hermes_failed';
      detail: string;
    };

/** One ask: the model the turn runs, the effort the person chose, and whether to force a refresh. */
export interface CredentialAsk {
  model: string;
  effort: string | null;
  force: boolean;
}

/**
 * The program. Its inputs are arguments (the Hermes provider id, the model, the effort, `1` to
 * force a refresh), never program text; it prints one JSON line. A failure line never holds the
 * token: the resolver failed before there was one.
 */
export const SIGNED_IN_CREDENTIAL_PROGRAM = `
import base64, json, sys
provider = sys.argv[1]
model = sys.argv[2] if len(sys.argv) > 2 else ""
wanted = (sys.argv[3] if len(sys.argv) > 3 else "").strip().lower()
force = len(sys.argv) > 4 and sys.argv[4] == "1"

def out(value):
    sys.stdout.write(json.dumps(value) + "\\n")
    sys.stdout.flush()
    sys.exit(0)

def short(exc):
    return (type(exc).__name__ + ": " + str(exc))[:300]

try:
    import hermes_cli.auth as auth
except Exception as exc:
    out({"ok": False, "reason": "hermes_unavailable", "detail": "Hermes's Python has no credential store: " + short(exc)})

def resolve():
    if provider == "openai-codex":
        return auth.resolve_codex_runtime_credentials(force_refresh=force, refresh_if_expiring=True)
    if provider == "xai-oauth":
        return auth.resolve_xai_oauth_runtime_credentials(force_refresh=force)
    if provider == "nous":
        return auth.resolve_nous_runtime_credentials(force_refresh=force)
    if provider == "minimax-oauth":
        return auth.resolve_minimax_oauth_runtime_credentials()
    raise LookupError("Hermes has no sign-in for " + provider)

try:
    creds = resolve() or {}
except Exception as exc:
    out({"ok": False, "reason": "not_signed_in", "detail": short(exc)})

token = str(creds.get("api_key") or "").strip()
if not token:
    out({"ok": False, "reason": "not_signed_in", "detail": "the signed-in account has no usable credential"})

DEFAULT_BASE = {"openai-codex": "https://chatgpt.com/backend-api/codex", "xai-oauth": "https://api.x.ai/v1"}
base = str(creds.get("base_url") or "").strip().rstrip("/") or DEFAULT_BASE.get(provider, "")
if not base:
    out({"ok": False, "reason": "not_signed_in", "detail": "the signed-in account has no endpoint"})

wire = {"openai-codex": "codex_responses", "xai-oauth": "codex_responses", "minimax-oauth": "anthropic_messages"}.get(provider, "chat_completions")
if provider == "nous":
    try:
        from hermes_cli.providers import nous_api_mode
        wire = nous_api_mode(model) or "chat_completions"
    except Exception:
        wire = "chat_completions"
if wire not in ("codex_responses", "chat_completions", "anthropic_messages"):
    wire = "chat_completions"

wire_model = model
headers = {"Authorization": "Bearer " + token}
if provider == "openai-codex":
    try:
        from agent.model_metadata import strip_codex_context_variant_suffix
        wire_model = strip_codex_context_variant_suffix(model) or model
    except Exception:
        if wire_model.endswith("-900k"):
            wire_model = wire_model[: -len("-900k")]
    try:
        from agent.codex_headers import codex_cloudflare_headers
        headers.update(codex_cloudflare_headers(token, base_url=base))
    except Exception:
        pass
    if not any(name.lower() == "chatgpt-account-id" for name in headers):
        try:
            part = token.split(".")[1]
            claims = json.loads(base64.urlsafe_b64decode(part + "=" * (-len(part) % 4)))
            account = (claims.get("https://api.openai.com/auth") or {}).get("chatgpt_account_id")
            if isinstance(account, str) and account:
                headers["ChatGPT-Account-ID"] = account
        except Exception:
            pass
    headers["Authorization"] = "Bearer " + token

reasoning = None
if wire == "codex_responses" and wanted != "none":
    effort = wanted or "medium"
    try:
        import agent.reasoning_effort as efforts
        if provider == "xai-oauth":
            from agent.model_metadata import grok_supports_reasoning_effort, is_grok_46_family
            if grok_supports_reasoning_effort(wire_model):
                vocabulary = efforts.XAI_GROK46_EFFORTS if is_grok_46_family(wire_model) else efforts.XAI_LEGACY_EFFORTS
                effort = efforts.clamp_effort(effort, vocabulary)
            else:
                effort = None
        else:
            effort = efforts.clamp_effort(effort, efforts.codex_supported_efforts(wire_model))
    except Exception:
        if provider == "xai-oauth":
            effort = None
    if effort:
        reasoning = {"effort": str(effort)}
        if provider == "openai-codex":
            reasoning["summary"] = "auto"

out({
    "ok": True,
    "wire": wire,
    "base_url": base,
    "model": wire_model,
    "headers": headers,
    "reasoning": reasoning,
    "codex_backend": provider == "openai-codex",
})
`;

const WIRES = new Set<SignedInWire>(['codex_responses', 'chat_completions', 'anthropic_messages']);

/** Hermes's credential for one turn, never throwing, never passing Hermes's output on. */
export async function signedInCredential(
  run: HermesPythonRun,
  home: string,
  hermesProvider: string,
  ask: CredentialAsk,
): Promise<SignedInCredentialResult> {
  let answer: { code: number; stdout: string; stderr: string };
  try {
    answer = await run(home, [
      SIGNED_IN_CREDENTIAL_PROGRAM,
      hermesProvider,
      ask.model,
      ask.effort ?? '',
      ask.force ? '1' : '0',
    ]);
  } catch (error) {
    return {
      ok: false,
      reason: 'hermes_failed',
      detail: error instanceof Error ? error.message : 'Hermes’s Python did not run',
    };
  }
  const line = answer.stdout.trim().split('\n').pop() ?? '';
  let body: Record<string, unknown>;
  try {
    const parsed: unknown = JSON.parse(line);
    if (!parsed || typeof parsed !== 'object') throw new Error('not an object');
    body = parsed as Record<string, unknown>;
  } catch {
    // Never stdout or stderr: a traceback may quote the request it failed on.
    return {
      ok: false,
      reason: 'hermes_failed',
      detail: `Hermes’s Python ended with code ${String(answer.code)} without an answer`,
    };
  }
  if (body.ok !== true) {
    const reason =
      body.reason === 'hermes_unavailable' || body.reason === 'not_signed_in'
        ? body.reason
        : 'hermes_failed';
    return {
      ok: false,
      reason,
      detail: typeof body.detail === 'string' ? body.detail : 'Hermes could not lend the sign-in',
    };
  }
  const headers: Record<string, string> = {};
  if (body.headers && typeof body.headers === 'object') {
    for (const [name, value] of Object.entries(body.headers as Record<string, unknown>)) {
      if (typeof value === 'string') headers[name] = value;
    }
  }
  const reasoning: Record<string, string> = {};
  if (body.reasoning && typeof body.reasoning === 'object') {
    for (const [name, value] of Object.entries(body.reasoning as Record<string, unknown>)) {
      if (typeof value === 'string' && value) reasoning[name] = value;
    }
  }
  const wire = WIRES.has(body.wire as SignedInWire) ? (body.wire as SignedInWire) : null;
  const baseUrl = typeof body.base_url === 'string' ? body.base_url : '';
  if (!wire || !baseUrl || !Object.keys(headers).some((name) => /^authorization$/i.test(name))) {
    return {
      ok: false,
      reason: 'hermes_failed',
      detail: 'Hermes answered without a usable sign-in',
    };
  }
  return {
    ok: true,
    credential: {
      wire,
      baseUrl,
      model: typeof body.model === 'string' && body.model ? body.model : ask.model,
      headers,
      reasoning: Object.keys(reasoning).length > 0 ? reasoning : null,
      codexBackend: body.codex_backend === true,
    },
  };
}

export interface SignedInChatOptions {
  /** The provider's name, for the sentences the hub writes itself. */
  label: string;
  /** Hermes's credential for this turn; `force` after a 401. */
  resolve: (force: boolean) => Promise<SignedInCredentialResult>;
  fetchImpl: typeof fetch;
}

/** The credential's own values, to be cut out of any text that leaves this file. */
function secretsOf(credential: SignedInCredential): string[] {
  const found: string[] = [];
  for (const [name, value] of Object.entries(credential.headers)) {
    if (!/^authorization$/i.test(name)) continue;
    found.push(value);
    const bare = value.replace(/^Bearer\s+/i, '').trim();
    if (bare) found.push(bare);
  }
  // Longest first, so the whole header goes before the token inside it.
  return found.filter((value) => value.length >= 8).sort((a, b) => b.length - a.length);
}

export function scrub(text: string | null, secrets: readonly string[]): string | null {
  if (text === null) return null;
  let clean = text;
  for (const secret of secrets) clean = clean.split(secret).join('[redacted]');
  return clean;
}

/** A refusal before any request left: why, and what the person can do. */
function refusal(
  result: Extract<SignedInCredentialResult, { ok: false }>,
  label: string,
): ChatEvent {
  switch (result.reason) {
    case 'hermes_unavailable':
      return {
        type: 'failed',
        // `agent_unavailable`, and a fallback model may take the turn (§54).
        reason: 'unreachable',
        status: null,
        detail:
          `${label} is signed in through Hermes, and the direct agent borrows that sign-in from ` +
          `Hermes's own Python at each turn; this hub cannot run it (${result.detail}). ` +
          'Use the Hermes agent for this model, or add a provider with a key.',
      };
    case 'not_signed_in':
      return {
        type: 'failed',
        reason: 'unauthorized',
        status: null,
        detail:
          `Hermes has no usable sign-in for ${label} (${result.detail}); ` +
          'sign in again under Models → Providers',
      };
    default:
      return {
        type: 'failed',
        reason: 'unreachable',
        status: null,
        detail: `Hermes could not lend the sign-in for ${label}: ${result.detail}`,
      };
  }
}

function wireChat(
  credential: SignedInCredential,
  request: ChatRequest,
  label: string,
  fetchImpl: typeof fetch,
): AsyncIterable<ChatEvent> {
  const ctx: ProviderContext = {
    slug: '',
    label,
    baseUrl: credential.baseUrl,
    // The credential is in the headers, as Hermes wrote them.
    apiKey: null,
    requiresKey: false,
    headers: credential.headers,
    settings: {},
    fetchImpl,
  };
  const wireRequest: ChatRequest = { ...request, model: credential.model };
  switch (credential.wire) {
    case 'codex_responses':
      return responsesChat(ctx, wireRequest, {
        reasoning: credential.reasoning,
        sendMaxOutputTokens: !credential.codexBackend,
      });
    case 'anthropic_messages':
      // Hermes's base for Messages ends where `/v1/messages` is appended.
      return anthropicChat(
        { ...ctx, baseUrl: credential.baseUrl.replace(/\/v1\/?$/, '') },
        wireRequest,
      );
    default:
      return openAiChat(ctx, wireRequest);
  }
}

/**
 * One streamed turn on a signed-in provider: Hermes's credential, the provider's wire, one more
 * try with a refreshed sign-in after a 401 that came before any text. Never throws.
 */
export async function* signedInChat(
  options: SignedInChatOptions,
  request: ChatRequest,
): AsyncIterable<ChatEvent> {
  for (let attempt = 0; attempt < 2; attempt += 1) {
    const resolved = await options.resolve(attempt > 0);
    if (request.signal?.aborted) {
      yield { type: 'failed', reason: 'cancelled', detail: null, status: null };
      return;
    }
    if (!resolved.ok) {
      yield refusal(resolved, options.label);
      return;
    }
    const secrets = secretsOf(resolved.credential);
    let produced = false;
    let again = false;
    for await (const event of wireChat(
      resolved.credential,
      request,
      options.label,
      options.fetchImpl,
    )) {
      if (event.type === 'failed') {
        if (event.status === 401 && !produced && attempt === 0 && !request.signal?.aborted) {
          again = true;
          break;
        }
        const refused = event.reason === 'unauthorized';
        yield {
          ...event,
          detail:
            scrub(event.detail, secrets) ??
            (refused
              ? `${options.label} refused the sign-in Hermes holds; sign in again under Models → Providers`
              : null),
        };
        return;
      }
      produced = true;
      yield event;
      if (event.type === 'completed') return;
    }
    if (!again) return;
  }
}
