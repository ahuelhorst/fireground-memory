package org.firememory.client;

import com.google.api.gax.rpc.ClientStream;
import com.google.api.gax.rpc.ResponseObserver;
import com.google.api.gax.rpc.StreamController;
import com.google.cloud.speech.v2.*;
import com.google.protobuf.ByteString;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.firememory.model.AudioSource;
import org.firememory.config.GoogleSpeechProperties;
import org.firememory.model.TranscriptUpdate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * {@link TranscriptionClient} backed by Google Cloud Speech-to-Text v2 streaming recognition.
 * <p>
 * One {@code transcribe} call is one session, which may span several Google streams: Google
 * closes a stream after ~5 minutes, so the session restarts it at a pause after the soft limit,
 * or at the hard limit regardless. Each stream's final results are delivered before the next
 * stream opens, so transcripts stay in order. Interim results are enabled: while someone is
 * speaking, Google's running guess is delivered as a partial {@link TranscriptUpdate} (its pieces
 * joined into one line, repeats skipped); only finished sentences count as pauses for stream
 * restarts. Audio is sent as fast as it arrives; pacing
 * recorded files to real time is the caller's job ({@code RealTimePacingService}).
 * Cancelling the returned future cancels the active Google stream and stops sending.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GoogleSpeechClient implements TranscriptionClient {

    /** Audio sent per request. 50 ms of 48 kHz stereo = 9.6 KB, under Google's 15 KB per-request limit. */
    static final int CHUNK_MILLIS = 50;

    /** How long to wait for a closing stream's final results before moving on. */
    static final Duration FINAL_RESULTS_TIMEOUT = Duration.ofSeconds(10);

    private final SpeechClient speechClient;
    private final GoogleSpeechProperties properties;

    @Override
    public CompletableFuture<Void> transcribe(AudioSource audio, Consumer<TranscriptUpdate> onTranscript) {
        Session session = new Session(audio, onTranscript);
        session.start();
        return session.completion;
    }

    /** State for one transcribe call: the audio, the caller's future, and the current Google stream. */
    private final class Session {

        private final AudioSource audio;
        private final Consumer<TranscriptUpdate> onTranscript;
        private final CompletableFuture<Void> completion = new CompletableFuture<>();
        private final AtomicInteger transcriptCount = new AtomicInteger();
        private final AtomicLong totalBytes = new AtomicLong();
        private int streamCount;
        private volatile GoogleStream current;
        private volatile Thread sender;
        /** Last partial delivered since the last finished sentence; used to skip repeats. */
        private volatile String lastPartial;

        Session(AudioSource audio, Consumer<TranscriptUpdate> onTranscript) {
            this.audio = audio;
            this.onTranscript = onTranscript;
        }

        void start() {
            log.info("Starting Google streaming recognition: {} Hz, {} channel(s), model={}, language={}, recognizer={}",
                    audio.sampleRateHertz(), audio.channelCount(),
                    properties.speech().model(), properties.speech().language(), recognizerName());

            completion.whenComplete((ignored, error) -> {
                if (error instanceof CancellationException) {
                    onCancelled();
                }
            });
            // Virtual thread: blocking reads (live audio, paced files) must not tie up a platform thread.
            sender = Thread.ofVirtual().name("speech-audio-sender").start(this::run);
        }

        /** Reads audio and sends it to Google, rotating streams before Google's time limit. */
        private void run() {
            int frameBytes = audio.channelCount() * AudioSource.BYTES_PER_SAMPLE;
            int chunkBytes = Math.max(frameBytes, audio.bytesPerSecond() * CHUNK_MILLIS / 1000 / frameBytes * frameBytes);
            byte[] buffer = new byte[chunkBytes];
            long softLimitBytes = bytesFor(properties.speech().streamSoftLimit());
            long hardLimitBytes = bytesFor(properties.speech().streamHardLimit());

            try (audio) {
                GoogleStream stream = openStream();
                long streamBytes = 0;
                boolean softLimitReached = false;
                int bytesRead;

                while (!completion.isDone() && (bytesRead = audio.data().readNBytes(buffer, 0, chunkBytes)) > 0) {
                    if (stream.endedByGoogle()) {
                        log.warn("Google ended stream #{} early; opening a new one", stream.number);
                        stream = openStream();
                        streamBytes = 0;
                        softLimitReached = false;
                    }

                    stream.sendAudio(buffer, bytesRead);
                    totalBytes.addAndGet(bytesRead);
                    streamBytes += bytesRead;

                    if (!softLimitReached && streamBytes >= softLimitBytes) {
                        softLimitReached = true;
                        stream.finalResultSeen.set(false); // from now on, wait for the next finished sentence
                    }
                    boolean atPause = softLimitReached && stream.finalResultSeen.get();
                    if (atPause || streamBytes >= hardLimitBytes) {
                        log.info("Rotating Google stream #{} -> #{} after {} ms of audio ({})",
                                stream.number, stream.number + 1, millisFor(streamBytes),
                                atPause ? "pause" : "hard limit");
                        if (!stream.finishAndAwaitFinalResults()) {
                            return; // session already failed or was cancelled
                        }
                        stream = openStream();
                        streamBytes = 0;
                        softLimitReached = false;
                    }
                }

                if (completion.isDone()) {
                    return; // cancelled or failed while sending
                }
                log.info("Sent {} ms of audio to Google; waiting for final results", millisFor(totalBytes.get()));
                stream.finish();
                stream.done.whenComplete((ignored, error) -> {
                    if (error == null) {
                        succeed();
                    }
                });
            } catch (Exception error) {
                if (completion.isCancelled()) {
                    return; // interrupted or closed by cancellation: expected
                }
                if (error instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                log.error("Failed while sending audio to Google; closing stream", error);
                GoogleStream stream = current;
                if (stream != null) {
                    stream.abort(error);
                }
                fail(error);
            }
        }

        private GoogleStream openStream() {
            GoogleStream stream = new GoogleStream(++streamCount);
            stream.requests = speechClient.streamingRecognizeCallable().splitCall(stream);
            // First request on every stream: recognizer and configuration.
            stream.requests.send(StreamingRecognizeRequest.newBuilder()
                    .setRecognizer(recognizerName())
                    .setStreamingConfig(streamingConfig(audio))
                    .build());
            current = stream;
            log.debug("Opened Google stream #{}", stream.number);
            return stream;
        }

        private void deliverFinal(String text) {
            if (!completion.isDone()) {
                lastPartial = null;
                transcriptCount.incrementAndGet();
                onTranscript.accept(TranscriptUpdate.finalText(text));
            }
        }

        private void deliverPartial(String text) {
            if (!completion.isDone() && !text.equals(lastPartial)) {
                lastPartial = text;
                onTranscript.accept(TranscriptUpdate.partial(text));
            }
        }

        private void succeed() {
            int count = transcriptCount.get();
            if (count == 0) {
                log.warn("Transcription finished with no transcripts. Check that the audio contains "
                        + "audible speech and that its WAV header (sample rate/channels) is correct.");
            } else {
                log.info("Transcription finished: {} transcript(s), {} Google stream(s), {} ms of audio",
                        count, streamCount, millisFor(totalBytes.get()));
            }
            completion.complete(null);
        }

        private void fail(Throwable error) {
            if (completion.completeExceptionally(error)) {
                closeAudioQuietly(); // unblock the sender if it is waiting for live audio
            }
        }

        private void onCancelled() {
            log.info("Transcription cancelled by caller after {} ms of audio", millisFor(totalBytes.get()));
            GoogleStream stream = current;
            if (stream != null) {
                stream.cancel();
            }
            Thread thread = sender;
            if (thread != null) {
                thread.interrupt();
            }
            closeAudioQuietly();
        }

        private void closeAudioQuietly() {
            try {
                audio.close();
            } catch (IOException error) {
                log.debug("Error closing audio input", error);
            }
        }

        private long bytesFor(Duration duration) {
            return duration.toMillis() * audio.bytesPerSecond() / 1000;
        }

        private long millisFor(long bytes) {
            return bytes * 1000 / audio.bytesPerSecond();
        }

        /** One Google streaming call: receives Google's responses and sends our requests. */
        private final class GoogleStream implements ResponseObserver<StreamingRecognizeResponse> {

            private final int number;
            private final CompletableFuture<Void> done = new CompletableFuture<>();
            private final AtomicBoolean finalResultSeen = new AtomicBoolean();
            private volatile boolean closedByUs;
            private volatile boolean cancelledByUs;
            private volatile StreamController controller;
            private ClientStream<StreamingRecognizeRequest> requests;

            GoogleStream(int number) {
                this.number = number;
            }

            @Override
            public void onStart(StreamController controller) {
                this.controller = controller;
            }

            @Override
            public void onResponse(StreamingRecognizeResponse response) {
                log.debug("Google stream #{} response: speechEventType={}, results={}",
                        number, response.getSpeechEventType(), response.getResultsCount());

                // Google may split its in-progress guess into several non-final pieces: join them.
                StringBuilder partial = new StringBuilder();
                for (StreamingRecognitionResult result : response.getResultsList()) {
                    String text = result.getAlternativesList().isEmpty()
                            ? "" : result.getAlternatives(0).getTranscript().strip();
                    log.debug("Google stream #{} result: final={}, stability={}, text=\"{}\"",
                            number, result.getIsFinal(), result.getStability(), text);

                    if (text.isEmpty()) {
                        continue;
                    }
                    if (result.getIsFinal()) {
                        finalResultSeen.set(true);
                        deliverFinal(text);
                    } else {
                        if (!partial.isEmpty()) {
                            partial.append(' ');
                        }
                        partial.append(text);
                    }
                }
                if (!partial.isEmpty()) {
                    deliverPartial(partial.toString());
                }
            }

            @Override
            public void onError(Throwable error) {
                done.completeExceptionally(error);
                if (cancelledByUs || completion.isCancelled()) {
                    log.debug("Google stream #{} closed after cancel: {}", number, error.getMessage());
                    return;
                }
                log.error("Google streaming recognition failed (model={}, location={})",
                        properties.speech().model(), properties.speech().location(), error);
                fail(error);
            }

            @Override
            public void onComplete() {
                log.debug("Google stream #{} completed", number);
                done.complete(null);
            }

            void sendAudio(byte[] buffer, int length) {
                requests.send(StreamingRecognizeRequest.newBuilder()
                        .setAudio(ByteString.copyFrom(buffer, 0, length))
                        .build());
            }

            /** True if Google completed this stream without us closing it. */
            boolean endedByGoogle() {
                return !closedByUs && done.isDone() && !done.isCompletedExceptionally();
            }

            /** Half-closes the stream; Google then finalizes all audio already sent. */
            void finish() {
                closedByUs = true;
                requests.closeSend();
            }

            /** @return false if the session should stop (stream failed or interrupted) */
            boolean finishAndAwaitFinalResults() {
                finish();
                try {
                    done.get(FINAL_RESULTS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                    return true;
                } catch (TimeoutException error) {
                    log.warn("Google stream #{} did not finish within {}; cancelling it and continuing",
                            number, FINAL_RESULTS_TIMEOUT);
                    cancel();
                    return true;
                } catch (ExecutionException error) {
                    return false; // onError already failed the session
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }

            /** Stops the stream immediately, discarding any pending results. */
            void cancel() {
                closedByUs = true;
                cancelledByUs = true;
                StreamController streamController = controller;
                if (streamController != null) {
                    streamController.cancel();
                } else if (requests != null) {
                    requests.closeSendWithError(new CancellationException("Transcription cancelled"));
                }
            }

            void abort(Throwable error) {
                if (!closedByUs) {
                    closedByUs = true;
                    requests.closeSendWithError(error);
                }
            }
        }
    }

    private String recognizerName() {
        return String.format("projects/%s/locations/%s/recognizers/_",
                properties.projectId(), properties.speech().location());
    }

    private StreamingRecognitionConfig streamingConfig(AudioSource audio) {
        RecognitionConfig recognitionConfig = RecognitionConfig.newBuilder()
                .setExplicitDecodingConfig(
                        ExplicitDecodingConfig.newBuilder()
                                .setEncoding(ExplicitDecodingConfig.AudioEncoding.LINEAR16)
                                .setSampleRateHertz(audio.sampleRateHertz())
                                .setAudioChannelCount(audio.channelCount())
                                .build()
                )
                .addLanguageCodes(properties.speech().language())
                .setModel(properties.speech().model())
                .build();

        return StreamingRecognitionConfig.newBuilder()
                .setConfig(recognitionConfig)
                .setStreamingFeatures(StreamingRecognitionFeatures.newBuilder().setInterimResults(true))
                .build();
    }
}
