package com.company.nopar.service;

import com.company.nopar.entity.RefreshToken;
import com.company.nopar.entity.User;
import com.company.nopar.exception.InvalidRefreshTokenException;
import com.company.nopar.repository.RefreshTokenRepository;
import com.company.nopar.repository.UserRepository;
import com.company.nopar.service.RefreshTokenService.IssuedRefreshToken;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceTest {

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private RefreshTokenService service;

    private final User user = driver();

    @Test
    void givenUser_whenCreateRefreshToken_thenOnlyTheHashIsPersisted() {
        IssuedRefreshToken issued = service.createRefreshToken(user);

        ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenRepository).save(captor.capture());
        RefreshToken saved = captor.getValue();

        assertThat(issued.user()).isSameAs(user);
        assertThat(issued.token()).hasSize(43).matches("[A-Za-z0-9_-]+");
        assertThat(saved.getTokenHash())
                .isEqualTo(RefreshTokenService.hash(issued.token()))
                .isNotEqualTo(issued.token());
        assertThat(saved.getUser()).isSameAs(user);

        Instant expectedExpiry = Instant.now().plus(Duration.ofDays(7));
        assertThat(saved.getExpiryDate())
                .isBetween(expectedExpiry.minusSeconds(60), expectedExpiry.plusSeconds(60));
    }

    @Test
    void givenTwoIssues_whenCreateRefreshToken_thenTokensAreDifferent() {
        IssuedRefreshToken first = service.createRefreshToken(user);
        IssuedRefreshToken second = service.createRefreshToken(user);

        assertThat(first.token()).isNotEqualTo(second.token());
    }

    @Test
    void givenValidToken_whenRotate_thenOldOneIsConsumedAndNewOneIsIssued() {
        String presented = "token-en-claro-del-cliente";
        String hash = RefreshTokenService.hash(presented);
        when(refreshTokenRepository.findByTokenHash(hash))
                .thenReturn(Optional.of(stored(hash, Instant.now().plusSeconds(3600))));
        when(refreshTokenRepository.deleteByTokenHash(hash)).thenReturn(1);

        IssuedRefreshToken rotated = service.rotateRefreshToken(presented);

        assertThat(rotated.token()).isNotEqualTo(presented);
        assertThat(rotated.user()).isSameAs(user);
        verify(refreshTokenRepository).deleteByTokenHash(hash);
        verify(refreshTokenRepository).save(any(RefreshToken.class));
    }

    @Test
    void givenUnknownToken_whenRotate_thenInvalidAndNothingIsIssued() {
        assertThatThrownBy(() -> service.rotateRefreshToken("desconocido"))
                .isInstanceOf(InvalidRefreshTokenException.class);

        verify(refreshTokenRepository, never()).deleteByTokenHash(any());
        verify(refreshTokenRepository, never()).save(any());
    }

    @Test
    void givenNullOrBlankToken_whenRotate_thenInvalidWithoutTouchingTheDatabase() {
        assertThatThrownBy(() -> service.rotateRefreshToken(null))
                .isInstanceOf(InvalidRefreshTokenException.class);
        assertThatThrownBy(() -> service.rotateRefreshToken("   "))
                .isInstanceOf(InvalidRefreshTokenException.class);

        verifyNoInteractions(refreshTokenRepository);
    }

    @Test
    void givenExpiredToken_whenRotate_thenItIsDeletedAndRejected() {
        String presented = "token-caducado";
        String hash = RefreshTokenService.hash(presented);
        when(refreshTokenRepository.findByTokenHash(hash))
                .thenReturn(Optional.of(stored(hash, Instant.now().minusSeconds(60))));
        when(refreshTokenRepository.deleteByTokenHash(hash)).thenReturn(1);

        assertThatThrownBy(() -> service.rotateRefreshToken(presented))
                .isInstanceOf(InvalidRefreshTokenException.class);

        verify(refreshTokenRepository).deleteByTokenHash(hash);
        verify(refreshTokenRepository, never()).save(any());
    }

    @Test
    void givenTokenConsumedByAConcurrentRequest_whenRotate_thenRejected() {
        String presented = "token-usado-a-la-vez";
        String hash = RefreshTokenService.hash(presented);
        when(refreshTokenRepository.findByTokenHash(hash))
                .thenReturn(Optional.of(stored(hash, Instant.now().plusSeconds(3600))));
        when(refreshTokenRepository.deleteByTokenHash(hash)).thenReturn(0); // otra petición ya lo borró

        assertThatThrownBy(() -> service.rotateRefreshToken(presented))
                .isInstanceOf(InvalidRefreshTokenException.class);

        verify(refreshTokenRepository, never()).save(any());
    }

    @Test
    void rotate_mustNotRollbackOnInvalidToken_otherwiseTheExpiredTokenDeletionIsLost() throws NoSuchMethodException {
        Transactional transactional = RefreshTokenService.class
                .getMethod("rotateRefreshToken", String.class)
                .getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.noRollbackFor()).contains(InvalidRefreshTokenException.class);
    }

    @Test
    void hash_isDeterministicSha256InHex() {
        assertThat(RefreshTokenService.hash("a"))
                .isEqualTo("ca978112ca1bbdcafac231b39a23dc4da786eff8147c4e72b9807785afee48bb");
    }

    @Test
    void givenRefreshToken_whenRevoke_thenDeletesItByHashAndNeverByItsRawValue() {
        service.revokeRefreshToken("token-del-movil");

        verify(refreshTokenRepository).deleteByTokenHash(RefreshTokenService.hash("token-del-movil"));
        verify(refreshTokenRepository, never()).deleteByUser(any());
        verifyNoInteractions(userRepository);
    }

    @Test
    void givenExpiredTokens_whenDeleteExpiredTokens_thenDeletesTheOnesBeforeNowAndReturnsHowMany() {
        when(refreshTokenRepository.deleteExpiredBefore(any(Instant.class))).thenReturn(3);
        Instant before = Instant.now();

        int deleted = service.deleteExpiredTokens();

        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(refreshTokenRepository).deleteExpiredBefore(cutoff.capture());
        assertThat(deleted).isEqualTo(3);
        assertThat(cutoff.getValue()).isBetween(before, Instant.now());
    }

    private RefreshToken stored(String hash, Instant expiry) {
        RefreshToken token = new RefreshToken();
        token.setId(1L);
        token.setTokenHash(hash);
        token.setExpiryDate(expiry);
        token.setUser(user);
        return token;
    }

    private static User driver() {
        User driver = new User();
        driver.setId(7L);
        driver.setUsername("conductor1");
        driver.setRole(User.Role.DRIVER);
        return driver;
    }
}
