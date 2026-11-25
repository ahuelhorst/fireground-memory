/**
 * Incremental Server-Sent Events parser (per the WHATWG spec, minus `id`/`retry`).
 * Needed because the browser's EventSource only supports GET, and our transcription
 * endpoint is a POST upload whose response we read with fetch.
 *
 * Feed it text chunks as they arrive; it returns every event completed so far.
 * Comment lines (`:keepalive`) are ignored.
 */
export interface SseEvent {
  /** Event name; `message` when the server didn't name it. */
  event: string;
  data: string;
}

export class SseParser {
  private buffer = '';
  private eventName = '';
  private dataLines: string[] = [];

  push(chunk: string): SseEvent[] {
    let text = this.buffer + chunk;
    const events: SseEvent[] = [];

    // A trailing \r may be the first half of a \r\n split across chunks: hold it back.
    let heldBack = '';
    if (text.endsWith('\r')) {
      heldBack = '\r';
      text = text.slice(0, -1);
    }

    // Split on any line ending (\r\n, \n or \r); keep the trailing partial line buffered.
    const lines = text.split(/\r\n|\n|\r/);
    this.buffer = (lines.pop() ?? '') + heldBack;

    for (const line of lines) {
      if (line === '') {
        this.dispatch(events);
      } else if (!line.startsWith(':')) {
        this.field(line);
      }
    }
    return events;
  }

  private field(line: string): void {
    const colon = line.indexOf(':');
    const name = colon === -1 ? line : line.slice(0, colon);
    let value = colon === -1 ? '' : line.slice(colon + 1);
    if (value.startsWith(' ')) {
      value = value.slice(1);
    }
    if (name === 'event') {
      this.eventName = value;
    } else if (name === 'data') {
      this.dataLines.push(value);
    }
  }

  private dispatch(events: SseEvent[]): void {
    if (this.dataLines.length > 0) {
      events.push({ event: this.eventName || 'message', data: this.dataLines.join('\n') });
    }
    this.eventName = '';
    this.dataLines = [];
  }
}
