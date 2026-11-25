import type { TranscriptionState } from '../hooks/useTranscription';

/**
 * One-line, plain-language status of the current transcription (announced to screen readers).
 */
export function StatusLine({ status, fileName, lines, errorMessage }: TranscriptionState) {
  return (
    <p role="status" className={`status status--${status}`}>
      {statusText(status, fileName, lines.length, errorMessage)}
    </p>
  );
}

function statusText(
  status: TranscriptionState['status'],
  fileName: string | null,
  lineCount: number,
  errorMessage: string | null,
): string {
  switch (status) {
    case 'idle':
      return 'Choose a WAV recording to transcribe.';
    case 'connecting':
      return `Connecting… ${fileName ?? ''}`.trim();
    case 'streaming':
      return `Transcribing ${fileName ?? ''}…`;
    case 'done':
      return lineCount === 0 ? 'Done: no speech recognized.' : `Done: ${lineCount} ${lineCount === 1 ? 'line' : 'lines'}.`;
    case 'stopped':
      return 'Stopped.';
    case 'error':
      return errorMessage ?? 'Something went wrong.';
  }
}
