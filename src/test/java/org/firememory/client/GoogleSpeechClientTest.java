package org.firememory.client;

import com.google.api.gax.rpc.BidiStreamingCallable;
import com.google.api.gax.rpc.ClientStream;
import com.google.api.gax.rpc.ResponseObserver;
import com.google.api.gax.rpc.StreamController;
import com.google.cloud.speech.v2.*;
import org.firememory.model.AudioSource;
import org.firememory.config.GoogleSpeechProperties;
import org.firememory.model.TranscriptUpdate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link GoogleSpeechClient}. Google's streaming API is replaced by in-memory
 * {@link FakeStream}s, so stream restarts, ordering and cancellation can be driven without network
 * calls. Audio is 8 kHz mono (16,000 bytes/s), sent in 50 ms chunks of 800 bytes.
 */
@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class GoogleSpeechClientTest {

    private static final int CHUNK = 800;
    private static final Duration LONG = Duration.ofMinutes(10);

    @Mock
    private SpeechClient speechClient;

    @Mock
    private BidiStreamingCallable<StreamingRecognizeRequest, StreamingRecognizeResponse> callable;

    private final List<FakeStream> streams = new CopyOnWriteArrayList<>();
    private volatile StreamBehaviour behaviour = new StreamBehaviour() { };

    @BeforeEach
    void setUp() {
        when(speechClient.streamingRecognizeCallable()).thenReturn(callable);
        when(callable.splitCall(any())).thenAnswer(invocation -> {
            ResponseObserver<StreamingRecognizeResponse> observer = invocation.getArgument(0);
            FakeStream stream = new FakeStream(streams.size() + 1, observer);
            streams.add(stream);
            observer.onStart(stream.controller);
            return stream;
        });
    }

    // --- Basic behaviour ---------------------------------------------------------------------

    @Test
    void sendsConfigFromFileFormatFirstThenAllAudioThenCloses() throws Exception {
        client(LONG, LONG).transcribe(audio(2), text -> { }).get(2, TimeUnit.SECONDS);

        assertThat(streams).hasSize(1);
        FakeStream stream = streams.getFirst();
        StreamingRecognizeRequest config = stream.requests.getFirst();
        assertThat(config.getRecognizer()).isEqualTo("projects/test-project/locations/global/recognizers/_");
        RecognitionConfig recognition = config.getStreamingConfig().getConfig();
        assertThat(recognition.getExplicitDecodingConfig().getSampleRateHertz()).isEqualTo(8_000);
        assertThat(recognition.getExplicitDecodingConfig().getAudioChannelCount()).isEqualTo(1);
        assertThat(recognition.getModel()).isEqualTo("telephony");
        assertThat(recognition.getLanguageCodesList()).containsExactly("en-US");
        assertThat(config.getStreamingConfig().getStreamingFeatures().getInterimResults()).isTrue();
        assertThat(stream.audioChunkSizes()).containsExactly(CHUNK, CHUNK);
        assertThat(stream.closed).isTrue();
    }

    @Test
    void forwardsFinalTranscriptsStrippedAndSkipsBlankOnes() throws Exception {
        behaviour = new StreamBehaviour() {
            @Override
            public void onAudio(FakeStream stream, int chunk) {
                stream.observer.onResponse(StreamingRecognizeResponse.newBuilder()
                        .addResults(result(" Engine 1 on scene ", true))
                        .addResults(result("   ", true))
                        .addResults(StreamingRecognitionResult.newBuilder().setIsFinal(true))
                        .build());
            }
        };
        List<TranscriptUpdate> updates = Collections.synchronizedList(new ArrayList<>());

        client(LONG, LONG).transcribe(audio(1), updates::add).get(2, TimeUnit.SECONDS);

        assertThat(updates).containsExactly(TranscriptUpdate.finalText("Engine 1 on scene"));
    }

    // --- Partial results (words as they are spoken) ----------------------------------------------

    @Test
    void deliversPartialResultsWithPiecesJoinedIntoOneLine() throws Exception {
        behaviour = new StreamBehaviour() {
            @Override
            public void onAudio(FakeStream stream, int chunk) {
                switch (chunk) {
                    case 1 -> stream.observer.onResponse(StreamingRecognizeResponse.newBuilder()
                            .addResults(result("Engine", false))
                            .build());
                    case 2 -> stream.observer.onResponse(StreamingRecognizeResponse.newBuilder()
                            .addResults(result("Engine 1", false))
                            .addResults(result(" on sea ", false))
                            .addResults(result("  ", false))
                            .build());
                    case 3 -> stream.finalResult("Engine 1 on scene");
                    default -> { }
                }
            }
        };
        List<TranscriptUpdate> updates = Collections.synchronizedList(new ArrayList<>());

        client(LONG, LONG).transcribe(audio(3), updates::add).get(2, TimeUnit.SECONDS);

        assertThat(updates).containsExactly(
                TranscriptUpdate.partial("Engine"),
                TranscriptUpdate.partial("Engine 1 on sea"),
                TranscriptUpdate.finalText("Engine 1 on scene"));
    }

    @Test
    void skipsRepeatedPartialsButRepeatsThemAfterAFinishedSentence() throws Exception {
        behaviour = new StreamBehaviour() {
            @Override
            public void onAudio(FakeStream stream, int chunk) {
                switch (chunk) {
                    case 1, 2 -> stream.partialResult("Copy");
                    case 3 -> stream.finalResult("Copy");
                    case 4, 5 -> stream.partialResult("Copy");
                    default -> { }
                }
            }
        };
        List<TranscriptUpdate> updates = Collections.synchronizedList(new ArrayList<>());

        client(LONG, LONG).transcribe(audio(5), updates::add).get(2, TimeUnit.SECONDS);

        assertThat(updates).containsExactly(
                TranscriptUpdate.partial("Copy"),
                TranscriptUpdate.finalText("Copy"),
                TranscriptUpdate.partial("Copy"));
    }

    @Test
    void deliversFinishedSentenceBeforePartialOfTheNextOneInTheSameResponse() throws Exception {
        behaviour = new StreamBehaviour() {
            @Override
            public void onAudio(FakeStream stream, int chunk) {
                stream.observer.onResponse(StreamingRecognizeResponse.newBuilder()
                        .addResults(result("Engine 1 on scene", true))
                        .addResults(result("Working", false))
                        .build());
            }
        };
        List<TranscriptUpdate> updates = Collections.synchronizedList(new ArrayList<>());

        client(LONG, LONG).transcribe(audio(1), updates::add).get(2, TimeUnit.SECONDS);

        assertThat(updates).containsExactly(
                TranscriptUpdate.finalText("Engine 1 on scene"),
                TranscriptUpdate.partial("Working"));
    }

    @Test
    void partialResultsDoNotCountAsAPauseForStreamRestarts() throws Exception {
        // Soft limit 100 ms (2 chunks): partials on every chunk must not trigger a restart.
        behaviour = new StreamBehaviour() {
            @Override
            public void onAudio(FakeStream stream, int chunk) {
                stream.partialResult("still talking " + chunk);
            }
        };

        client(Duration.ofMillis(100), LONG).transcribe(audio(6), update -> { }).get(2, TimeUnit.SECONDS);

        assertThat(streams).hasSize(1);
        assertThat(streams.getFirst().audioChunkSizes()).hasSize(6);
    }

    @Test
    void partialResultsAreNotCountedAsTranscripts(CapturedOutput output) throws Exception {
        behaviour = new StreamBehaviour() {
            @Override
            public void onAudio(FakeStream stream, int chunk) {
                stream.partialResult("Engine");
            }
        };

        client(LONG, LONG).transcribe(audio(1), update -> { }).get(2, TimeUnit.SECONDS);

        assertThat(output).contains("Transcription finished with no transcripts");
    }

    @Test
    void logsStartupSettingsAndWarnsWhenNoTranscripts(CapturedOutput output) throws Exception {
        client(LONG, LONG).transcribe(audio(0), text -> { }).get(2, TimeUnit.SECONDS);

        assertThat(output)
                .contains("Starting Google streaming recognition: 8000 Hz, 1 channel(s), model=telephony")
                .contains("Transcription finished with no transcripts");
    }

    // --- Stream restarts (Google's ~5-minute limit) ---------------------------------------------

    @Test
    void restartsAtSoftLimitAfterNextFinishedSentence(CapturedOutput output) throws Exception {
        // Soft limit 100 ms (2 chunks); each stream reports a finished sentence on its 3rd chunk.
        behaviour = new StreamBehaviour() {
            @Override
            public void onAudio(FakeStream stream, int chunk) {
                if (chunk == 3) {
                    stream.finalResult("sentence from stream " + stream.number);
                }
            }
        };
        List<TranscriptUpdate> transcripts = Collections.synchronizedList(new ArrayList<>());

        client(Duration.ofMillis(100), LONG).transcribe(audio(8), transcripts::add).get(2, TimeUnit.SECONDS);

        assertThat(streams).hasSize(3);
        assertThat(streams.get(0).audioChunkSizes()).hasSize(3);
        assertThat(streams.get(1).audioChunkSizes()).hasSize(3);
        assertThat(streams.get(2).audioChunkSizes()).hasSize(2);
        assertThat(streams).allSatisfy(s -> assertThat(s.requests.getFirst().hasStreamingConfig()).isTrue());
        assertThat(streams).allSatisfy(s -> assertThat(s.closed).isTrue());
        assertThat(totalAudioBytes()).isEqualTo(8 * CHUNK);
        assertThat(transcripts).containsExactly(
                TranscriptUpdate.finalText("sentence from stream 1"),
                TranscriptUpdate.finalText("sentence from stream 2"));
        assertThat(output).contains("Rotating Google stream #1 -> #2 after 150 ms of audio (pause)");
    }

    @Test
    void restartsAtHardLimitWhenThereIsNoPause(CapturedOutput output) throws Exception {
        // Soft 100 ms, hard 200 ms (4 chunks); no finished sentences at all.
        client(Duration.ofMillis(100), Duration.ofMillis(200)).transcribe(audio(8), text -> { })
                .get(2, TimeUnit.SECONDS);

        assertThat(streams.get(0).audioBytes()).isEqualTo(4 * CHUNK);
        assertThat(streams.get(1).audioBytes()).isEqualTo(4 * CHUNK);
        assertThat(totalAudioBytes()).isEqualTo(8 * CHUNK);
        assertThat(output).contains("Rotating Google stream #1 -> #2 after 200 ms of audio (hard limit)");
    }

    @Test
    void waitsForPreviousStreamToFinishBeforeOpeningNext() throws Exception {
        behaviour = new StreamBehaviour() {
            @Override
            public boolean completeOnClose(FakeStream stream) {
                return stream.number != 1; // Google is slow to finalize stream #1
            }
        };

        CompletableFuture<Void> done = client(Duration.ofMillis(50), Duration.ofMillis(100))
                .transcribe(audio(4), text -> { });

        waitUntil(() -> !streams.isEmpty() && streams.getFirst().closed);
        Thread.sleep(200);
        assertThat(streams).hasSize(1);

        streams.getFirst().observer.onComplete();
        done.get(2, TimeUnit.SECONDS);
        assertThat(streams.size()).isGreaterThan(1);
        assertThat(totalAudioBytes()).isEqualTo(4 * CHUNK);
    }

    @Test
    void failsSessionWhenALaterStreamErrors(CapturedOutput output) {
        behaviour = new StreamBehaviour() {
            @Override
            public void onAudio(FakeStream stream, int chunk) {
                if (stream.number == 2 && chunk == 1) {
                    stream.observer.onError(new RuntimeException("RESOURCE_EXHAUSTED"));
                }
            }
        };

        CompletableFuture<Void> done = client(LONG, Duration.ofMillis(100)).transcribe(audio(8), text -> { });

        assertThatThrownBy(() -> done.get(2, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasRootCauseMessage("RESOURCE_EXHAUSTED");
        assertThat(streams).hasSize(2);
        assertThat(output).contains("Google streaming recognition failed (model=telephony, location=global)");
    }

    @Test
    void opensNewStreamWhenGoogleEndsOneEarly(CapturedOutput output) throws Exception {
        behaviour = new StreamBehaviour() {
            @Override
            public void onAudio(FakeStream stream, int chunk) {
                if (stream.number == 1 && chunk == 1) {
                    stream.observer.onComplete();
                }
            }
        };

        client(LONG, LONG).transcribe(audio(3), text -> { }).get(2, TimeUnit.SECONDS);

        assertThat(streams).hasSize(2);
        assertThat(streams.get(1).audioChunkSizes()).hasSize(2);
        assertThat(totalAudioBytes()).isEqualTo(3 * CHUNK);
        assertThat(output).contains("Google ended stream #1 early");
    }

    // --- Errors ------------------------------------------------------------------------------

    @Test
    void failsPropagatesAndLogsGoogleErrors(CapturedOutput output) {
        behaviour = new StreamBehaviour() {
            @Override
            public void onAudio(FakeStream stream, int chunk) {
                stream.observer.onError(new RuntimeException("PERMISSION_DENIED"));
            }
        };

        CompletableFuture<Void> done = client(LONG, LONG).transcribe(audio(4), text -> { });

        assertThatThrownBy(() -> done.get(2, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasRootCauseMessage("PERMISSION_DENIED");
        assertThat(output)
                .contains("Google streaming recognition failed (model=telephony, location=global)")
                .contains("PERMISSION_DENIED");
    }

    @Test
    void closesGoogleStreamWithErrorWhenAudioReadFails() {
        InputStream broken = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("disk read failed");
            }
        };

        CompletableFuture<Void> done = client(LONG, LONG).transcribe(new AudioSource(broken, 8_000, 1), text -> { });

        assertThatThrownBy(() -> done.get(2, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasRootCauseMessage("disk read failed");
        FakeStream stream = streams.getFirst();
        assertThat(stream.closedWithError).isInstanceOf(IOException.class);
        assertThat(stream.closed).isFalse();
    }

    // --- Cancellation (client disconnected) ----------------------------------------------------

    @Test
    void cancellingStopsSendingCancelsGoogleStreamAndClosesAudio(CapturedOutput output) throws Exception {
        LiveAudio live = new LiveAudio(2); // 2 chunks arrive, then the "radio" goes quiet
        CompletableFuture<Void> done = client(LONG, LONG).transcribe(new AudioSource(live, 8_000, 1), text -> { });
        waitUntil(() -> !streams.isEmpty() && streams.getFirst().audioChunkSizes().size() == 2);

        done.cancel(true);

        FakeStream stream = streams.getFirst();
        waitUntil(() -> stream.controller.cancelled);
        waitUntil(() -> live.closed);
        assertThat(done).isCancelled();
        int requestsAtCancel = stream.requests.size();
        Thread.sleep(100);
        assertThat(stream.requests).hasSize(requestsAtCancel);
        assertThat(stream.closed).isFalse();
        assertThat(output).contains("Transcription cancelled by caller after 100 ms of audio");
    }

    // --- Helpers -----------------------------------------------------------------------------

    private GoogleSpeechClient client(Duration softLimit, Duration hardLimit) {
        GoogleSpeechProperties properties = new GoogleSpeechProperties("test-project", "/unused",
                new GoogleSpeechProperties.Speech("global", "telephony", "en-US", softLimit, hardLimit));
        return new GoogleSpeechClient(speechClient, properties);
    }

    private static AudioSource audio(int chunks) {
        return new AudioSource(new ByteArrayInputStream(new byte[chunks * CHUNK]), 8_000, 1);
    }

    private int totalAudioBytes() {
        return streams.stream().mapToInt(FakeStream::audioBytes).sum();
    }

    private static StreamingRecognitionResult result(String transcript, boolean isFinal) {
        return StreamingRecognitionResult.newBuilder()
                .setIsFinal(isFinal)
                .addAlternatives(SpeechRecognitionAlternative.newBuilder().setTranscript(transcript))
                .build();
    }

    private static void waitUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Condition not met within 2 s");
            }
            Thread.sleep(5);
        }
    }

    /** How fake Google streams react; tests override what they need. */
    private interface StreamBehaviour {
        /** Called after each audio chunk is received; {@code chunk} counts from 1 per stream. */
        default void onAudio(FakeStream stream, int chunk) {
        }

        /** Whether the stream completes as soon as we half-close it (Google finalized instantly). */
        default boolean completeOnClose(FakeStream stream) {
            return true;
        }
    }

    /** In-memory stand-in for one Google streaming call. */
    private final class FakeStream implements ClientStream<StreamingRecognizeRequest> {

        final int number;
        final ResponseObserver<StreamingRecognizeResponse> observer;
        final List<StreamingRecognizeRequest> requests = new CopyOnWriteArrayList<>();
        final FakeController controller = new FakeController();
        volatile boolean closed;
        volatile Throwable closedWithError;

        FakeStream(int number, ResponseObserver<StreamingRecognizeResponse> observer) {
            this.number = number;
            this.observer = observer;
        }

        @Override
        public void send(StreamingRecognizeRequest request) {
            requests.add(request);
            if (!request.getAudio().isEmpty()) {
                behaviour.onAudio(this, audioChunkSizes().size());
            }
        }

        @Override
        public void closeSendWithError(Throwable error) {
            closedWithError = error;
        }

        @Override
        public void closeSend() {
            closed = true;
            if (behaviour.completeOnClose(this)) {
                observer.onComplete();
            }
        }

        @Override
        public boolean isSendReady() {
            return true;
        }

        void finalResult(String text) {
            observer.onResponse(StreamingRecognizeResponse.newBuilder().addResults(result(text, true)).build());
        }

        void partialResult(String text) {
            observer.onResponse(StreamingRecognizeResponse.newBuilder().addResults(result(text, false)).build());
        }

        List<Integer> audioChunkSizes() {
            return requests.stream().filter(r -> !r.getAudio().isEmpty()).map(r -> r.getAudio().size()).toList();
        }

        int audioBytes() {
            return audioChunkSizes().stream().mapToInt(Integer::intValue).sum();
        }
    }

    private static final class FakeController implements StreamController {
        volatile boolean cancelled;

        @Override
        public void cancel() {
            cancelled = true;
        }

        @Override
        public void disableAutoInboundFlowControl() {
        }

        @Override
        public void request(int count) {
        }
    }

    /** Live-style input: delivers some chunks, then blocks (radio silence) until closed or interrupted. */
    private static final class LiveAudio extends InputStream {
        private final CountDownLatch closedLatch = new CountDownLatch(1);
        private int chunksLeft;
        volatile boolean closed;

        LiveAudio(int chunks) {
            this.chunksLeft = chunks;
        }

        @Override
        public int read() {
            throw new UnsupportedOperationException();
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (chunksLeft > 0) {
                chunksLeft--;
                return Math.min(length, CHUNK);
            }
            try {
                closedLatch.await();
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("interrupted");
            }
            throw new IOException("stream closed");
        }

        @Override
        public void close() {
            closed = true;
            closedLatch.countDown();
        }
    }
}
