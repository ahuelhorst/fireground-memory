package org.firememory.api;

import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import org.firememory.model.AudioSource;
import org.firememory.model.TranscriptUpdate;
import org.firememory.service.AudioService;
import org.firememory.service.RealTimePacingService;
import org.firememory.service.TestWav;
import org.firememory.service.TranscriptionService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockAsyncContext;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-layer tests for POST /v1/fgi/transcribe-file: WAV decoding, SSE output and error contract.
 * Uses the real {@link AudioService}, {@link RealTimePacingService} (spied) and
 * {@link SseTranscriptPublisher}; the transcription service is mocked.
 */
@WebMvcTest(TranscriptionController.class)
@Import({AudioService.class, RealTimePacingService.class, SseTranscriptPublisher.class})
class TranscriptionControllerTest {

    private static final MockMultipartFile RADIO_WAV =
            new MockMultipartFile("file", "radio.wav", "audio/wav", TestWav.pcm16(8_000, 1, 100));

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TranscriptionService transcriptionService;

    @MockitoSpyBean
    private RealTimePacingService pacingService;

    @Test
    void streamsEachTranscriptAsAnSseEventInOrder() throws Exception {
        when(transcriptionService.transcribe(any(AudioSource.class), any())).thenAnswer(invocation -> {
            Consumer<TranscriptUpdate> onTranscript = invocation.getArgument(1);
            onTranscript.accept(TranscriptUpdate.finalText("Engine 1 on scene"));
            onTranscript.accept(TranscriptUpdate.finalText("Working fire, second floor"));
            return CompletableFuture.completedFuture(null);
        });

        MvcResult result = mockMvc.perform(multipart("/v1/fgi/transcribe-file").file(RADIO_WAV))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andExpect(content().string("data:Engine 1 on scene\n\ndata:Working fire, second floor\n\n"));
    }

