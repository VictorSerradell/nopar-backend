package com.company.nopar.security;

import com.company.nopar.exception.TooManyLoginAttemptsException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoginRateLimiterTest {

    private static final Duration WINDOW = Duration.ofMinutes(15);

    /** Un reloj que se mueve cuando la prueba lo dice. */
    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-11-26T10:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final MutableClock clock = new MutableClock();
    // 3 por usuario e IP, 6 por usuario, 8 por IP: números pequeños para probar cada límite
    private final LoginRateLimiter limiter = new LoginRateLimiter(true, 3, 6, 8, WINDOW, clock);

    private void fail(String username, String ip, int times) {
        for (int i = 0; i < times; i++) {
            limiter.recordFailure(username, ip);
        }
    }

    @Test
    void givenFewerFailuresThanTheLimit_whenCheck_thenAllowed() {
        fail("ana", "1.1.1.1", 2);

        assertThatCode(() -> limiter.checkAllowed("ana", "1.1.1.1")).doesNotThrowAnyException();
    }

    @Test
    void givenTheLimitOfAUserFromAnIp_whenCheck_thenBlockedForTheWholeWindowAndOthersAreNot() {
        fail("ana", "1.1.1.1", 3);

        assertThatThrownBy(() -> limiter.checkAllowed("ana", "1.1.1.1"))
                .isInstanceOfSatisfying(TooManyLoginAttemptsException.class,
                        ex -> assertThat(ex.getRetryAfter()).isEqualTo(WINDOW));
        // Otro usuario desde esa IP, y el mismo usuario desde otra IP, siguen pudiendo entrar
        assertThatCode(() -> limiter.checkAllowed("bea", "1.1.1.1")).doesNotThrowAnyException();
        assertThatCode(() -> limiter.checkAllowed("ana", "2.2.2.2")).doesNotThrowAnyException();
    }

    @Test
    void givenABlock_whenTimePasses_thenItEndsAndTheCounterStartsOver() {
        fail("ana", "1.1.1.1", 3);

        clock.advance(Duration.ofMinutes(10));
        assertThatThrownBy(() -> limiter.checkAllowed("ana", "1.1.1.1"))
                .isInstanceOfSatisfying(TooManyLoginAttemptsException.class,
                        ex -> assertThat(ex.getRetryAfter()).isEqualTo(Duration.ofMinutes(5)));

        clock.advance(Duration.ofMinutes(5));
        assertThatCode(() -> limiter.checkAllowed("ana", "1.1.1.1")).doesNotThrowAnyException();
        // Empieza de cero: hacen falta otros 3 fallos para volver a bloquear
        fail("ana", "1.1.1.1", 2);
        assertThatCode(() -> limiter.checkAllowed("ana", "1.1.1.1")).doesNotThrowAnyException();
    }

    @Test
    void givenFailuresSpreadOverMoreThanTheWindow_whenCheck_thenTheyDoNotAddUp() {
        fail("ana", "1.1.1.1", 2);
        clock.advance(WINDOW);
        fail("ana", "1.1.1.1", 2);

        assertThatCode(() -> limiter.checkAllowed("ana", "1.1.1.1")).doesNotThrowAnyException();
    }

    @Test
    void givenASuccessfulLogin_whenCheck_thenTheUserFromThatIpStartsOverFromZero() {
        fail("ana", "1.1.1.1", 2);
        limiter.recordSuccess("ana", "1.1.1.1");
        fail("ana", "1.1.1.1", 2);

        assertThatCode(() -> limiter.checkAllowed("ana", "1.1.1.1")).doesNotThrowAnyException();
    }

    @Test
    void givenManyIpsGuessingOneUser_whenTheUserLimitIsReached_thenBlockedFromAnyIp() {
        // 6 fallos contra "ana", cada uno desde una IP distinta: ninguna IP llega a su límite, el usuario sí
        for (int i = 1; i <= 6; i++) {
            limiter.recordFailure("ana", "9.9.9." + i);
        }

        assertThatThrownBy(() -> limiter.checkAllowed("ana", "5.5.5.5")).isInstanceOf(TooManyLoginAttemptsException.class);
        assertThatCode(() -> limiter.checkAllowed("bea", "5.5.5.5")).doesNotThrowAnyException();
    }

    @Test
    void givenOneIpGuessingManyUsers_whenTheIpLimitIsReached_thenEveryoneFromThatIpIsBlocked() {
        for (int i = 1; i <= 8; i++) {
            limiter.recordFailure("usuario" + i, "3.3.3.3");
        }

        assertThatThrownBy(() -> limiter.checkAllowed("otro", "3.3.3.3")).isInstanceOf(TooManyLoginAttemptsException.class);
        assertThatCode(() -> limiter.checkAllowed("otro", "4.4.4.4")).doesNotThrowAnyException();
    }

    @Test
    void givenDifferentCaseAndSpaces_whenCount_thenItIsTheSameUser() {
        limiter.recordFailure("Ana", "1.1.1.1");
        limiter.recordFailure(" ana ", "1.1.1.1");
        limiter.recordFailure("ANA", "1.1.1.1");

        assertThatThrownBy(() -> limiter.checkAllowed("ana", "1.1.1.1")).isInstanceOf(TooManyLoginAttemptsException.class);
    }

    @Test
    void givenNullUsernameOrIp_whenUsed_thenItDoesNotFail() {
        assertThatCode(() -> {
            limiter.recordFailure(null, null);
            limiter.checkAllowed(null, null);
            limiter.recordSuccess(null, null);
        }).doesNotThrowAnyException();
    }

    @Test
    void givenItIsDisabled_whenTooManyFailures_thenNeverBlocks() {
        LoginRateLimiter off = new LoginRateLimiter(false, 1, 1, 1, WINDOW, clock);

        off.recordFailure("ana", "1.1.1.1");
        off.recordFailure("ana", "1.1.1.1");

        assertThatCode(() -> off.checkAllowed("ana", "1.1.1.1")).doesNotThrowAnyException();
    }

    @Test
    void whenReset_thenEverythingIsForgotten() {
        fail("ana", "1.1.1.1", 3);

        limiter.reset();

        assertThatCode(() -> limiter.checkAllowed("ana", "1.1.1.1")).doesNotThrowAnyException();
    }
}
