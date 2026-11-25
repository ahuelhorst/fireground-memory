package org.firememory.api;

import org.firememory.model.AudioSource;
import org.firememory.service.AudioService;
import org.firememory.service.RealTimePacingService;
import org.firememory.service.TestWav;
import org.firememory.service.TranscriptionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.hamcrest.Matchers.containsString;

/**
 * Verifies {@link SseTranscriptPublisher} sends {@code :keepalive} comments during silence.
 * Separate class so the short heartbeat interval doesn't affect exact-output assertions elsewhere.
 */
@WebMvcTest(controllers = TranscriptionController.class, properties = "fireground.sse.heartbeat-interval=50ms")
@Import({AudioService.class, RealTimePacingService.class, SseTranscriptPublisher.class})
class SseHeartbeatTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TranscriptionService transcriptionService;

    @Test
    void sendsKeepaliveCommentsWhileNoTranscriptsArrive() throws Exception {
        // 500 ms of "radio silence", starting when transcription starts (not at test setup,
        // so a slow first request can't eat the silence before any heartbeat is due).
        when(transcriptionService.transcribe(any(AudioSource.class), any())).thenAnswer(invocation ->
                CompletableFuture.runAsync(() -> { }, CompletableFuture.delayedExecutor(500, TimeUnit.MILLISECONDS)));
        MockMultipartFile wav = new MockMultipartFile("file", "radio.wav", "audio/wav", TestWav.pcm16(8_000, 1, 100));

        MvcResult result = mockMvc.perform(multipart("/v1/fgi/transcribe-file").file(wav))
                .andExpect(request().asyncStarted())
                .andReturn();
        result.getAsyncResult(2_000);

        mockMvc.perform(asyncDispatch(result))
                .andExpect(content().string(containsString(":keepalive\n\n")));
    }
}
