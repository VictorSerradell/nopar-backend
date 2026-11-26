package com.company.nopar.config;

import com.company.nopar.push.FcmPushSender;
import com.company.nopar.push.NoopPushSender;
import com.company.nopar.push.PushSender;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/**
 * Elige cómo se envían las notificaciones push. Con application.push.fcm.enabled=true se usa Firebase Cloud
 * Messaging con la cuenta de servicio de application.push.fcm.credentials-file (la ruta del JSON) o, si no hay
 * fichero, de application.push.fcm.credentials-json (el propio JSON o su Base64, para donde no hay ficheros secretos);
 * si están las dos, manda el fichero. En cualquier otro caso no se envía nada y el resto de la aplicación funciona
 * igual. Si está activado y las credenciales no valen, la aplicación no arranca.
 */
@Slf4j
@Configuration
public class PushConfig {

    @Bean
    public PushSender pushSender(
            @Value("${application.push.fcm.enabled:false}") String enabled,
            @Value("${application.push.fcm.credentials-file:}") String credentialsFile,
            @Value("${application.push.fcm.credentials-json:}") String credentialsJson,
            @Value("${application.push.fcm.api-base-url:" + FcmPushSender.DEFAULT_API_BASE_URL + "}") String apiBaseUrl) {
        if (!Boolean.parseBoolean(enabled.strip())) {
            log.info("Notificaciones push desactivadas (FCM_ENABLED=true y FCM_CREDENTIALS_FILE o FCM_CREDENTIALS_JSON las activan)");
            return new NoopPushSender();
        }
        PushSender sender;
        if (!credentialsFile.isBlank()) {
            sender = new FcmPushSender(Path.of(credentialsFile.strip()), apiBaseUrl);
        } else if (!credentialsJson.isBlank()) {
            sender = FcmPushSender.fromCredentialsJson(credentialsJson, apiBaseUrl);
        } else {
            throw new IllegalStateException("FCM_ENABLED=true pero faltan las credenciales: FCM_CREDENTIALS_FILE "
                    + "(la ruta del JSON de la cuenta de servicio de Firebase) o FCM_CREDENTIALS_JSON (el propio JSON, "
                    + "tal cual o en Base64)");
        }
        log.info("Notificaciones push activadas con Firebase Cloud Messaging");
        return sender;
    }
}
