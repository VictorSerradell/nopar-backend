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
        assertThat(config.pushSender("false", "", "", BASE_URL)).isInstanceOf(NoopPushSender.class);
    }

    @Test
    void givenAnEmptyOrUnknownValue_whenCreatingTheSender_thenPushStaysDisabled() {
        // una variable vacía (FCM_ENABLED=) no puede dejar la aplicación sin PushSender ni activarlo por error
        assertThat(config.pushSender("", "", "", BASE_URL)).isInstanceOf(NoopPushSender.class);
        assertThat(config.pushSender("  ", "", "", BASE_URL)).isInstanceOf(NoopPushSender.class);
        assertThat(config.pushSender("quizás", "", "", BASE_URL)).isInstanceOf(NoopPushSender.class);
    }

    @Test
    void givenDisabled_whenThereAreCredentials_thenTheyAreNotEvenRead() {
        assertThat(config.pushSender("false", tempDir.resolve("no-existe.json").toString(), "", BASE_URL))
                .isInstanceOf(NoopPushSender.class);
    }

    @Test
    void givenEnabledWithoutCredentialsFile_whenCreatingTheSender_thenTheApplicationDoesNotStart() {
        assertThatThrownBy(() -> config.pushSender("true", " ", "", BASE_URL))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FCM_CREDENTIALS_FILE");
    }

    @Test
    void givenEnabledWithAMissingFile_whenCreatingTheSender_thenTheApplicationDoesNotStart() {
        String missing = tempDir.resolve("no-existe.json").toString();

        assertThatThrownBy(() -> config.pushSender("true", missing, "", BASE_URL))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no-existe.json");
    }

    @Test
    void givenEnabledWithValidCredentials_whenCreatingTheSender_thenItIsFcm() throws Exception {
        Path file = serviceAccountFile();

        PushSender sender = config.pushSender("true", file.toString(), "", BASE_URL);

        assertThat(sender).isInstanceOf(FcmPushSender.class);
    }

    @Test
    void givenEnabledWithoutAnyCredentials_whenCreatingTheSender_thenTheMessageNamesBothWays() {
        assertThatThrownBy(() -> config.pushSender("true", "", "  ", BASE_URL))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FCM_CREDENTIALS_FILE")
                .hasMessageContaining("FCM_CREDENTIALS_JSON");
    }

    @Test
    void givenEnabledWithTheJsonInAVariable_whenCreatingTheSender_thenItIsFcm() throws Exception {
        PushSender sender = config.pushSender("true", "", serviceAccountJson(), BASE_URL);

        assertThat(sender).isInstanceOf(FcmPushSender.class);
    }

    @Test
    void givenEnabledWithTheJsonInBase64_whenCreatingTheSender_thenItIsFcm() throws Exception {
        String base64 = Base64.getEncoder().encodeToString(serviceAccountJson().getBytes(StandardCharsets.UTF_8));

        assertThat(config.pushSender("true", "", base64, BASE_URL)).isInstanceOf(FcmPushSender.class);
        // una variable pegada con saltos de línea o espacios alrededor tampoco rompe nada
        assertThat(config.pushSender("true", "", "\n  " + base64 + "  \n", BASE_URL)).isInstanceOf(FcmPushSender.class);
    }

    @Test
    void givenAnInvalidJsonVariable_whenCreatingTheSender_thenTheApplicationDoesNotStartAndNothingIsQuoted() {
        String secret = "SECRETO-QUE-NO-DEBE-SALIR-EN-EL-LOG";

        assertThatThrownBy(() -> config.pushSender("true", "", "{\"private_key\": \"" + secret, BASE_URL))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FCM_CREDENTIALS_JSON")
                .hasMessageNotContaining(secret)
                .hasNoCause();
        assertThatThrownBy(() -> config.pushSender("true", "", "esto no es JSON ni Base64 de un JSON", BASE_URL))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FCM_CREDENTIALS_JSON");
    }

    @Test
    void givenAJsonVariableWithoutTheRequiredFields_whenCreatingTheSender_thenTheMessageNamesTheField() {
        assertThatThrownBy(() -> config.pushSender("true", "", "{\"project_id\": \"test-project\"}", BASE_URL))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FCM_CREDENTIALS_JSON")
                .hasMessageContaining("client_email");
    }

    @Test
    void givenBothAFileAndAJsonVariable_whenCreatingTheSender_thenTheFileWins() throws Exception {
        Path file = serviceAccountFile();

        assertThat(config.pushSender("true", file.toString(), "basura que no se llega a leer", BASE_URL))
                .isInstanceOf(FcmPushSender.class);
    }

    @Test
    void givenDisabled_whenTheJsonVariableIsInvalid_thenItIsNotEvenRead() {
        assertThat(config.pushSender("false", "", "basura", BASE_URL)).isInstanceOf(NoopPushSender.class);
    }

    private Path serviceAccountFile() throws Exception {
        return Files.writeString(tempDir.resolve("firebase-service-account.json"), serviceAccountJson());
    }

    private String serviceAccountJson() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keys = generator.generateKeyPair();
        String pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8))
                        .encodeToString(keys.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
        return "{\"project_id\":\"test-project\",\"client_email\":\"nopar@test-project.iam.gserviceaccount.com\","
                + "\"token_uri\":\"https://oauth2.googleapis.com/token\","
                + "\"private_key\":\"" + pem.replace("\n", "\\n") + "\"}";
    }
}
