package org.firememory.api;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.firememory.model.TranscriptUpdate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Adapts a callback-based transcription to Server-Sent Events.
 * <ul>
 *   <li>Each finished sentence becomes one unnamed SSE event.</li>
 *   <li>Each partial result (sentence still being spoken) becomes an event named {@code partial};
 *       it replaces the previous partial, and is replaced by the next finished sentence.</li>
 *   <li>On failure, a final event named {@code error} carries a short message, then the stream closes.</li>
 *   <li>A {@code :keepalive} comment is sent periodically so a dropped client is detected during
 *       radio silence, and idle mobile/proxy connections stay open.</li>
 *   <li>When the connection ends for any reason, the transcription future is cancelled so the
 *       speech provider stops working (and billing).</li>
 * </ul>
 */
@Slf4j
@Component
public class SseTranscriptPublisher {

    static final String ERROR_EVENT = "error";
    static final String PARTIAL_EVENT = "partial";
    static final String KEEPALIVE_COMMENT = "keepalive";

    private final Duration heartbeatInterval;
    private final ScheduledExecutorService heartbeats =
            Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("sse-heartbeat").factory());

    public SseTranscriptPublisher(@Value("${fireground.sse.heartbeat-interval:15s}") Duration heartbeatInterval) {
        this.heartbeatInterval = heartbeatInterval;
    }

    @PreDestroy
    void shutdown() {
        heartbeats.shutdownNow();
    }

    /**
     * @param transcription starts transcription given a transcript callback; exceptions it throws
     *                      synchronously propagate to the caller (no stream is opened)
     */
    public SseEmitter stream(Function<Consumer<TranscriptUpdate>, CompletableFuture<Void>> transcription) {
        SseEmitter emitter = new SseEmitter(0L);
        AtomicReference<CompletableFuture<Void>> running = new AtomicReference<>();

        Runnable stopTranscription = () -> {
            CompletableFuture<Void> future = running.get();
            if (future != null && future.cancel(true)) {
                log.info("SSE client disconnected; cancelled transcription");
            }
        };

        Consumer<TranscriptUpdate> sendEvent = update -> {
            try {
                emitter.send(update.isFinal()
                        ? SseEmitter.event().data(update.text())
                        : SseEmitter.event().name(PARTIAL_EVENT).data(update.text()));
            } catch (Exception error) {
                log.info("Could not send transcript to client (likely disconnected): {}", error.getMessage());
                stopTranscription.run();
                emitter.completeWithError(error);
            }
        };

        CompletableFuture<Void> future = transcription.apply(sendEvent);
        running.set(future);

        long intervalMillis = heartbeatInterval.toMillis();
        ScheduledFuture<?> heartbeat = heartbeats.scheduleAtFixedRate(
                () -> sendHeartbeat(emitter, stopTranscription), intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);

        Runnable onConnectionClosed = () -> {
            heartbeat.cancel(false);
            stopTranscription.run();
        };
        emitter.onCompletion(onConnectionClosed);
        emitter.onTimeout(onConnectionClosed);
        emitter.onError(error -> onConnectionClosed.run());

        future.whenComplete((ignored, error) -> {
            heartbeat.cancel(false);
            if (error == null) {
                emitter.complete();
                return;
            }
            Throwable cause = unwrap(error);
            if (cause instanceof CancellationException) {
                log.debug("Transcription cancelled; closing SSE stream");
                emitter.complete();
            } else {
                sendErrorEventAndClose(emitter, cause);
            }
        });

        return emitter;
    }

    private static void sendHeartbeat(SseEmitter emitter, Runnable stopTranscription) {
        try {
            emitter.send(SseEmitter.event().comment(KEEPALIVE_COMMENT));
        } catch (Exception error) {
            log.info("SSE heartbeat failed (client likely disconnected): {}", error.getMessage());
            stopTranscription.run();
            emitter.completeWithError(error);
        }
    }

    private static void sendErrorEventAndClose(SseEmitter emitter, Throwable error) {
        log.warn("Transcription failed; sending error event to client", error);
        try {
            emitter.send(SseEmitter.event().name(ERROR_EVENT).data("Transcription failed: " + error.getMessage()));
            emitter.complete();
        } catch (Exception sendFailure) {
            emitter.completeWithError(error);
        }
    }

    private static Throwable unwrap(Throwable error) {
        return (error instanceof CompletionException && error.getCause() != null) ? error.getCause() : error;
    }
}
