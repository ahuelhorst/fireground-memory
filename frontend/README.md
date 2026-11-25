# Fireground Memory — Frontend

React + TypeScript (Vite) app: choose a WAV recording and watch its transcript stream in, in
large text. Runs in any browser; the same code will be wrapped as an iOS/Android app with
[Capacitor](https://capacitorjs.com/) later.

## Run (development)
Start the backend first (from the project root: `mvn spring-boot:run`), then:

    cd frontend
    npm install
    npm run dev

Open http://localhost:5173. The dev server forwards `/v1/*` to the backend on `localhost:8080`.

## Build

    npm run build       # type-check + production build into dist/

## Configuration
| Variable | Default | Meaning |
|---|---|---|
| `VITE_API_BASE_URL` | empty (same origin / dev proxy) | Backend URL, e.g. when running on a phone. See `.env.example`. |

## Structure
    src/
      services/     backend communication and audio; the ONLY code that talks to the backend
        transcriptionApi.ts   POST /v1/fgi/transcribe-file, reads the SSE stream
        sseParser.ts          SSE parser (EventSource can't POST, so we read fetch's stream)
      hooks/
        useTranscription.ts   screen state: status, lines, errors, start/stop
      components/
        StatusLine.tsx        one-line status
        Transcript.tsx        large-text transcript, newest highlighted, auto-scroll
      pages/
        TranscriptionPage.tsx the transcription screen
      App.tsx                 app root: global styles, renders the current page

## Later: running as an app (Capacitor)
Requires a Mac with Xcode for iOS. Roughly: `npm install @capacitor/core @capacitor/cli @capacitor/ios`,
`npx cap init`, `npm run build`, `npx cap add ios`, `npx cap open ios`. The backend must then be
reachable from the phone (set `VITE_API_BASE_URL`) and allow the app's origin via CORS.
