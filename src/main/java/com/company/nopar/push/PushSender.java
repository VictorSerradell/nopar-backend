package com.company.nopar.push;

import java.util.Collection;
import java.util.List;

/**
 * Envía notificaciones push. La implementación real habla con Firebase Cloud Messaging (FcmPushSender); si no hay
 * credenciales se usa una que no envía nada (NoopPushSender) y el resto de la aplicación funciona igual.
 */
public interface PushSender {

    /**
     * Envía la notificación a cada token. Un fallo con un token no impide enviar a los demás ni lanza una excepción.
     *
     * @return los tokens que el proveedor ha dado por no registrados (la app se desinstaló o el token caducó) y que
     *         conviene borrar; vacío si no hay ninguno
     */
    List<String> send(PushNotification notification, Collection<String> tokens);
}
