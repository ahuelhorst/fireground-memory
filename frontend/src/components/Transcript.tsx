import { useEffect, useRef } from 'react';

/**
 * Transcript lines in large text, newest last and highlighted. The sentence still being spoken
 * (`partial`) is shown as the newest line, dimmed and italic, until its finished line replaces it.
 * Scrolls to the newest line as text arrives so the latest radio traffic is always on screen.
 */
export function Transcript({ lines, partial }: { lines: string[]; partial: string | null }) {
  const latestRef = useRef<HTMLLIElement>(null);

  useEffect(() => {
    latestRef.current?.scrollIntoView?.({ behavior: 'smooth', block: 'end' });
  }, [lines.length, partial]);

  if (lines.length === 0 && !partial) {
    return null;
  }

  return (
    <ol className="transcript" aria-label="Transcript" aria-live="polite">
      {lines.map((line, index) => {
        const isLatest = !partial && index === lines.length - 1;
        return (
          <li key={index} ref={isLatest ? latestRef : undefined} className={isLatest ? 'transcript__line--latest' : undefined}>
            {line}
          </li>
        );
      })}
      {partial && (
        // Hidden from screen readers: it changes several times a second; the finished line is announced.
        <li ref={latestRef} className="transcript__line--latest transcript__line--partial" aria-hidden="true">
          {partial}
        </li>
      )}
    </ol>
  );
}
