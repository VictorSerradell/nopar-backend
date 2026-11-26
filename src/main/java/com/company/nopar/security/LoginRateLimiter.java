package com.company.nopar.security;

import com.company.nopar.exception.TooManyLoginAttemptsException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Frena los intentos de adivinar una contraseña: tras demasiados inicios de sesión fallidos se bloquea el login durante
 * un rato y se responde 429 con Retry-After. Se cuentan los fallos de tres formas a la vez, y basta que se alcance
 * cualquiera de los tres límites:
 * - por usuario desde una misma IP (5): quien se equivoca de contraseña espera un poco, sin molestar a nadie más;
 * - por usuario desde cualquier IP (20): frena a quien prueba contraseñas desde muchas IP distintas;
 * - por IP (50): frena a quien prueba muchos usuarios desde un mismo sitio. Es alto porque en una estación muchos
 *   conductores pueden salir con la misma IP (la del wifi o la del operador).
 * Un inicio de sesión correcto borra el contador de ese usuario en esa IP.
 *
 * Los contadores viven en memoria: se pierden al reiniciar y no se comparten entre instancias de la aplicación, algo
 * aceptable para una sola instancia. Se configura con application.security.login-rate-limit.* (LOGIN_RATE_LIMIT_ENABLED
 * lo apaga).
 */
@Slf4j
@Component
public class LoginRateLimiter {

    /** Con tantas entradas se purgan las caducadas; si aun así sigue lleno, se vacía (protege la memoria). */
    private static final int MAX_ENTRIES = 10_000;
    private static final int MAX_KEY_PART_LENGTH = 100;

    /** Fallos contados desde windowStart; blockedUntil != null cuando se alcanzó el límite. */
    private record Entry(Instant windowStart, int count, Instant blockedUntil) { }

    private final boolean enabled;
    private final int maxPerAccountAndIp;
    private final int maxPerAccount;
    private final int maxPerIp;
    private final Duration window;
    private final Clock clock;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    @Autowired
    public LoginRateLimiter(
            @Value("${application.security.login-rate-limit.enabled:true}") boolean enabled,
            @Value("${application.security.login-rate-limit.max-attempts:5}") int maxPerAccountAndIp,
            @Value("${application.security.login-rate-limit.max-attempts-per-account:20}") int maxPerAccount,
            @Value("${application.security.login-rate-limit.max-attempts-per-ip:50}") int maxPerIp,
            @Value("${application.security.login-rate-limit.window-minutes:15}") int windowMinutes) {
        this(enabled, maxPerAccountAndIp, maxPerAccount, maxPerIp, Duration.ofMinutes(windowMinutes), Clock.systemUTC());
    }

    LoginRateLimiter(boolean enabled, int maxPerAccountAndIp, int maxPerAccount, int maxPerIp, Duration window, Clock clock) {
        this.enabled = enabled;
        this.maxPerAccountAndIp = maxPerAccountAndIp;
        this.maxPerAccount = maxPerAccount;
        this.maxPerIp = maxPerIp;
        this.window = window;
        this.clock = clock;
    }

    /** Lanza TooManyLoginAttemptsException si ese usuario o esa IP están bloqueados; hay que llamarlo antes de autenticar. */
    public void checkAllowed(String username, String ip) {
        if (!enabled) {
            return;
        }
        Instant now = clock.instant();
        Duration longest = Duration.ZERO;
        for (String key : List.of(accountAndIpKey(username, ip), accountKey(username), ipKey(ip))) {
            Entry entry = entries.get(key);
            if (entry != null && entry.blockedUntil() != null && now.isBefore(entry.blockedUntil())) {
                Duration left = Duration.between(now, entry.blockedUntil());
                if (left.compareTo(longest) > 0) {
                    longest = left;
                }
            }
        }
        if (!longest.isZero()) {
            throw new TooManyLoginAttemptsException(longest);
        }
    }

    public void recordFailure(String username, String ip) {
        if (!enabled) {
            return;
        }
        Instant now = clock.instant();
        if (entries.size() >= MAX_ENTRIES) {
            purge(now);
        }
        register(accountAndIpKey(username, ip), maxPerAccountAndIp, now);
        register(accountKey(username), maxPerAccount, now);
        register(ipKey(ip), maxPerIp, now);
    }

    /** Un inicio de sesión correcto: ese usuario, desde esa IP, vuelve a empezar de cero. */
    public void recordSuccess(String username, String ip) {
        if (enabled) {
            entries.remove(accountAndIpKey(username, ip));
        }
    }

    /** Olvida todos los contadores. Solo para las pruebas. */
    public void reset() {
        entries.clear();
    }

    private void register(String key, int max, Instant now) {
        entries.compute(key, (k, current) -> {
            Entry base = current == null || expired(current, now) ? new Entry(now, 0, null) : current;
            int count = base.count() + 1;
            Instant blockedUntil = base.blockedUntil();
            if (count >= max && blockedUntil == null) {
                blockedUntil = now.plus(window);
                log.warn("Inicio de sesión bloqueado {} minutos tras {} intentos fallidos: {}", window.toMinutes(), count, k);
            }
            return new Entry(base.windowStart(), count, blockedUntil);
        });
    }

    private boolean expired(Entry entry, Instant now) {
        Instant end = entry.blockedUntil() != null ? entry.blockedUntil() : entry.windowStart().plus(window);
        return !now.isBefore(end);
    }

    private void purge(Instant now) {
        entries.values().removeIf(entry -> expired(entry, now));
        if (entries.size() >= MAX_ENTRIES) {
            log.warn("Demasiados contadores de intentos de login en memoria: se vacían");
            entries.clear();
        }
    }

    private static String accountAndIpKey(String username, String ip) {
        return "user-ip:" + clean(username) + "|" + clean(ip);
    }

    private static String accountKey(String username) {
        return "user:" + clean(username);
    }

    private static String ipKey(String ip) {
        return "ip:" + clean(ip);
    }

    /** Sin mayúsculas ni espacios sobrantes, acotado y sin caracteres de control (el nombre acaba en el log). */
    private static String clean(String value) {
        if (value == null) {
            return "";
        }
        String cleaned = value.strip().toLowerCase(Locale.ROOT).replaceAll("\\p{Cntrl}", "?");
        return cleaned.length() > MAX_KEY_PART_LENGTH ? cleaned.substring(0, MAX_KEY_PART_LENGTH) : cleaned;
    }
}
