package com.company.nopar.push;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Envía las notificaciones con la API HTTP v1 de Firebase Cloud Messaging, sin el SDK de Firebase.
 *
 * Se autentica con la cuenta de servicio de Firebase (el JSON que se descarga de la consola): firma con su clave
 * privada un JWT (RS256), lo cambia en Google por un token de acceso de una hora y lo reutiliza hasta que caduca. La
 * API v1 no tiene envío masivo, así que se hace una petición por dispositivo, una detrás de otra: para las decenas
 * de dispositivos de una flota son pocos segundos y el envío no va en la petición del administrador, sino en
 * segundo plano.
 *
 * Un dispositivo que FCM da por no registrado (UNREGISTERED) se devuelve para que se borre. Cualquier otro error con
 * un dispositivo se anota en el log y no afecta a los demás.
 */
@Slf4j
public class FcmPushSender implements PushSender {

    public static final String DEFAULT_API_BASE_URL = "https://fcm.googleapis.com";

    private static final String SCOPE = "https://www.googleapis.com/auth/firebase.messaging";
    private static final String JWT_BEARER_GRANT = "urn:ietf:params:oauth:grant-type:jwt-bearer";
    /** Se pide un token nuevo un poco antes de que caduque el actual. */
    private static final Duration EXPIRY_MARGIN = Duration.ofMinutes(2);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final RestClient http;
    private final String clientEmail;
    private final PrivateKey privateKey;
    private final URI tokenUri;
    private final URI sendUri;

    // Protegidos por el monitor de la instancia
    private String accessToken;
    private Instant accessTokenExpiresAt = Instant.EPOCH;

    /**
     * @param credentialsFile el JSON de la cuenta de servicio de Firebase; de él salen el proyecto y la clave
     * @param apiBaseUrl      la API de FCM ({@value #DEFAULT_API_BASE_URL}); solo cambia en los tests
     * @throws IllegalStateException si el fichero no se puede leer o le faltan datos: es mejor no arrancar que
     *                               descubrir en el primer aviso que las notificaciones no funcionan
     */
    public FcmPushSender(Path credentialsFile, String apiBaseUrl) {
        this(readCredentials(credentialsFile), credentialsFile.toString(), apiBaseUrl);
    }

    /**
     * Con las credenciales en una variable de entorno (FCM_CREDENTIALS_JSON) en vez de en un fichero, para las
     * plataformas que no tienen ficheros secretos (Railway y parecidas).
     *
     * @param credentials el JSON de la cuenta de servicio de Firebase, tal cual o codificado en Base64 (más cómodo:
     *                    una variable de una sola línea)
     * @throws IllegalStateException si no se puede leer o le faltan datos. El mensaje nunca cita el contenido: podría
     *                               enseñar un trozo de la clave privada en el log
     */
    public static FcmPushSender fromCredentialsJson(String credentials, String apiBaseUrl) {
        return new FcmPushSender(parseCredentialsValue(credentials), "FCM_CREDENTIALS_JSON", apiBaseUrl);
    }

