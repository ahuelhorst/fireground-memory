package org.firememory.service;

import org.firememory.model.AudioSource;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.InterruptedIOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link RealTimePacingService}: reads take as long as the audio would play.
 * Audio is 8 kHz 16-bit mono, i.e. 16,000 bytes per second.
 */
class RealTimePacingServiceTest {

    private final RealTimePacingService pacingService = new RealTimePacingService();

    @Test
    void readsNoFasterThanRealTimeAndPreservesData() throws Exception {
        byte[] pcm = new byte[3_200]; // 200 ms of audio
        for (int i = 0; i < pcm.length; i++) {
            pcm[i] = (byte) i;
        }

        long start = System.nanoTime();
        byte[] read;
        try (AudioSource paced = pacingService.pacedToRealTime(radioAudio(pcm))) {
            read = paced.data().readAllBytes();
        }
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertThat(read).isEqualTo(pcm);
        assertThat(elapsedMillis).isBetween(180L, 1_500L);
    }

    @Test
    void pacingIsDeliveredPerChunkNotAllAtTheEnd() throws Exception {
        try (AudioSource paced = pacingService.pacedToRealTime(radioAudio(new byte[1_600]))) {
            byte[] chunk = new byte[800]; // 50 ms

            long start = System.nanoTime();
            assertThat(paced.data().readNBytes(chunk, 0, chunk.length)).isEqualTo(800);
            long firstChunkMillis = (System.nanoTime() - start) / 1_000_000;

            assertThat(firstChunkMillis).isBetween(40L, 500L);
        }
    }

    @Test
    void interruptStopsReadingAndKeepsInterruptFlag() throws Exception {
        try (AudioSource paced = pacingService.pacedToRealTime(radioAudio(new byte[16_000]))) {
            InputStream data = paced.data();
            Thread.currentThread().interrupt();

            assertThatThrownBy(() -> data.read(new byte[1_600]))
                    .isInstanceOf(InterruptedIOException.class);
            assertThat(Thread.interrupted()).isTrue(); // also clears it for other tests
        }
    }

    @Test
    void keepsFormatAndWrapsTheOriginalStream() {
        AudioSource original = new AudioSource(new ByteArrayInputStream(new byte[0]), 44_100, 2);

        AudioSource paced = pacingService.pacedToRealTime(original);

        assertThat(paced.sampleRateHertz()).isEqualTo(44_100);
        assertThat(paced.channelCount()).isEqualTo(2);
        assertThat(paced.data()).isNotSameAs(original.data());
    }

    @Test
    void rejectsAudioWithNoSampleRate() {
        AudioSource broken = new AudioSource(new ByteArrayInputStream(new byte[0]), 0, 1);

        assertThatThrownBy(() -> pacingService.pacedToRealTime(broken))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static AudioSource radioAudio(byte[] pcm) {
        return new AudioSource(new ByteArrayInputStream(pcm), 8_000, 1);
    }
}
