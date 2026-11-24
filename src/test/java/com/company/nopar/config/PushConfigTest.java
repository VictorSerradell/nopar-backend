package com.company.nopar.config;

import com.company.nopar.push.FcmPushSender;
import com.company.nopar.push.NoopPushSender;
import com.company.nopar.push.PushSender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PushConfigTest {

    private static final String BASE_URL = FcmPushSender.DEFAULT_API_BASE_URL;

    @TempDir
    Path tempDir;

    private final PushConfig config = new PushConfig();

    @Test
    void givenNothingConfigured_whenCreatingTheSender_thenNothingIsSent() {
        assertThat(config.pushSender("false", "", BASE_URL)).isInstanceOf(NoopPushSender.class);
    }

    @Test
    void givenAnEmptyOrUnknownValue_whenCreatingTheSender_thenPushStaysDisabled() {
        // una variable vacía (FCM_ENABLED=) no puede dejar la aplicación sin PushSender ni activarlo por error
        assertThat(config.pushSender("", "", BASE_URL)).isInstanceOf(NoopPushSender.class);
        assertThat(config.pushSender("  ", "", BASE_URL)).isInstanceOf(NoopPushSender.class);
        assertThat(config.pushSender("quizás", "", BASE_URL)).isInstanceOf(NoopPushSender.class);
    }

    @Test
    void givenDisabled_whenThereAreCredentials_thenTheyAreNotEvenRead() {
        assertThat(config.pushSender("false", tempDir.resolve("no-existe.json").toString(), BASE_URL))
                .isInstanceOf(NoopPushSender.class);
    }

    @Test
    void givenEnabledWithoutCredentialsFile_whenCreatingTheSender_thenTheApplicationDoesNotStart() {
        assertThatThrownBy(() -> config.pushSender("true", " ", BASE_URL))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FCM_CREDENTIALS_FILE");
    }

    @Test
    void givenEnabledWithAMissingFile_whenCreatingTheSender_thenTheApplicationDoesNotStart() {
        String missing = tempDir.resolve("no-existe.json").toString();

        assertThatThrownBy(() -> config.pushSender("true", missing, BASE_URL))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no-existe.json");
    }

    @Test
    void givenEnabledWithValidCredentials_whenCreatingTheSender_thenItIsFcm() throws Exception {
        Path file = serviceAccountFile();

        PushSender sender = config.pushSender("true", file.toString(), BASE_URL);

        assertThat(sender).isInstanceOf(FcmPushSender.class);
    }

    private Path serviceAccountFile() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keys = generator.generateKeyPair();
        String pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8))
                        .encodeToString(keys.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
        return Files.writeString(tempDir.resolve("firebase-service-account.json"),
                "{\"project_id\":\"test-project\",\"client_email\":\"nopar@test-project.iam.gserviceaccount.com\","
                        + "\"token_uri\":\"https://oauth2.googleapis.com/token\","
                        + "\"private_key\":\"" + pem.replace("\n", "\\n") + "\"}");
    }
}
