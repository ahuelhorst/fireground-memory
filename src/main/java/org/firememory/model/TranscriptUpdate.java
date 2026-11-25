package org.firememory.model;

/**
 * One piece of transcript from a speech provider, in provider-neutral form.
 * <ul>
 *   <li><b>Partial</b> ({@code isFinal == false}): the provider's running guess at the sentence
 *       currently being spoken. Each partial replaces the previous one, and its text can still
 *       change (e.g. "on sea" becomes "on scene").</li>
 *   <li><b>Final</b> ({@code isFinal == true}): a finished sentence. It replaces the current
 *       partial and never changes again.</li>
 * </ul>
 *
 * @param text    non-blank transcript text
 * @param isFinal whether this is a finished sentence
 */
public record TranscriptUpdate(String text, boolean isFinal) {

    public static TranscriptUpdate partial(String text) {
        return new TranscriptUpdate(text, false);
    }

    public static TranscriptUpdate finalText(String text) {
        return new TranscriptUpdate(text, true);
    }
}
