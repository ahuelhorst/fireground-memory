package org.firememory.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Typed Google Cloud settings bound from {@code google.cloud.*} in application config.
 */
@ConfigurationProperties(prefix = "google.cloud")
public record GoogleSpeechProperties(String projectId, String credentialsPath, Speech speech) {

    /**
     * @param streamSoftLimit after this much audio on one Google stream, restart it at the next
     *                        finished sentence (a pause between transmissions)
     * @param streamHardLimit restart the stream at this point regardless; must stay below
     *                        Google's ~5-minute streaming limit
     */
    public record Speech(String location,
                         String model,
                         String language,
                         @DefaultValue("240s") Duration streamSoftLimit,
                         @DefaultValue("270s") Duration streamHardLimit) {
    }
}
