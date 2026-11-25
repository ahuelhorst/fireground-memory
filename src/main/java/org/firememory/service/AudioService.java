package org.firememory.service;

import org.firememory.exception.UnsupportedAudioException;
import org.firememory.model.AudioSource;
import org.springframework.stereotype.Service;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Turns audio files into {@link AudioSource}s. Reads a WAV file header and returns the PCM data
 * with its real format. Accepts only 16-bit signed PCM WAV, 8–48 kHz, mono or stereo.
 */
@Service
public class AudioService {

    static final int MIN_SAMPLE_RATE = 8_000;
    static final int MAX_SAMPLE_RATE = 48_000;

    private static final String EXPECTED = "Expected a 16-bit PCM WAV file (8–48 kHz, mono or stereo).";

    /**
     * @throws UnsupportedAudioException if the input is not a supported WAV file
     */
    public AudioSource readWav(InputStream input) {
        // AudioSystem needs mark/reset support to sniff the header.
        InputStream buffered = new BufferedInputStream(input);
        try {
            AudioFileFormat fileFormat = AudioSystem.getAudioFileFormat(buffered);
            if (fileFormat.getType() != AudioFileFormat.Type.WAVE) {
                throw new UnsupportedAudioException("Not a WAV file (" + fileFormat.getType() + "). " + EXPECTED);
            }

            AudioFormat format = fileFormat.getFormat();
            validate(format);

            AudioInputStream pcm = AudioSystem.getAudioInputStream(buffered);
            return new AudioSource(pcm, Math.round(format.getSampleRate()), format.getChannels());

        } catch (UnsupportedAudioFileException error) {
            throw new UnsupportedAudioException("Unrecognized audio file. " + EXPECTED, error);
        } catch (IOException error) {
            throw new UnsupportedAudioException("Could not read audio file. " + EXPECTED, error);
        }
    }

    private static void validate(AudioFormat format) {
        if (!AudioFormat.Encoding.PCM_SIGNED.equals(format.getEncoding())
                || format.getSampleSizeInBits() != 16
                || format.isBigEndian()) {
            throw new UnsupportedAudioException(
                    "Unsupported WAV encoding (" + format.getEncoding() + ", "
                            + format.getSampleSizeInBits() + "-bit). " + EXPECTED);
        }
        if (format.getChannels() < 1 || format.getChannels() > 2) {
            throw new UnsupportedAudioException(
                    "Unsupported channel count (" + format.getChannels() + "). " + EXPECTED);
        }
        float rate = format.getSampleRate();
        if (rate < MIN_SAMPLE_RATE || rate > MAX_SAMPLE_RATE) {
            throw new UnsupportedAudioException(
                    "Unsupported sample rate (" + Math.round(rate) + " Hz). " + EXPECTED);
        }
    }
}
