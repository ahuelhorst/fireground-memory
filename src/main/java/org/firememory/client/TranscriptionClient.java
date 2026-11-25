package org.firememory.client;

import org.firememory.model.AudioSource;
import org.firememory.model.TranscriptUpdate;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Provider-neutral speech-to-text abstraction. Implementations wrap one vendor SDK and must not
 * leak vendor types; swapping providers means adding a new implementation of this interface.
 */
public interface TranscriptionClient {

    /**
     * Streams audio to the speech provider and reports transcript updates: partial results for the
     * sentence in progress (if the provider supports them) and each finalized, non-blank sentence.
     * Implementations must send audio as fast as it arrives (no pacing) and keep updates in order.
     *
     * @return completes when the provider finishes, or exceptionally on any failure.
     *         Cancelling it ({@code cancel(true)}) must stop sending audio and release provider resources.
     */
    CompletableFuture<Void> transcribe(AudioSource audio, Consumer<TranscriptUpdate> onTranscript);

}
