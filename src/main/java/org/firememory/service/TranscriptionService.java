package org.firememory.service;

import lombok.RequiredArgsConstructor;
import org.firememory.model.AudioSource;
import org.firememory.client.TranscriptionClient;
import org.firememory.model.TranscriptUpdate;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Transport-neutral transcription. Callers supply audio of any origin (uploaded file, live
 * stream) as an {@link AudioSource} and receive transcript updates through a callback, so the
 * same method serves the SSE file endpoint today and a live endpoint later.
 */
@Service
@RequiredArgsConstructor
public class TranscriptionService {

    private final TranscriptionClient transcriptionClient;

    /**
     * @param audio        PCM audio and its format
     * @param onTranscript called with each update, in order: partial results for the sentence in
     *                     progress, and each finished sentence
     * @return completes when transcription finishes, or exceptionally on failure;
     *         cancel it to stop transcription (e.g. when the client disconnects)
     */
    public CompletableFuture<Void> transcribe(AudioSource audio, Consumer<TranscriptUpdate> onTranscript) {
        return transcriptionClient.transcribe(audio, onTranscript);
    }

}
