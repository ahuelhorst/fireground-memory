import type { ChangeEvent } from 'react';
import { StatusLine } from '../components/StatusLine';
import { Transcript } from '../components/Transcript';
import { useTranscription } from '../hooks/useTranscription';

/**
 * Transcription screen: pick a WAV recording, then watch its transcript stream in, in large text.
 */
export function TranscriptionPage() {
  const transcription = useTranscription();

  const onFileChosen = (event: ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0];
    event.target.value = ''; // allow choosing the same file again
    if (file) {
      void transcription.start(file);
    }
  };

  return (
    <main className="app">
      <header className="app__header">
        <h1>Fireground Memory</h1>
      </header>

      <section className="controls">
        <label className="button button--primary">
          Choose WAV file
          <input type="file" accept=".wav,audio/wav,audio/x-wav" className="visually-hidden" onChange={onFileChosen} />
        </label>
        {transcription.isActive && (
          <button type="button" className="button" onClick={transcription.stop}>
            Stop
          </button>
        )}
      </section>

      <StatusLine {...transcription} />
      <Transcript lines={transcription.lines} partial={transcription.partial} />
    </main>
  );
}
