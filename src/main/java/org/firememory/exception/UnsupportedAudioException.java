package org.firememory.exception;

/**
 * Thrown when uploaded audio is not in a supported format.
 */
public class UnsupportedAudioException extends RuntimeException {

    public UnsupportedAudioException(String message) {
        super(message);
    }

    public UnsupportedAudioException(String message, Throwable cause) {
        super(message, cause);
    }
}