    @Test
    void streamsPartialResultsAsNamedEventsAndFinishedSentencesAsPlainData() throws Exception {
        when(transcriptionService.transcribe(any(AudioSource.class), any())).thenAnswer(invocation -> {
            Consumer<TranscriptUpdate> onTranscript = invocation.getArgument(1);
            onTranscript.accept(TranscriptUpdate.partial("Engine 1"));
            onTranscript.accept(TranscriptUpdate.partial("Engine 1 on sea"));
            onTranscript.accept(TranscriptUpdate.finalText("Engine 1 on scene"));
            return CompletableFuture.completedFuture(null);
        });

        MvcResult result = mockMvc.perform(multipart("/v1/fgi/transcribe-file").file(RADIO_WAV))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().string("event:partial\ndata:Engine 1\n\n"
                        + "event:partial\ndata:Engine 1 on sea\n\n"
                        + "data:Engine 1 on scene\n\n"));
    }

    @Test
    void passesWavFormatToService() throws Exception {
        when(transcriptionService.transcribe(any(AudioSource.class), any()))
                .thenReturn(CompletableFuture.completedFuture(null));

        mockMvc.perform(multipart("/v1/fgi/transcribe-file").file(RADIO_WAV))
                .andExpect(request().asyncStarted());

        ArgumentCaptor<AudioSource> audio = ArgumentCaptor.forClass(AudioSource.class);
        verify(transcriptionService).transcribe(audio.capture(), any());
        assertThat(audio.getValue().sampleRateHertz()).isEqualTo(8_000);
        assertThat(audio.getValue().channelCount()).isEqualTo(1);
    }

    @Test
    void sendsErrorEventThenClosesWhenTranscriptionFails() throws Exception {
        when(transcriptionService.transcribe(any(AudioSource.class), any())).thenAnswer(invocation -> {
            Consumer<TranscriptUpdate> onTranscript = invocation.getArgument(1);
            onTranscript.accept(TranscriptUpdate.finalText("Engine 1 on scene"));
            return CompletableFuture.failedFuture(new RuntimeException("PERMISSION_DENIED"));
        });

        MvcResult result = mockMvc.perform(multipart("/v1/fgi/transcribe-file").file(RADIO_WAV))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().string("data:Engine 1 on scene\n\n"
                        + "event:error\ndata:Transcription failed: PERMISSION_DENIED\n\n"));
    }

    @Test
    void errorEventUnwrapsAsyncFailureToShowRootMessage() throws Exception {
        when(transcriptionService.transcribe(any(AudioSource.class), any())).thenReturn(
                CompletableFuture.failedFuture(new CompletionException(new IllegalStateException("model not found"))));

        MvcResult result = mockMvc.perform(multipart("/v1/fgi/transcribe-file").file(RADIO_WAV))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(result))
                .andExpect(content().string("event:error\ndata:Transcription failed: model not found\n\n"));
    }

    @Test
    void completesEmptyStreamWhenNoSpeechRecognized() throws Exception {
        when(transcriptionService.transcribe(any(AudioSource.class), any()))
                .thenReturn(CompletableFuture.completedFuture(null));

        MvcResult result = mockMvc.perform(multipart("/v1/fgi/transcribe-file").file(RADIO_WAV))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().string(""));
    }

    @Test
    void transcribesThePacedRecordingNotTheRawUpload() throws Exception {
        AudioSource paced = new AudioSource(new ByteArrayInputStream(new byte[0]), 8_000, 1);
        doReturn(paced).when(pacingService).pacedToRealTime(any());
        when(transcriptionService.transcribe(any(AudioSource.class), any()))
                .thenReturn(CompletableFuture.completedFuture(null));

        mockMvc.perform(multipart("/v1/fgi/transcribe-file").file(RADIO_WAV));

        ArgumentCaptor<AudioSource> recording = ArgumentCaptor.forClass(AudioSource.class);
        verify(pacingService).pacedToRealTime(recording.capture());
        assertThat(recording.getValue().sampleRateHertz()).isEqualTo(8_000);
        verify(transcriptionService).transcribe(same(paced), any());
    }

    @Test
    void cancelsTranscriptionWhenClientDisconnects() throws Exception {
        CompletableFuture<Void> running = new CompletableFuture<>(); // never finishes on its own
        when(transcriptionService.transcribe(any(AudioSource.class), any())).thenReturn(running);

        MvcResult result = mockMvc.perform(multipart("/v1/fgi/transcribe-file").file(RADIO_WAV))
                .andExpect(request().asyncStarted())
                .andReturn();

        // Simulate the container reporting a broken connection (e.g. phone lost signal).
        MockAsyncContext asyncContext = (MockAsyncContext) result.getRequest().getAsyncContext();
        for (AsyncListener listener : asyncContext.getListeners()) {
            listener.onError(new AsyncEvent(asyncContext, new IOException("Broken pipe")));
        }

        assertThat(running).isCancelled();
    }

    @Test
    void returnsBadRequestWhenFilePartIsMissing() throws Exception {
        MockMultipartFile wrongPart = new MockMultipartFile("notFile", "radio.wav", "audio/wav", new byte[]{1});

        mockMvc.perform(multipart("/v1/fgi/transcribe-file").file(wrongPart))
                .andExpect(status().isBadRequest());

        verify(transcriptionService, never()).transcribe(any(), any());
    }

    @Test
    void returnsProblemJson415ForNonWavEvenWhenClientAcceptsOnlyEventStream() throws Exception {
        MockMultipartFile mp3 = new MockMultipartFile("file", "radio.mp3", "audio/mpeg", "ID3 not wav".getBytes());

        mockMvc.perform(multipart("/v1/fgi/transcribe-file").file(mp3).accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(415))
                .andExpect(jsonPath("$.title").value("Unsupported audio format"));

        verify(transcriptionService, never()).transcribe(any(), any());
    }
}
