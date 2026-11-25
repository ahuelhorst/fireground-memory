# CLAUDE.md — Fireground Memory

## Business Goal
During fires, firefighters listen to radio traffic and hand-transcribe it onto a whiteboard to
track what's happening. This project replaces that by **listening to radio transmissions and
streaming a live transcription to an iOS app**, so crews can see what was said in real time.

Keep in mind when making decisions:
- **Latency matters.** Transcripts should appear within seconds of speech.
- **Reliability matters more than polish.** Fireground conditions mean noisy audio and flaky
  networks. Fail gracefully, reconnect automatically, never silently lose transcript data.
- **The UI must be readable at a glance** — large text, high contrast, newest content obvious.

## Architecture
```
React app (frontend/)     <--- HTTP/SSE (OpenAPI contract) --->  Spring Boot backend  --->  Speech-to-text provider
browser + iOS via Capacitor                                        (src/)                     (currently Google Cloud Speech)
```

- **Backend:** Java 25, Spring Boot 3.5, Maven, JPA + H2.
- **Package layout** (`org.firememory`):
  - `api` — the HTTP API layer, everything the iOS app depends on:
    - `api.openapi` — all OpenAPI material:
      - `*Api` interfaces (e.g. `TranscriptionApi`) — routing + all OpenAPI annotations.
      - `OpenApiConfig` — API-level metadata (title, version).
    - `*Controller` classes (e.g. `TranscriptionController`) — implement the `*Api` interface.
      No OpenAPI annotations, no business logic; just delegate to services.
    - `ApiExceptionHandler` — maps domain exceptions to `ProblemDetail` (application/problem+json).
    - Transport adapters, e.g. `SseTranscriptPublisher` (callback + future -> `SseEmitter`).
  - `model` — plain data types shared by all layers (no logic, no dependencies on other packages):
    - `AudioSource` — PCM stream + sample rate + channels. `TranscriptionClient` implementations
      receive `AudioSource`, never raw uploads.
    - `TranscriptUpdate(text, isFinal)` — a partial result (sentence in progress, may change) or a
      finished sentence.
  - `exception` — exceptions any layer may throw (e.g. `UnsupportedAudioException`);
    `ApiExceptionHandler` maps them to HTTP responses. Don't put exceptions in `service`.
  - `service` — business logic, one job per service. Depends on interfaces, never on vendor SDKs.
    - `TranscriptionService` — audio in, transcript updates out (via callback).
    - `AudioService` — `readWav(InputStream)`: validates WAV (16-bit PCM, 8–48 kHz, mono/stereo)
      and produces an `AudioSource`.
    - `RealTimePacingService` — `pacedToRealTime(AudioSource)` for recorded input.
  - `client` — external API integrations. `TranscriptionClient` is the abstraction;
    `GoogleSpeechClient` is one implementation.
  - `config` — Spring wiring, including which provider implementation is active.
- **Streaming:** transcripts are pushed to clients via Server-Sent Events (`SseEmitter`).

## Workflow Rules (MUST follow)
1. **Always create a plan and ask the user before editing files.** Present which files will be
   created/changed and why, then wait for explicit approval. No exceptions for "small" changes.
2. Keep changes scoped to what was approved. Raise anything else as a suggestion instead.
3. After implementing, run the tests (`mvn test`) and report results honestly, including failures.

## Coding Practices

### Lombok
- Use Lombok to remove boilerplate: `@RequiredArgsConstructor` for constructor injection
  (instead of hand-written constructors / `@Autowired`), `@Getter`/`@Setter`, `@Builder`,
  `@Value` for immutable DTOs, `@Slf4j` for logging.
- Avoid `@Data` on JPA entities (broken `equals`/`hashCode`/`toString` with lazy relations);
  use `@Getter`/`@Setter` instead.
- Lombok is registered as an annotation processor in `maven-compiler-plugin` (required on JDK 23+).
  No `lombok.config` — keep it that way.
- Don't put `@Value` on fields of `@RequiredArgsConstructor` classes (Lombok won't copy it to the
  constructor). Bind settings into a `@ConfigurationProperties` record (e.g.
  `GoogleSpeechProperties`) and inject that instead.

### Decoupled external API integrations
- Every external service (speech-to-text, storage, etc.) sits behind an interface we own
  (e.g. `TranscriptionClient`). Services depend only on that interface.
- Vendor SDK types (Google, OpenAI, etc.) **must not leak** outside their client implementation —
  no vendor classes in method signatures, DTOs, services, or controllers. Map to our own models.
- Select the active provider through configuration (`@ConditionalOnProperty` / profiles), so
  swapping providers means adding a new implementation + changing config, with no changes to
  services or controllers.
- Vendor credentials and settings live in config/env vars, never hard-coded.

### Transport-neutral services
- Service methods never use HTTP/transport types (`SseEmitter`, `MultipartFile`,
  `HttpServletRequest`, WebSocket sessions, `ResponseEntity`).
- They take domain types (e.g. `AudioSource`) and report results via callbacks and/or
  `CompletableFuture`. Example: `TranscriptionService.transcribe(AudioSource, Consumer<TranscriptUpdate>)`
  serves the SSE file endpoint now and must be reused unchanged by the live endpoint.
- Adapting to a transport (SSE, WebSocket) is the `api` layer's job.
- Cancellation: cancelling the `CompletableFuture` returned by a transcription must stop provider
  work (sending audio, open streams). Transport adapters cancel it when the connection closes.

