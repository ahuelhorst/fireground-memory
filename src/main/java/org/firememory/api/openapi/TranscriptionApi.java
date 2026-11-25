package org.firememory.api.openapi;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

/**
 * API contract for transcription endpoints. Holds routing and OpenAPI documentation;
 * implemented by {@link org.firememory.api.TranscriptionController}.
 */
@Tag(name = "Transcription", description = "Radio transmission transcription")
@RequestMapping("/v1/fgi")
public interface TranscriptionApi {

    @Operation(
            summary = "Transcribe a WAV file as a live stream",
            description = "Uploads a WAV recording and streams its transcript back as Server-Sent Events. "
                    + "While someone is speaking, events named `partial` carry the speech provider's running "
                    + "guess at the sentence in progress, updated several times a second. Each `partial` "
                    + "replaces the previous one, and its text can still change (e.g. \"on sea\" becomes "
                    + "\"on scene\"). When the sentence is finished, an unnamed event carries the final text, "
                    + "which replaces the current partial and never changes. Audio is sent to the "
                    + "speech provider at real-time pace, so a 2-minute file streams over ~2 minutes. "
                    + "The file must be 16-bit PCM WAV, 8–48 kHz, mono or stereo (radio recordings are "
                    + "typically 8 kHz mono). The stream closes when transcription finishes. "
                    + "If transcription fails after the stream has started, a final event named `error` "
                    + "is sent whose `data` is a human-readable message, then the stream closes. "
                    + "During silence the server sends `:keepalive` SSE comment lines (default every 15 s); "
                    + "clients should ignore them. Closing the connection stops transcription. "
                    + "Recordings of any length are supported."
    )
    @ApiResponse(
            responseCode = "200",
            description = "Event stream. Event named `partial`: `data` is the sentence in progress (plain text), "
                    + "replacing the previous partial; it may change. Unnamed events: `data` is one finished "
                    + "transcript sentence (plain text), replacing the current partial. "
                    + "Event named `error`: `data` is a failure message; it is always the last event.",
            content = @Content(
                    mediaType = MediaType.TEXT_EVENT_STREAM_VALUE,
                    schema = @Schema(type = "string", example = "event:partial\ndata:Engine 1\n\n"
                            + "event:partial\ndata:Engine 1 on sea\n\n"
                            + "data:Engine 1 on scene, working fire\n\n"
                            + "event:error\ndata:Transcription failed: PERMISSION_DENIED\n\n")
            )
    )
    @ApiResponse(responseCode = "400", description = "The `file` part is missing or malformed", content = @Content)
    @ApiResponse(
            responseCode = "415",
            description = "The file is not a 16-bit PCM WAV (8–48 kHz, mono or stereo). No stream is opened.",
            content = @Content(
                    mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)
            )
    )
    @PostMapping(
            value = "/transcribe-file",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE
    )
    SseEmitter transcribeFile(
            @Parameter(description = "WAV file: 16-bit PCM, 8–48 kHz, mono or stereo")
            @RequestParam("file") MultipartFile file) throws IOException;
}
