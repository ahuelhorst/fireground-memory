package org.firememory.api;

import java.io.IOException;

import lombok.RequiredArgsConstructor;
import org.firememory.api.openapi.TranscriptionApi;
import org.firememory.model.AudioSource;
import org.firememory.service.AudioService;
import org.firememory.service.RealTimePacingService;
import org.firememory.service.TranscriptionService;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * HTTP endpoint for transcribing an uploaded WAV file. Decodes the upload into an
 * {@link AudioSource}, paces it to real time (it is a recording, not live audio), and streams
 * transcripts back over SSE. Routes and OpenAPI docs live on {@link TranscriptionApi}.
 */
@RestController
@RequiredArgsConstructor
public class TranscriptionController implements TranscriptionApi {

    private final AudioService audioService;
    private final RealTimePacingService pacingService;
    private final TranscriptionService transcriptionService;
    private final SseTranscriptPublisher sseTranscriptPublisher;

    @Override
    public SseEmitter transcribeFile(MultipartFile file) throws IOException {
        AudioSource recording = audioService.readWav(file.getInputStream());
        AudioSource realTimeAudio = pacingService.pacedToRealTime(recording);

        // The publisher opens the SSE stream, then starts transcription with a callback
        // that sends each transcript update to the client.
        return sseTranscriptPublisher.stream(sendToClient ->
                transcriptionService.transcribe(realTimeAudio, sendToClient));
    }

}
