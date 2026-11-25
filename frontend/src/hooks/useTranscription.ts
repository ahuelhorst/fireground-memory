import { useCallback, useEffect, useRef, useState } from 'react';
import { TranscriptionError, transcribeFile } from '../services/transcriptionApi';

/**
 * Screen state for transcribing one file at a time: status, transcript lines and errors.
 * Starting a new file stops the previous one; unmounting stops any transcription in progress.
 */
export type TranscriptionStatus = 'idle' | 'connecting' | 'streaming' | 'done' | 'stopped' | 'error';

export interface TranscriptionState {
  status: TranscriptionStatus;
  fileName: string | null;
  /** Finished transcript lines, oldest first. */
  lines: string[];
  /** The sentence still being spoken (may change), or null when there is none. */
  partial: string | null;
  errorMessage: string | null;
}

const INITIAL_STATE: TranscriptionState = {
  status: 'idle',
  fileName: null,
  lines: [],
  partial: null,
  errorMessage: null,
};

export function useTranscription() {
  const [state, setState] = useState<TranscriptionState>(INITIAL_STATE);
  /** The transcription in progress, or null when none is running. */
  const controllerRef = useRef<AbortController | null>(null);

  const stop = useCallback(() => {
    controllerRef.current?.abort();
    controllerRef.current = null;
  }, []);

  const start = useCallback(
    async (file: File) => {
      stop();
      const controller = new AbortController();
      controllerRef.current = controller;
      const isCurrent = () => controllerRef.current === controller;

      setState({ ...INITIAL_STATE, status: 'connecting', fileName: file.name });

      try {
        await transcribeFile(file, {
          signal: controller.signal,
          onOpen: () => {
            if (isCurrent()) setState((s) => ({ ...s, status: 'streaming' }));
          },
          onTranscript: (text) => {
            if (isCurrent()) setState((s) => ({ ...s, lines: [...s.lines, text], partial: null }));
          },
          onPartial: (text) => {
            if (isCurrent()) setState((s) => ({ ...s, partial: text }));
          },
        });
        if (isCurrent()) {
          controllerRef.current = null;
          setState((s) => ({ ...s, status: 'done' }));
        }
      } catch (error) {
        if (controller.signal.aborted) {
          // Nothing newer started: the user pressed Stop. Otherwise a newer file replaced this one.
          if (controllerRef.current === null) setState((s) => ({ ...s, status: 'stopped' }));
          return;
        }
        if (isCurrent()) {
          controllerRef.current = null;
          const message = error instanceof TranscriptionError ? error.message : 'Something went wrong.';
          setState((s) => ({ ...s, status: 'error', errorMessage: message }));
        }
      }
    },
    [stop],
  );

  useEffect(() => stop, [stop]);

  const isActive = state.status === 'connecting' || state.status === 'streaming';
  return { ...state, isActive, start, stop };
}
