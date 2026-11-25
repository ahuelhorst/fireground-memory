# Fireground Memory

Real-time transcription of fireground radio transmissions. Replaces hand-copying radio traffic
onto a whiteboard: the backend transcribes audio and streams the text to clients (iOS app planned)
as it is recognized.

Java 25, Spring Boot 3.5, Maven, JPA + H2, Google Cloud Speech-to-Text (v2), Lombok, springdoc-openapi.

## Prerequisites
- JDK 25, Maven 3.9+
- A Google Cloud project with the Speech-to-Text API enabled, and a service-account key file

## Run
PowerShell:

    $env:GOOGLE_APPLICATION_CREDENTIALS = "C:\path\to\service-account.json"
    $env:PROJECT_ID = "your-gcp-project-id"
    mvn spring-boot:run

The `local` profile is active by default (`application.properties`); provider settings are in
`application-local.yml`.

- API docs (Swagger UI): http://localhost:8080/swagger-ui.html
- OpenAPI spec (JSON): http://localhost:8080/v3/api-docs

## Frontend
A React app in [`frontend/`](frontend/README.md): choose a WAV file and the transcript streams in
as large text. With the backend running:

    cd frontend
    npm install
    npm run dev      # http://localhost:5173

## Test

    mvn test

Tests use mocked transcription clients and never call Google.

## API
| Method | Path | Purpose |
|---|---|---|
| POST | /v1/fgi/transcribe-file | multipart `file` (16-bit PCM WAV); responds with a `text/event-stream` of transcript segments |

The stream carries two kinds of transcript events:
- `event:partial` — the sentence still being spoken, updated several times a second so words
  appear as they're said. Each one replaces the previous partial, and its text can still change
  (`Engine 1 on sea` → `Engine 1 on scene`).
- Unnamed events (plain `data:`) — a finished sentence. It replaces the current partial and never
  changes. Clients that only read `data:` lines get finished sentences, as before.

```
event:partial
data:Engine 1

event:partial
data:Engine 1 on sea

data:Engine 1 on scene
```

Uploaded files are played to the
provider at real-time pace, so a 2-minute file streams over ~2 minutes. Recordings of any length
work: the Google stream is restarted automatically before Google's ~5-minute limit. During
silence the server sends `:keepalive` comment lines (every 15 s by default); SSE clients ignore
them. Closing the connection stops transcription.

Responses:
- `200` — `text/event-stream` of transcript segments. If transcription fails mid-stream, the last
  event is `event:error` with a message in `data` (e.g. `Transcription failed: PERMISSION_DENIED`).
  A stream that closes with no events and no error means Google recognized no speech.
- `400` — `file` part missing
- `415` — not a supported WAV (`application/problem+json` explaining why); no stream is opened

### Audio requirements
16-bit PCM **WAV**, 8–48 kHz, mono or stereo. The format is read from the WAV header. Radio
recordings are typically 8 kHz mono. Convert anything else with ffmpeg:

    ffmpeg -i input.mp3 -ar 8000 -ac 1 -c:a pcm_s16le radio.wav

Example:

    curl.exe -N -F "file=@radio.wav" http://localhost:8080/v1/fgi/transcribe-file

### Settings (`application-local.yml`)
| Property | Default | Meaning |
|---|---|---|
| `google.cloud.speech.stream-soft-limit` | `240s` | After this, restart the Google stream at the next pause |
| `google.cloud.speech.stream-hard-limit` | `270s` | Restart regardless (Google closes streams at ~5 min) |
| `fireground.sse.heartbeat-interval` | `15s` | How often `:keepalive` is sent |

### Known limits
- If a Google stream fails, the whole transcription fails (no automatic retry yet).

## Architecture
    api (TranscriptionController: upload in, transcripts out over SSE)
        ->  service
              AudioService.readWav            WAV upload -> AudioSource
              RealTimePacingService           recording -> real-time-speed AudioSource
              TranscriptionService.transcribe AudioSource -> TranscriptUpdates
        ->  client (TranscriptionClient interface)
                └─ GoogleSpeechClient

    model      AudioSource, TranscriptUpdate (plain data shared by all layers)
    exception  UnsupportedAudioException (mapped to HTTP 415 by ApiExceptionHandler)

`api/` is the HTTP layer: `*Api` interfaces in `api/openapi/` hold routes and OpenAPI docs, controllers implement them,
and transport adapters (`SseTranscriptPublisher`) live here. The service is transport-neutral, so a
future live (WebSocket) endpoint reuses `TranscriptionService.transcribe` unchanged.

The speech provider sits behind `TranscriptionClient`; to switch providers, add a new
implementation and wire it in config. Vendor SDK types do not leave the `client` package.

## Roadmap
- Live radio audio input (instead of file upload)
- Wrap the React frontend as an iOS app (Capacitor)
- Configurable / mock transcription provider for local development
