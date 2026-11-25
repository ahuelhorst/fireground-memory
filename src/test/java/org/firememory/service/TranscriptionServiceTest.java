package org.firememory.service;

import org.firememory.model.AudioSource;
import org.firememory.client.TranscriptionClient;
import org.firememory.model.TranscriptUpdate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TranscriptionService} with a mocked {@link TranscriptionClient}.
 * No web or Spring context: the service is transport-neutral.
 */
@ExtendWith(MockitoExtension.class)
class TranscriptionServiceTest {

    private static final AudioSource LIVE_RADIO_PCM =
            new AudioSource(new ByteArrayInputStream(new byte[1_600]), 8_000, 1);

    @Mock
    private TranscriptionClient transcriptionClient;

    @InjectMocks
    private TranscriptionService service;

    @Test
    void deliversTranscriptsInOrderAndCompletes() throws Exception {
        when(transcriptionClient.transcribe(same(LIVE_RADIO_PCM), any())).thenAnswer(invocation -> {
            Consumer<TranscriptUpdate> onTranscript = invocation.getArgument(1);
            onTranscript.accept(TranscriptUpdate.partial("Engine 1 on"));
            onTranscript.accept(TranscriptUpdate.finalText("Engine 1 on scene"));
            onTranscript.accept(TranscriptUpdate.finalText("Working fire, second floor"));
            return CompletableFuture.completedFuture(null);
        });
        List<TranscriptUpdate> transcripts = new ArrayList<>();

        CompletableFuture<Void> done = service.transcribe(LIVE_RADIO_PCM, transcripts::add);

        assertThat(done).isCompleted();
        done.get();
        assertThat(transcripts).containsExactly(
                TranscriptUpdate.partial("Engine 1 on"),
                TranscriptUpdate.finalText("Engine 1 on scene"),
                TranscriptUpdate.finalText("Working fire, second floor"));
    }

    @Test
    void propagatesProviderFailure() {
        when(transcriptionClient.transcribe(any(), any()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("provider unavailable")));

        CompletableFuture<Void> done = service.transcribe(LIVE_RADIO_PCM, text -> { });

        assertThatThrownBy(done::get)
                .isInstanceOf(ExecutionException.class)
                .hasRootCauseMessage("provider unavailable");
    }

    @Test
    void returnsClientFutureSoCallersCanCancelTranscription() {
        CompletableFuture<Void> clientFuture = new CompletableFuture<>();
        when(transcriptionClient.transcribe(any(), any())).thenReturn(clientFuture);

        CompletableFuture<Void> done = service.transcribe(LIVE_RADIO_PCM, text -> { });
        done.cancel(true);

        assertThat(clientFuture).isCancelled();
    }

    @Test
    void completesWithNoTranscriptsWhenNoSpeechRecognized() {
        when(transcriptionClient.transcribe(any(), any())).thenReturn(CompletableFuture.completedFuture(null));
        List<TranscriptUpdate> transcripts = new ArrayList<>();

        CompletableFuture<Void> done = service.transcribe(LIVE_RADIO_PCM, transcripts::add);

        assertThat(done).isCompleted();
        assertThat(transcripts).isEmpty();
    }
}
