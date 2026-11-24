package com.company.nopar.service;

import com.company.nopar.entity.DeviceToken;
import com.company.nopar.entity.User;
import com.company.nopar.repository.DeviceTokenRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class DeviceServiceTest {

    @Mock
    private DeviceTokenRepository deviceTokenRepository;

    @InjectMocks
    private DeviceService deviceService;

    @Test
    void givenAToken_whenRegisterDevice_thenUpsertsItWithoutSurroundingSpaces() {
        deviceService.registerDevice(driver(7L), "  token-abc  ", DeviceToken.Platform.ANDROID);

        verify(deviceTokenRepository).upsert(eq(7L), eq("token-abc"), eq("ANDROID"), any(LocalDateTime.class));
    }

    @Test
    void whenRegisterDevice_thenTheLimitPerUserIsAppliedAfterTheInsert() {
        deviceService.registerDevice(driver(7L), "token-abc", DeviceToken.Platform.IOS);

        InOrder order = inOrder(deviceTokenRepository);
        order.verify(deviceTokenRepository).upsert(eq(7L), eq("token-abc"), eq("IOS"), any(LocalDateTime.class));
        order.verify(deviceTokenRepository).deleteOldestBeyond(7L, DeviceService.MAX_DEVICES_PER_USER);
    }

    @Test
    void whenUnregisterDevice_thenOnlyTheTokenOfThatUserIsDeleted() {
        deviceService.unregisterDevice(driver(7L), " token-abc ");

        verify(deviceTokenRepository).deleteByTokenAndUserId("token-abc", 7L);
    }

    @Test
    void givenUnregisteredTokens_whenRemoveInvalidTokens_thenTheyAreDeletedInOneStatement() {
        deviceService.removeInvalidTokens(List.of("tok-1", "tok-2"));

        verify(deviceTokenRepository).deleteByTokenIn(List.of("tok-1", "tok-2"));
    }

    @Test
    void givenNoTokens_whenRemoveInvalidTokens_thenTheDatabaseIsNotTouched() {
        deviceService.removeInvalidTokens(List.of());

        verifyNoInteractions(deviceTokenRepository);
    }

    private static User driver(Long id) {
        User user = new User();
        user.setId(id);
        user.setUsername("driver" + id);
        user.setRole(User.Role.DRIVER);
        return user;
    }
}
