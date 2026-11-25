package org.firememory.service;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Builds in-memory audio files for tests.
 */
public final class TestWav {

    private TestWav() {
    }

    /** 16-bit signed little-endian PCM WAV of silence. */
    public static byte[] pcm16(int sampleRate, int channels, int millis) {
        return write(new AudioFormat(sampleRate, 16, channels, true, false), millis, AudioFileFormat.Type.WAVE);
    }

    public static byte[] write(AudioFormat format, int millis, AudioFileFormat.Type type) {
        int frames = (int) (format.getSampleRate() * millis / 1000);
        byte[] pcm = new byte[frames * format.getFrameSize()];
        try (AudioInputStream stream = new AudioInputStream(new ByteArrayInputStream(pcm), format, frames)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            AudioSystem.write(stream, type, out);
            return out.toByteArray();
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }
}