    private FcmPushSender(Map<String, Object> credentials, String source, String apiBaseUrl) {
        this.clientEmail = required(credentials, "client_email", source);
        this.tokenUri = URI.create(required(credentials, "token_uri", source));
        String projectId = required(credentials, "project_id", source);
        this.privateKey = parsePrivateKey(required(credentials, "private_key", source));
        this.sendUri = URI.create(apiBaseUrl.replaceAll("/+$", "") + "/v1/projects/" + projectId + "/messages:send");

        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                // HTTP/1.1: FCM lo admite y las peticiones van de una en una, no hace falta multiplexar
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build());
        requestFactory.setReadTimeout(Duration.ofSeconds(10));
        this.http = RestClient.builder().requestFactory(requestFactory).build();
    }

    @Override
    public List<String> send(PushNotification notification, Collection<String> tokens) {
        List<String> unregistered = new ArrayList<>();
        int sent = 0;
        int failed = 0;
        for (String token : tokens) {
            Outcome outcome = sendOne(notification, token);
            switch (outcome) {
                case SENT -> sent++;
                case UNREGISTERED -> unregistered.add(token);
                case FAILED -> failed++;
            }
        }
        log.info("FCM: {} enviados, {} dispositivos dados de baja, {} fallidos", sent, unregistered.size(), failed);
        return unregistered;
    }

    private enum Outcome { SENT, UNREGISTERED, FAILED }

    private Outcome sendOne(PushNotification notification, String token) {
        try {
            Map<String, Object> body = message(notification, token);
            ResponseEntity<String> response = postMessage(body);
            if (response.getStatusCode().value() == 401) {
                // El token de acceso caducó o se revocó antes de lo previsto: se pide otro y se reintenta una vez
                invalidateAccessToken();
                response = postMessage(body);
            }
            if (response.getStatusCode().is2xxSuccessful()) {
                return Outcome.SENT;
            }
            String responseBody = response.getBody() == null ? "" : response.getBody();
            if (response.getStatusCode().value() == 404 && responseBody.contains("UNREGISTERED")) {
                return Outcome.UNREGISTERED;
            }
            log.warn("FCM rechazó un envío (HTTP {}): {}", response.getStatusCode().value(), shorten(responseBody));
            return Outcome.FAILED;
        } catch (RuntimeException e) {
            log.warn("FCM: no se pudo enviar a un dispositivo: {}", e.toString());
            return Outcome.FAILED;
        }
    }

    private ResponseEntity<String> postMessage(Map<String, Object> body) {
        return http.post()
                .uri(sendUri)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                // Los errores HTTP no lanzan excepción: se miran el código y el cuerpo de la respuesta
                .onStatus(HttpStatusCode::isError, (request, response) -> { })
                .toEntity(String.class);
    }

    private static Map<String, Object> message(PushNotification notification, String token) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("token", token);
        message.put("notification", Map.of("title", notification.title(), "body", notification.body()));
        if (!notification.data().isEmpty()) {
            message.put("data", notification.data());
        }
        // En Android, prioridad alta: el aviso llega aunque el móvil esté en reposo
        Map<String, Object> android = new LinkedHashMap<>();
        android.put("priority", "HIGH");
        if (notification.androidChannelId() != null) {
            // Con la app cerrada es el sistema quien pinta la notificación: este es el canal que usará
            android.put("notification", Map.of("channel_id", notification.androidChannelId()));
        }
        message.put("android", android);
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("message", message);
        return envelope;
    }

    // ---------------------------------------------------------------- autenticación con la cuenta de servicio

    private synchronized String accessToken() {
        if (accessToken == null || Instant.now().isAfter(accessTokenExpiresAt.minus(EXPIRY_MARGIN))) {
            refreshAccessToken();
        }
        return accessToken;
    }

    private synchronized void invalidateAccessToken() {
        accessToken = null;
    }

    private void refreshAccessToken() {
        Instant now = Instant.now();
        String assertion = signedAssertion(now);
        String form = "grant_type=" + URLEncoder.encode(JWT_BEARER_GRANT, StandardCharsets.UTF_8)
                + "&assertion=" + URLEncoder.encode(assertion, StandardCharsets.UTF_8);

        ResponseEntity<String> response = http.post()
                .uri(tokenUri)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, httpResponse) -> { })
                .toEntity(String.class);
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException("Google no dio el token de acceso de FCM (HTTP "
                    + response.getStatusCode().value() + "): " + shorten(response.getBody()));
        }

        Map<String, Object> json = parseJson(response.getBody());
        Object token = json.get("access_token");
        if (!(token instanceof String) || ((String) token).isBlank()) {
            throw new IllegalStateException("La respuesta de Google no trae el token de acceso de FCM");
        }
        long expiresInSeconds = json.get("expires_in") instanceof Number number ? number.longValue() : 3600;
        this.accessToken = (String) token;
        this.accessTokenExpiresAt = now.plusSeconds(expiresInSeconds);
    }

    // ---------------------------------------------------------------- credenciales

    private static Map<String, Object> readCredentials(Path file) {
        try {
            return parseJson(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("No se pueden leer las credenciales de FCM en " + file
                    + " (FCM_CREDENTIALS_FILE): " + e.getMessage(), e);
        }
    }

    private static Map<String, Object> parseCredentialsValue(String value) {
        String text = value == null ? "" : value.strip();
        try {
            if (!text.startsWith("{")) {
                text = new String(Base64.getMimeDecoder().decode(text), StandardCharsets.UTF_8);
            }
            return parseJson(text);
        } catch (RuntimeException e) {
            // Sin el mensaje ni la causa del error: podrían citar un trozo de la clave privada
            throw new IllegalStateException("No se pueden leer las credenciales de FCM en FCM_CREDENTIALS_JSON: "
                    + "tiene que ser el JSON de la cuenta de servicio de Firebase, tal cual o en Base64");
        }
    }

    private static String required(Map<String, Object> credentials, String field, String source) {
        Object value = credentials.get(field);
        if (!(value instanceof String) || ((String) value).isBlank()) {
            throw new IllegalStateException("Las credenciales de FCM en " + source + " no tienen el campo «" + field
                    + "»: tiene que ser el JSON de una cuenta de servicio de Firebase");
        }
        return (String) value;
    }

    /**
     * JWT firmado con la clave de la cuenta de servicio (RS256) que se cambia por el access token de FCM. Se arma a
     * mano en vez de con el builder de JJWT: la 0.12 serializa "aud" siempre como lista, aunque tenga un solo valor,
     * y el endpoint OAuth de Google exige una cadena (responde "Failed audience check").
     */
    private String signedAssertion(Instant now) {
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("alg", "RS256");
        header.put("typ", "JWT");
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", clientEmail);
        claims.put("scope", SCOPE);
        claims.put("aud", tokenUri.toString());
        claims.put("iat", now.getEpochSecond());
        claims.put("exp", now.plus(Duration.ofHours(1)).getEpochSecond());

        Base64.Encoder base64Url = Base64.getUrlEncoder().withoutPadding();
        String signingInput = base64Url.encodeToString(JSON.writeValueAsString(header).getBytes(StandardCharsets.UTF_8))
                + "." + base64Url.encodeToString(JSON.writeValueAsString(claims).getBytes(StandardCharsets.UTF_8));
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(privateKey);
            signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            return signingInput + "." + base64Url.encodeToString(signature.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("No se pudo firmar la aserción de FCM", e);
        }
    }

    private static PrivateKey parsePrivateKey(String pem) {
        try {
            String base64 = pem.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
            byte[] der = Base64.getDecoder().decode(base64);
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("La clave privada de las credenciales de FCM no es válida", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseJson(String json) {
        Map<String, Object> parsed = JSON.readValue(json, Map.class);
        if (parsed == null) {
            throw new IllegalStateException("JSON vacío");
        }
        return parsed;
    }

    private static String shorten(String text) {
        if (text == null) {
            return "";
        }
        String oneLine = text.strip().replaceAll("\\s+", " ");
        return oneLine.length() <= 300 ? oneLine : oneLine.substring(0, 300) + "…";
    }
}
