/**
 * OpenAI's Responses API (`POST {base}/responses`, streamed) for the `direct` agent — the wire
 * the ChatGPT subscription's Codex backend and xAI's signed-in Grok speak (DECISIONS §118).
 *
 * What was observed (Hermes v2026.9.14, MIT; read, described here in our words, nothing
 * copied): Hermes sends both backends a Responses request with the system prompt as
 * `instructions`, the conversation as `input` message items (`input_text` / `input_image` for
 * the person, `output_text` for the model), `store: false` and `stream: true`. The Codex backend
 * takes no `max_output_tokens`; reasoning goes as `reasoning: {effort, summary}` when the model
 * takes it. The stream is server-sent events whose `type` names them: text arrives as
 * `response.output_text.delta`, the reasoning summary as `response.reasoning_summary_text.delta`,
 * and the totals on `response.completed`.
 *
 * The same two rules as every other adapter's `chat` (`stream.ts`): a failure is a value, never
 * an exception, and nothing here puts a header — which is where the credential is — into a
 * message.
 */
import { joinUrl } from './http.js';
import { chatFailure, openStream, parseFrame, sseData } from './stream.js';
import type { ChatEvent, ChatMessage, ChatRequest, ProviderContext } from './types.js';

/** Sent when the profile has no system prompt: the Codex backend refuses an empty one. */
export const DEFAULT_INSTRUCTIONS = 'You are a helpful assistant.';

export interface ResponsesOptions {
  /** `reasoning` as the endpoint takes it, or null to send none. */
  reasoning?: Record<string, string> | null;
  /** The Codex backend refuses `max_output_tokens`; other Responses endpoints take it. */
  sendMaxOutputTokens?: boolean;
}

/** One conversation turn as a Responses `input` message item. */
function inputItem(message: ChatMessage): Record<string, unknown> {
  if (message.role === 'assistant') {
    return {
      type: 'message',
      role: 'assistant',
      content: [{ type: 'output_text', text: message.text }],
    };
  }
  return {
    type: 'message',
    role: 'user',
    content: [
      ...(message.text ? [{ type: 'input_text', text: message.text }] : []),
      ...(message.images ?? []).map((image) => ({
        type: 'input_image',
        image_url: `data:${image.mime};base64,${image.dataBase64}`,
      })),
    ],
  };
}

interface ResponsesFrame {
  type?: unknown;
  delta?: unknown;
  message?: unknown;
  error?: { message?: unknown } | string | null;
  response?: {
    error?: { message?: unknown } | null;
    incomplete_details?: { reason?: unknown } | null;
    usage?: {
      input_tokens?: unknown;
      output_tokens?: unknown;
      input_tokens_details?: { cached_tokens?: unknown } | null;
      output_tokens_details?: { reasoning_tokens?: unknown } | null;
    } | null;
  };
}

function count(value: unknown): number | undefined {
  return typeof value === 'number' && Number.isFinite(value) && value >= 0
    ? Math.trunc(value)
    : undefined;
}

function messageOf(frame: ResponsesFrame): string | null {
  const nested = frame.response?.error?.message;
  if (typeof nested === 'string' && nested) return nested;
  if (typeof frame.error === 'string' && frame.error) return frame.error;
  if (frame.error && typeof frame.error === 'object' && typeof frame.error.message === 'string') {
    return frame.error.message;
  }
  return typeof frame.message === 'string' && frame.message ? frame.message : null;
}

/**
 * One streamed turn against a Responses endpoint. System messages become `instructions`
 * (joined), never an `input` turn the model could argue with.
 */
export async function* responsesChat(
  ctx: ProviderContext,
  request: ChatRequest,
  options: ResponsesOptions = {},
): AsyncIterable<ChatEvent> {
  const instructions =
    request.messages
      .filter((message) => message.role === 'system')
      .map((message) => message.text.trim())
      .filter(Boolean)
      .join('\n\n') || DEFAULT_INSTRUCTIONS;
  const open = await openStream({
    url: joinUrl(ctx.baseUrl, 'responses'),
    headers: {
      ...(ctx.apiKey ? { authorization: `Bearer ${ctx.apiKey}` } : {}),
      ...ctx.headers,
    },
    body: {
      model: request.model,
      instructions,
      input: request.messages.filter((message) => message.role !== 'system').map(inputItem),
      store: false,
      stream: true,
      include: [],
      ...(options.reasoning ? { reasoning: options.reasoning } : {}),
      ...(options.sendMaxOutputTokens && request.maxOutputTokens
        ? { max_output_tokens: request.maxOutputTokens }
        : {}),
    },
    fetchImpl: ctx.fetchImpl,
    ...(request.signal ? { signal: request.signal } : {}),
  });
  if (!open.ok) {
    yield chatFailure(open);
    return;
  }
  for await (const payload of sseData(open.lines)) {
    const frame = parseFrame(payload) as ResponsesFrame | null;
    if (!frame || typeof frame.type !== 'string') continue;
    switch (frame.type) {
      case 'response.output_text.delta':
        if (typeof frame.delta === 'string' && frame.delta) {
          yield { type: 'delta', text: frame.delta };
        }
        break;
      case 'response.reasoning_summary_text.delta':
      case 'response.reasoning_text.delta':
        if (typeof frame.delta === 'string' && frame.delta) {
          yield { type: 'reasoning', text: frame.delta };
        }
        break;
      case 'response.failed':
      case 'error':
        yield {
          type: 'failed',
          reason: 'http_error',
          detail: (messageOf(frame) ?? 'the provider ended the turn with an error').slice(0, 500),
          status: null,
        };
        return;
      case 'response.completed':
      case 'response.incomplete': {
        const usage = frame.response?.usage;
        if (usage) {
          const event: Extract<ChatEvent, { type: 'usage' }> = { type: 'usage' };
          const input = count(usage.input_tokens);
          const output = count(usage.output_tokens);
          const cached = count(usage.input_tokens_details?.cached_tokens);
          const reasoned = count(usage.output_tokens_details?.reasoning_tokens);
          // `input_tokens` counts the cached part too; the hub's input is what was not cached.
          if (input !== undefined) event.inputTokens = Math.max(0, input - (cached ?? 0));
          if (output !== undefined) event.outputTokens = output;
          if (cached !== undefined) event.cacheReadTokens = cached;
          if (reasoned !== undefined) event.reasoningTokens = reasoned;
          yield event;
        }
        yield { type: 'completed' };
        return;
      }
      default:
        break;
    }
  }
  if (request.signal?.aborted) {
    yield { type: 'failed', reason: 'cancelled', detail: null, status: null };
    return;
  }
  // The stream ended without `response.completed`: what arrived is the answer.
  yield { type: 'completed' };
}
