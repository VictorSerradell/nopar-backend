package com.company.nopar.push;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Prueba FcmPushSender contra un servidor HTTP local que hace de Google (el endpoint del token OAuth y la API de FCM):
 * así se comprueba lo que de verdad viaja por la red, sin depender de Firebase.
 */
class FcmPushSenderTest {

    private static final String PROJECT = "test-project";
    private static final String CLIENT_EMAIL = "nopar@test-project.iam.gserviceaccount.com";
    private static final String TOKEN_OK = "{\"access_token\":\"ya29.fake\",\"expires_in\":3600,\"token_type\":\"Bearer\"}";
    private static final String SEND_OK = "{\"name\":\"projects/test-project/messages/1\"}";
    private static final String UNREGISTERED = "{\"error\":{\"code\":404,\"message\":\"Requested entity was not found.\","
            + "\"status\":\"NOT_FOUND\",\"details\":[{\"@type\":\"type.googleapis.com/google.firebase.fcm.v1.FcmError\","
            + "\"errorCode\":\"UNREGISTERED\"}]}}";

    private static KeyPair keys;

    @TempDir
    Path tempDir;

    private HttpServer server;
    private final List<Request> tokenRequests = new CopyOnWriteArrayList<>();
    private final List<Request> sendRequests = new CopyOnWriteArrayList<>();
    private volatile Function<Request, Reply> tokenEndpoint = request -> new Reply(200, TOKEN_OK);
    private volatile Function<Request, Reply> sendEndpoint = request -> new Reply(200, SEND_OK);

    private final PushNotification notification =
            PushNotification.forAnnouncement(5L, "Lockers", "Recordad cerrar la puerta");

    private record Request(String authorization, String contentType, String body) {
    }

    private record Reply(int status, String body) {
    }

