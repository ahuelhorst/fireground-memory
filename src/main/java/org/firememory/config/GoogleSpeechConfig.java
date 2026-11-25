package org.firememory.config;


import com.google.api.gax.core.FixedCredentialsProvider;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.speech.v2.SpeechClient;
import com.google.cloud.speech.v2.SpeechSettings;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.Collections;

/**
 * Creates the Google {@link SpeechClient} from the service-account key file named in
 * {@link GoogleSpeechProperties}. Google SDK wiring stays here and in the {@code client} package.
 */
@Configuration
@EnableConfigurationProperties(GoogleSpeechProperties.class)
public class GoogleSpeechConfig {

    @Bean(destroyMethod = "close")
    public SpeechClient speechClient(GoogleSpeechProperties properties) throws IOException {

        GoogleCredentials credentials;
        try (FileInputStream input = new FileInputStream(properties.credentialsPath())) {
            credentials = GoogleCredentials.fromStream(input)
                    .createScoped(Collections.singletonList("https://www.googleapis.com/auth/cloud-platform"));
        }

        FixedCredentialsProvider credentialsProvider = FixedCredentialsProvider.create(credentials);
        SpeechSettings settings = SpeechSettings.newBuilder()
                .setCredentialsProvider(credentialsProvider)
                .build();

        return SpeechClient.create(settings);
    }
}
