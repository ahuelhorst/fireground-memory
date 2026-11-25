import { SseParser } from './sseParser';

/**
 * Client for the backend transcription API (POST /v1/fgi/transcribe-file, see the OpenAPI spec at
 * /v3/api-docs). The only module that talks to the backend: UI code calls these functions and
 * never uses fetch directly, so an API change is contained here.
 */

const API_BASE_URL: string = import.meta.env.VITE_API_BASE_URL ?? '';

export type TranscriptionErrorKind =
  /** The backend rejected the upload before streaming (400/415): `message` explains why. */
  | 'rejected'
  /** Transcription started but failed (an `error` event from the server). */
  | 'failed'
  /** Could not reach the backend, or the connection dropped. */
  | 'network';

export class TranscriptionError extends Error {
  constructor(
    message: string,
    readonly kind: TranscriptionErrorKind,
  ) {
    super(message);
    this.name = 'TranscriptionError';
  }
}

export interface TranscribeOptions {
  /** Called once the server accepts the upload and starts streaming. */
  onOpen?: () => void;
  /** Called with each finished transcript line, in order. It replaces the current partial line. */
  onTranscript: (text: string) => void;
  /**
   * Called with the sentence still being spoken, several times a second. Each call replaces the
   * previous partial; the text may still change until the finished line arrives.
   */
  onPartial?: (text: string) => void;
  /** Abort to stop transcription; the backend then stops the speech provider too. */
  signal?: AbortSignal;
}

/**
 * Uploads a WAV file and streams its transcript.
 *
 * Resolves when the transcript is complete. Rejects with {@link TranscriptionError} on failure,
 * or with the abort reason (a DOMException named `AbortError`) if `signal` is aborted.
 */
export async function transcribeFile(file: File, options: TranscribeOptions): Promise<void> {
  const body = new FormData();
  body.append('file', file);

  let response: Response;
  try {
    response = await fetch(`${API_BASE_URL}/v1/fgi/transcribe-file`, {
      method: 'POST',
      body,
      headers: { Accept: 'text/event-stream' },
      signal: options.signal,
    });
  } catch (error) {
    throw toNetworkError(error, options.signal);
  }

  if (!response.ok) {
    throw new TranscriptionError(await problemMessage(response), response.status >= 500 ? 'failed' : 'rejected');
  }
  if (!response.body) {
    throw new TranscriptionError('The server returned no stream.', 'network');
  }

  options.onOpen?.();
  await readEvents(response.body, options);
}

async function readEvents(stream: ReadableStream<Uint8Array>, options: TranscribeOptions): Promise<void> {
  const reader = stream.getReader();
  const decoder = new TextDecoder(); // stream mode keeps multi-byte characters split across chunks intact
  const parser = new SseParser();
  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) {
        return;
      }
      for (const event of parser.push(decoder.decode(value, { stream: true }))) {
        if (event.event === 'error') {
          throw new TranscriptionError(event.data, 'failed');
        }
        if (event.event === 'message') {
          options.onTranscript(event.data);
        } else if (event.event === 'partial') {
          options.onPartial?.(event.data);
        }
      }
    }
  } catch (error) {
    if (error instanceof TranscriptionError) {
      throw error;
    }
    throw toNetworkError(error, options.signal);
  } finally {
    reader.releaseLock();
  }
}

/** Reads an RFC 9457 problem response (`detail`), falling back to the HTTP status. */
async function problemMessage(response: Response): Promise<string> {
  try {
    const problem: unknown = await response.json();
    if (problem && typeof problem === 'object' && 'detail' in problem && typeof problem.detail === 'string') {
      return problem.detail;
    }
  } catch {
    // Not JSON: use the status below.
  }
  return `The server rejected the request (HTTP ${response.status}).`;
}

function toNetworkError(error: unknown, signal: AbortSignal | undefined): unknown {
  if (signal?.aborted) {
    return signal.reason ?? error; // let callers recognise a deliberate stop
  }
  return new TranscriptionError('Lost connection to the server.', 'network');
}
