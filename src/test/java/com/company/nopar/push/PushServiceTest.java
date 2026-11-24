package com.company.nopar.push;

import com.company.nopar.entity.User;
import com.company.nopar.repository.DeviceTokenRepository;
import com.company.nopar.service.DeviceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PushServiceTest {

    private final PushNotification notification = PushNotification.forAnnouncement(5L, "Lockers", "Texto");

    @Mock
    private DeviceTokenRepository deviceTokenRepository;

    @Mock
    private DeviceService deviceService;

    @Mock
    private PushSender pushSender;

    @InjectMocks
    private PushService pushService;

    @Test
    void givenNoDevices_whenNotifyDrivers_thenNothingIsSent() {
        when(deviceTokenRepository.findTokensByUserRole(User.Role.DRIVER)).thenReturn(List.of());

        pushService.notifyDrivers(notification);

        verifyNoInteractions(pushSender, deviceService);
    }

    @Test
    void givenDriversDevices_whenNotifyDrivers_thenSendsToAllOfThemAndKeepsThemIfTheyAreValid() {
        when(deviceTokenRepository.findTokensByUserRole(User.Role.DRIVER)).thenReturn(List.of("tok-1", "tok-2"));
        when(pushSender.send(notification, List.of("tok-1", "tok-2"))).thenReturn(List.of());

        pushService.notifyDrivers(notification);

        verify(pushSender).send(notification, List.of("tok-1", "tok-2"));
        verifyNoInteractions(deviceService);
    }

    @Test
    void givenUnregisteredDevices_whenNotifyDrivers_thenTheirTokensAreRemoved() {
        when(deviceTokenRepository.findTokensByUserRole(User.Role.DRIVER)).thenReturn(List.of("tok-1", "tok-2"));
        when(pushSender.send(notification, List.of("tok-1", "tok-2"))).thenReturn(List.of("tok-2"));

        pushService.notifyDrivers(notification);

        verify(deviceService).removeInvalidTokens(List.of("tok-2"));
    }

    @Test
    void givenTheSenderFails_whenNotifyDrivers_thenTheFailureIsNotHidden() {
        when(deviceTokenRepository.findTokensByUserRole(User.Role.DRIVER)).thenReturn(List.of("tok-1"));
        when(pushSender.send(notification, List.of("tok-1"))).thenThrow(new IllegalStateException("sin red"));

        assertThatThrownBy(() -> pushService.notifyDrivers(notification)).isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(deviceService);
    }

    @Test
    void givenNoDevices_whenNotifyUser_thenNothingIsSent() {
        when(deviceTokenRepository.findTokensByUserId(7L)).thenReturn(List.of());

        pushService.notifyUser(7L, notification);

        verifyNoInteractions(pushSender, deviceService);
    }

    @Test
    void givenTheUsersDevices_whenNotifyUser_thenSendsOnlyToThoseAndRemovesTheUnregisteredOnes() {
        when(deviceTokenRepository.findTokensByUserId(7L)).thenReturn(List.of("tok-1", "tok-2"));
        when(pushSender.send(notification, List.of("tok-1", "tok-2"))).thenReturn(List.of("tok-2"));

        pushService.notifyUser(7L, notification);

        verify(pushSender).send(notification, List.of("tok-1", "tok-2"));
        verify(deviceService).removeInvalidTokens(List.of("tok-2"));
        verify(deviceTokenRepository, never()).findTokensByUserRole(any());
    }

    @Test
    void givenNoUsers_whenNotifyUsers_thenNothingIsLookedUpOrSent() {
        pushService.notifyUsers(List.of(), notification);

        verifyNoInteractions(deviceTokenRepository, pushSender, deviceService);
    }

    @Test
    void givenUsersDevices_whenNotifyUsers_thenSendsToAllOfThemInOneGo() {
        when(deviceTokenRepository.findTokensByUserIdIn(List.of(2L, 3L))).thenReturn(List.of("tok-1", "tok-2"));
        when(pushSender.send(notification, List.of("tok-1", "tok-2"))).thenReturn(List.of());

        pushService.notifyUsers(List.of(2L, 3L), notification);

        verify(pushSender).send(notification, List.of("tok-1", "tok-2"));
        verifyNoInteractions(deviceService);
    }

    @Test
    void givenUsersWithoutDevices_whenNotifyUsers_thenNothingIsSent() {
        when(deviceTokenRepository.findTokensByUserIdIn(List.of(2L))).thenReturn(List.of());

        pushService.notifyUsers(List.of(2L), notification);

        verifyNoInteractions(pushSender, deviceService);
    }

    @Test
    void givenUnregisteredDevices_whenNotifyUsers_thenTheirTokensAreRemoved() {
        when(deviceTokenRepository.findTokensByUserIdIn(List.of(2L, 3L))).thenReturn(List.of("tok-1", "tok-2"));
        when(pushSender.send(notification, List.of("tok-1", "tok-2"))).thenReturn(List.of("tok-1"));

        pushService.notifyUsers(List.of(2L, 3L), notification);

        verify(deviceService).removeInvalidTokens(List.of("tok-1"));
    }
}