### Decoupled backend and frontend
- The backend is a pure API; it knows nothing about the iOS app's UI.
- The iOS app talks to the backend **only** through the documented API contract.
- Do not put presentation logic (formatting for display, UI state) in the backend, or
  business logic in the app.
- The frontend lives in `frontend/` (separate npm project). The backend serves no UI.

### Frontend (`frontend/`)
- React + TypeScript, built with Vite. No frontend tests — don't add any.
- Runs in the browser and will be wrapped as an iOS/Android app with **Capacitor** — so use only
  web APIs that work in iOS WKWebView, and keep layouts phone-first (safe-area insets, large tap
  targets).
- `src/` layout: `components/` reusable UI components, `pages/` full screens, `services/` backend
  communication and audio, `hooks/` reusable React logic. `App.tsx` is only the root.
- **Only `src/services/` talks to the backend.** Components and hooks call `src/services` functions, never
  `fetch` directly. The backend URL comes from `VITE_API_BASE_URL` (empty = same origin / dev proxy).
- No business logic in the UI: it displays what the API returns.
- Screen state lives in hooks (e.g. `useTranscription`); components stay presentational.
- Design: dark, high-contrast, large text (transcript ~2–2.75rem); readable at a glance.

### OpenAPI
- The API follows the **OpenAPI 3** specification. The spec is the source of truth for the
  contract between backend and iOS app.
- Approach is **code-first with springdoc**: the spec is generated from annotations on the
  `*Api` interfaces in the `api.openapi` package (`@Operation`, `@ApiResponse`, `@Schema`, ...) and
  served at `/v3/api-docs` (Swagger UI: `/swagger-ui.html`). API-level metadata lives in
  `api/openapi/OpenApiConfig`.
- Keep controllers clean: every new endpoint is declared (mapping + docs) on a `*Api`
  interface in `api/openapi/`, and the controller in `api/` only implements it.
- Every endpoint must be documented (paths, request/response schemas, error responses, SSE event
  formats). Update the annotations in the same change as any API change.
- Use versioned paths (current base: `/v1/fgi`), plural resource nouns, consistent error bodies.
- Use request/response DTOs at the controller boundary — never expose JPA entities directly.

### Unit tests
- Write unit tests for every new or changed class (JUnit 5 + Mockito; `spring-boot-starter-test`).
- Services are tested with mocked client interfaces — tests must not call real external APIs.
- Use `@WebMvcTest` for controller tests, verifying behavior against the API contract.
- Test failure paths too (provider errors, dropped streams, bad input).
- `SseEmitter` output can't be read directly — test streaming through MockMvc
  (`request().asyncStarted()` then `asyncDispatch(...)`). See `TranscriptionControllerTest`.
- `GoogleSpeechClientTest` replaces Google streams with in-memory `FakeStream`s; extend those
  for new Google behaviour rather than mocking gax classes ad hoc.
- Use `@MockitoBean` (not the deprecated `@MockBean`). Mockito runs as a Java agent via surefire.

### General
- Constructor injection only; prefer immutability.
- Log with `@Slf4j`; never log credentials or full audio payloads.
- Handle errors explicitly — don't swallow exceptions.

## Commands
```
mvn spring-boot:run     # run backend (http://localhost:8080)
mvn test                # run tests
mvn clean package       # build

cd frontend
npm run dev             # frontend dev server (http://localhost:5173, proxies /v1 to :8080)
npm run build           # type-check + production build
```

## Domain Notes
- Radio audio is narrowband (~300–3000 Hz), usually captured as 8 kHz, 16-bit mono PCM.
  Never hard-code a sample rate; carry the real format in `AudioSource`.
- Google streaming requires audio at ~real time, max 15 KB per request, and closes a stream
  after ~5 minutes. Google model is `telephony` (tuned for narrowband audio).
- **Pacing belongs to recorded input only.** File endpoints call `RealTimePacingService.pacedToRealTime()`;
  live input must not be paced (it is already real time, and pacing would make backlog permanent).
  `TranscriptionClient` implementations send audio as fast as it arrives.
- `GoogleSpeechClient` restarts Google streams automatically (soft limit: at next pause; hard
  limit: regardless — `google.cloud.speech.stream-*-limit`). It waits for each stream's final
  results before opening the next, so transcripts stay ordered. A restart at the hard limit can
  split one sentence into two transcripts.
- **Partial results:** Google interim results are on, so words appear while someone is speaking.
  SSE sends them as `event:partial` (each replaces the previous; text can still change) and finished
  sentences as plain `data:` events. The app shows the partial line dimmed until the finished one
  replaces it. Only finished sentences count as pauses for stream restarts.
- SSE sends `:keepalive` comments (`fireground.sse.heartbeat-interval`) so dropped clients are
  detected during radio silence and their transcription is cancelled.

## Known Gaps
- Current endpoint transcribes an uploaded file; the goal is live radio audio streaming.
- Stream restart is unit-tested but not yet verified against real Google (needs a >5-minute recording).
- No retry: if any Google stream fails, the whole transcription fails.
- `GoogleSpeechClient` is always active (`@Component`); there is no config switch or mock
  provider yet for running locally without Google credentials.
- Frontend not yet wrapped with Capacitor (needs a Mac/Xcode for iOS); backend has no CORS
  config yet (needed once the app is served from a different origin than the backend).
