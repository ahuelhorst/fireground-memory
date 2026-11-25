package org.firememory.service;

import org.firememory.exception.UnsupportedAudioException;
import org.firememory.model.AudioSource;
import org.junit.jupiter.api.Test;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link AudioService#readWav}: accepted WAV formats, header stripping, and rejections.
 */
class AudioServiceTest {

    private final AudioService audioService = new AudioService();

    @Test
    void readsTypicalRadioWav8kHzMono() throws Exception {
        try (AudioSource audio = audioService.readWav(new ByteArrayInputStream(TestWav.pcm16(8_000, 1, 500)))) {
            assertThat(audio.sampleRateHertz()).isEqualTo(8_000);
            assertThat(audio.channelCount()).isEqualTo(1);
            // Header is consumed: only PCM remains (0.5 s * 8000 Hz * 2 bytes).
            assertThat(audio.data().readAllBytes()).hasSize(8_000);
        }
    }

    @Test
    void reads44kHzStereoWav() throws Exception {
        try (AudioSource audio = audioService.readWav(new ByteArrayInputStream(TestWav.pcm16(44_100, 2, 100)))) {
            assertThat(audio.sampleRateHertz()).isEqualTo(44_100);
            assertThat(audio.channelCount()).isEqualTo(2);
        }
    }

    @Test
    void rejects8BitWav() {
        byte[] wav = TestWav.write(new AudioFormat(8_000, 8, 1, false, false), 100, AudioFileFormat.Type.WAVE);

        assertThatThrownBy(() -> audioService.readWav(new ByteArrayInputStream(wav)))
                .isInstanceOf(UnsupportedAudioException.class)
                .hasMessageContaining("8-bit");
    }

    @Test
    void rejects24BitWav() {
        byte[] wav = TestWav.write(new AudioFormat(16_000, 24, 1, true, false), 100, AudioFileFormat.Type.WAVE);

        assertThatThrownBy(() -> audioService.readWav(new ByteArrayInputStream(wav)))
                .isInstanceOf(UnsupportedAudioException.class)
                .hasMessageContaining("24-bit");
    }

    @Test
    void rejectsSampleRateAbove48kHz() {
        assertThatThrownBy(() -> audioService.readWav(new ByteArrayInputStream(TestWav.pcm16(96_000, 1, 50))))
                .isInstanceOf(UnsupportedAudioException.class)
                .hasMessageContaining("96000 Hz");
    }

    @Test
    void rejectsNonWavAudioFile() {
        byte[] aiff = TestWav.write(new AudioFormat(16_000, 16, 1, true, true), 100, AudioFileFormat.Type.AIFF);

        assertThatThrownBy(() -> audioService.readWav(new ByteArrayInputStream(aiff)))
                .isInstanceOf(UnsupportedAudioException.class)
                .hasMessageContaining("Not a WAV file");
    }

    @Test
    void rejectsMp3() {
        byte[] mp3Like = "ID3\u0004\u0000\u0000\u0000\u0000\u0000\u0000 not really audio"
                .getBytes(StandardCharsets.ISO_8859_1);

        assertThatThrownBy(() -> audioService.readWav(new ByteArrayInputStream(mp3Like)))
                .isInstanceOf(UnsupportedAudioException.class);
    }

    @Test
    void rejectsEmptyUpload() {
        assertThatThrownBy(() -> audioService.readWav(new ByteArrayInputStream(new byte[0])))
                .isInstanceOf(UnsupportedAudioException.class);
    }
}