    @BeforeAll
    static void generateKeys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keys = generator.generateKeyPair();
    }

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/token", exchange -> respond(exchange, tokenRequests, tokenEndpoint));
        server.createContext("/v1/projects/" + PROJECT + "/messages:send",
                exchange -> respond(exchange, sendRequests, sendEndpoint));
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    // ---------------------------------------------------------------- envío

    @Test
    void givenSeveralTokens_whenSend_thenExchangesTheJwtOnceAndSendsOneRequestPerDevice() throws Exception {
        FcmPushSender sender = sender();

        List<String> unregistered = sender.send(notification, List.of("tok-1", "tok-2"));

        assertThat(unregistered).isEmpty();
        assertThat(tokenRequests).hasSize(1);
        assertThat(sendRequests).hasSize(2);
        assertThat(sendRequests).allSatisfy(request -> {
            assertThat(request.authorization()).isEqualTo("Bearer ya29.fake");
            assertThat(request.contentType()).startsWith("application/json");
        });
        assertThat(sendRequests.get(0).body())
                .contains("\"token\":\"tok-1\"")
                .contains("\"title\":\"Lockers\"")
                .contains("\"body\":\"Recordad cerrar la puerta\"")
                .contains("\"type\":\"announcement\"")
                .contains("\"announcementId\":\"5\"")
                .contains("\"priority\":\"HIGH\"");
        assertThat(sendRequests.get(1).body()).contains("\"token\":\"tok-2\"");
    }

    @Test
    void givenTwoSends_whenTheAccessTokenIsStillValid_thenItIsReused() throws Exception {
        FcmPushSender sender = sender();

        sender.send(notification, List.of("tok-1"));
        sender.send(notification, List.of("tok-2"));

        assertThat(tokenRequests).hasSize(1);
        assertThat(sendRequests).hasSize(2);
    }

    @Test
    void whenSend_thenTheAssertionIsAJwtSignedWithTheServiceAccountKeyForTheFirebaseScope() throws Exception {
        sender().send(notification, List.of("tok-1"));

        Map<String, String> form = parseForm(tokenRequests.get(0).body());
        assertThat(form).containsEntry("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer");
        Claims claims = Jwts.parser().verifyWith(keys.getPublic()).build()
                .parseSignedClaims(form.get("assertion")).getPayload();
        assertThat(claims.getIssuer()).isEqualTo(CLIENT_EMAIL);
        assertThat(claims.get("scope", String.class)).isEqualTo("https://www.googleapis.com/auth/firebase.messaging");
        assertThat(claims.getAudience()).containsExactly(baseUrl() + "/token");
        // Google rechaza la aserción si "aud" es una lista: tiene que ir como cadena
        String payload = new String(java.util.Base64.getUrlDecoder().decode(form.get("assertion").split("\\.")[1]),
                java.nio.charset.StandardCharsets.UTF_8);
        assertThat(payload).contains("\"aud\":\"" + baseUrl() + "/token\"");
        assertThat(claims.getExpiration()).isAfter(claims.getIssuedAt());
    }

    @Test
    void givenADeviceThatFcmDoesNotKnowAnymore_whenSend_thenItIsReturnedForDeletionAndTheRestStillGetSent() throws Exception {
        sendEndpoint = request -> request.body().contains("tok-bad") ? new Reply(404, UNREGISTERED) : new Reply(200, SEND_OK);

        List<String> unregistered = sender().send(notification, List.of("tok-ok-1", "tok-bad", "tok-ok-2"));

        assertThat(unregistered).containsExactly("tok-bad");
        assertThat(sendRequests).hasSize(3);
    }

    @Test
    void givenOtherErrors_whenSend_thenNothingIsReportedAsInvalidAndTheRestStillGetSent() throws Exception {
        sendEndpoint = request -> {
            if (request.body().contains("tok-500")) {
                return new Reply(500, "{\"error\":{\"status\":\"INTERNAL\"}}");
            }
            if (request.body().contains("tok-400")) {
                return new Reply(400, "{\"error\":{\"status\":\"INVALID_ARGUMENT\"}}");
            }
            return new Reply(200, SEND_OK);
        };

        List<String> unregistered = sender().send(notification, List.of("tok-500", "tok-400", "tok-ok"));

        assertThat(unregistered).isEmpty();
        assertThat(sendRequests).hasSize(3);
    }

    @Test
    void givenAnExpiredAccessToken_whenFcmAnswers401_thenGetsANewOneAndRetriesOnce() throws Exception {
        AtomicInteger tokens = new AtomicInteger();
        AtomicInteger sends = new AtomicInteger();
        tokenEndpoint = request -> new Reply(200,
                "{\"access_token\":\"ya29." + tokens.incrementAndGet() + "\",\"expires_in\":3600}");
        sendEndpoint = request -> sends.incrementAndGet() == 1
                ? new Reply(401, "{\"error\":{\"status\":\"UNAUTHENTICATED\"}}")
                : new Reply(200, SEND_OK);

        List<String> unregistered = sender().send(notification, List.of("tok-1"));

        assertThat(unregistered).isEmpty();
        assertThat(tokenRequests).hasSize(2);
        assertThat(sendRequests).extracting(Request::authorization).containsExactly("Bearer ya29.1", "Bearer ya29.2");
    }

    @Test
    void givenA401ThatPersists_whenSend_thenItGivesUpAfterTheRetry() throws Exception {
        sendEndpoint = request -> new Reply(401, "{\"error\":{\"status\":\"UNAUTHENTICATED\"}}");

        List<String> unregistered = sender().send(notification, List.of("tok-1"));

        assertThat(unregistered).isEmpty();
        assertThat(sendRequests).hasSize(2);
    }

    @Test
    void givenGoogleRefusesTheAccessToken_whenSend_thenNothingIsSentAndNothingIsReportedInvalid() throws Exception {
        tokenEndpoint = request -> new Reply(401, "{\"error\":\"invalid_grant\"}");

        List<String> unregistered = sender().send(notification, List.of("tok-1", "tok-2"));

        assertThat(unregistered).isEmpty();
        assertThat(sendRequests).isEmpty();
    }

    // ---------------------------------------------------------------- credenciales

    @Test
    void givenAMissingFile_whenCreate_thenFailsClearly() {
        Path missing = tempDir.resolve("no-existe.json");

        assertThatThrownBy(() -> new FcmPushSender(missing, baseUrl()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no-existe.json");
    }

    @Test
    void givenAFileThatIsNotJson_whenCreate_thenFailsClearly() throws Exception {
        Path file = write("esto no es json");

        assertThatThrownBy(() -> new FcmPushSender(file, baseUrl()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("credenciales de FCM");
    }

    @Test
    void givenCredentialsWithoutPrivateKey_whenCreate_thenFailsNamingTheMissingField() throws Exception {
        Path file = write("{\"project_id\":\"p\",\"client_email\":\"a@b.c\",\"token_uri\":\"http://localhost/token\"}");

        assertThatThrownBy(() -> new FcmPushSender(file, baseUrl()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("private_key");
    }

    @Test
    void givenAnInvalidPrivateKey_whenCreate_thenFailsClearly() throws Exception {
        Path file = write("{\"project_id\":\"p\",\"client_email\":\"a@b.c\",\"token_uri\":\"http://localhost/token\","
                + "\"private_key\":\"-----BEGIN PRIVATE KEY-----\\nAAAA\\n-----END PRIVATE KEY-----\\n\"}");

        assertThatThrownBy(() -> new FcmPushSender(file, baseUrl()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("clave privada");
    }

    // ---------------------------------------------------------------- utilidades

    private FcmPushSender sender() throws IOException {
        return new FcmPushSender(credentialsFile(), baseUrl());
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private Path credentialsFile() throws IOException {
        String pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8))
                        .encodeToString(keys.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
        return write("{\"type\":\"service_account\",\"project_id\":\"" + PROJECT + "\",\"private_key_id\":\"key-1\","
                + "\"private_key\":\"" + pem.replace("\n", "\\n") + "\",\"client_email\":\"" + CLIENT_EMAIL + "\","
                + "\"token_uri\":\"" + baseUrl() + "/token\"}");
    }

    private Path write(String content) throws IOException {
        return Files.writeString(tempDir.resolve("credentials-" + System.nanoTime() + ".json"), content);
    }

    private static void respond(HttpExchange exchange, List<Request> log, Function<Request, Reply> endpoint)
            throws IOException {
        Request request = new Request(
                exchange.getRequestHeaders().getFirst("Authorization"),
                exchange.getRequestHeaders().getFirst("Content-Type"),
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        log.add(request);
        Reply reply = endpoint.apply(request);
        byte[] body = reply.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(reply.status(), body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static Map<String, String> parseForm(String body) {
        Map<String, String> form = new LinkedHashMap<>();
        for (String pair : body.split("&")) {
            String[] parts = pair.split("=", 2);
            form.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8), URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
        }
        return form;
    }
}
