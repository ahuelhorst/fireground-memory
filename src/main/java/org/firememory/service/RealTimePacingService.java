package org.firememory.service;

import org.firememory.model.AudioSource;
import org.springframework.stereotype.Service;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.time.Duration;

/**
 * Slows recorded audio to real-time speed, so a file behaves like a live feed: bytes covering the
 * first second of audio are only returned once one second has passed.
 * <p>
 * Apply only to recorded input (files). Live input already arrives in real time and must not
 * be paced again, or any backlog could never catch up.
 */
@Service
public class RealTimePacingService {

    /**
     * Returns a copy of {@code audio}, with the same format, whose data is read no faster than real time.
     */
    public AudioSource pacedToRealTime(AudioSource audio) {
        InputStream paced = new PacedInputStream(audio.data(), audio.bytesPerSecond());
        return new AudioSource(paced, audio.sampleRateHertz(), audio.channelCount());
    }

    /** Uses an absolute schedule from the first read, so timing never drifts. */
    private static final class PacedInputStream extends FilterInputStream {

        private final long bytesPerSecond;
        private long startNanos = -1;
        private long bytesDelivered;

        PacedInputStream(InputStream in, int bytesPerSecond) {
            super(in);
            if (bytesPerSecond <= 0) {
                throw new IllegalArgumentException("bytesPerSecond must be positive");
            }
            this.bytesPerSecond = bytesPerSecond;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) {
                pace(1);
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int count = super.read(buffer, offset, length);
            if (count > 0) {
                pace(count);
            }
            return count;
        }

        /** Waits until the audio just read would have finished playing in real time. */
        private void pace(int count) throws InterruptedIOException {
            if (startNanos < 0) {
                startNanos = System.nanoTime();
            }
            bytesDelivered += count;
            long dueNanos = startNanos + bytesDelivered * 1_000_000_000L / bytesPerSecond;
            long waitNanos = dueNanos - System.nanoTime();
            if (waitNanos > 0) {
                try {
                    Thread.sleep(Duration.ofNanos(waitNanos));
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException("Interrupted while pacing audio");
                }
            }
        }
    }
}
