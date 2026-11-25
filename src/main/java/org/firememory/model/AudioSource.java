package org.firememory.model;

import java.io.IOException;
import java.io.InputStream;

/**
 * Provider-neutral audio input: 16-bit signed little-endian PCM samples plus their format.
 * Built from a WAV upload by {@code AudioService}, or directly from live audio.
 *
 * @param data            PCM sample bytes only (any file header already consumed)
 * @param sampleRateHertz samples per second, e.g. 8000 for typical radio audio
 * @param channelCount    1 (mono) or 2 (stereo)
 */
public record AudioSource(InputStream data, int sampleRateHertz, int channelCount) implements AutoCloseable {

    public static final int BYTES_PER_SAMPLE = 2;

    public int bytesPerSecond() {
        return sampleRateHertz * channelCount * BYTES_PER_SAMPLE;
    }

    @Override
    public void close() throws IOException {
        data.close();
    }
}
