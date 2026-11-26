package com.company.nopar.service;

import com.company.nopar.entity.AuditEvent;
import com.company.nopar.entity.User;
import com.company.nopar.repository.AuditEventRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuditServiceTest {

    @Mock
    private AuditEventRepository auditEventRepository;

    @InjectMocks
    private AuditService auditService;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private AuditEvent saved() {
        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditEventRepository).save(captor.capture());
        return captor.getValue();
    }

    private static User user(Long id, String username) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        return user;
    }

    @Test
    void givenAnExplicitActor_whenRecord_thenTheEventNamesActorAndTarget() {
        auditService.record(AuditEvent.Action.USER_DEACTIVATED, user(1L, "jefe"), user(7L, "conductor1"), "rol DRIVER");

        AuditEvent event = saved();
        assertThat(event.getAction()).isEqualTo(AuditEvent.Action.USER_DEACTIVATED);
        assertThat(event.getActorUsername()).isEqualTo("jefe");
        assertThat(event.getTargetUserId()).isEqualTo(7L);
        assertThat(event.getTargetUsername()).isEqualTo("conductor1");
        assertThat(event.getDetail()).isEqualTo("rol DRIVER");
        assertThat(event.getCreatedAt()).isNotNull();
    }

    @Test
    void givenAnExplicitNullActor_whenRecord_thenTheActorIsTheSystem() {
        auditService.record(AuditEvent.Action.USER_CREATED, null, user(7L, "conductor1"), null);

        assertThat(saved().getActorUsername()).isEqualTo(AuditService.SYSTEM);
    }

    @Test
    void givenAnAuthenticatedRequest_whenRecordWithoutActor_thenTheActorIsWhoseSessionItIs() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin1", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));

        auditService.record(AuditEvent.Action.PASSWORD_RESET, user(7L, "conductor1"), null);

        AuditEvent event = saved();
        assertThat(event.getActorUsername()).isEqualTo("admin1");
        assertThat(event.getTargetUsername()).isEqualTo("conductor1");
        assertThat(event.getDetail()).isNull();
    }

    @Test
    void givenNoSession_whenRecordWithoutActor_thenTheActorIsTheSystem() {
        auditService.record(AuditEvent.Action.USER_CREATED, user(7L, "admin"), "rol ADMIN");

        assertThat(saved().getActorUsername()).isEqualTo(AuditService.SYSTEM);
    }

    @Test
    void givenAnAnonymousRequest_whenRecordWithoutActor_thenTheActorIsTheSystem() {
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
                "clave", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));

        auditService.record(AuditEvent.Action.USER_CREATED, user(7L, "admin"), "rol ADMIN");

        assertThat(saved().getActorUsername()).isEqualTo(AuditService.SYSTEM);
    }

    @Test
    void givenAVeryLongDetail_whenRecord_thenItIsCutToWhatTheColumnHolds() {
        auditService.record(AuditEvent.Action.USER_CREATED, user(7L, "admin"), "x".repeat(900));

        assertThat(saved().getDetail()).hasSize(500);
    }

    @Test
    void whenList_thenNewestFirstWithTheIdAsTieBreaker() {
        when(auditEventRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

        auditService.list(2, 30);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(auditEventRepository).findAll(captor.capture());
        assertThat(captor.getValue().getPageNumber()).isEqualTo(2);
        assertThat(captor.getValue().getPageSize()).isEqualTo(30);
        assertThat(captor.getValue().getSort())
                .isEqualTo(Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
    }
}
