package com.company.nopar.service;

import com.company.nopar.entity.AuditEvent;
import com.company.nopar.entity.User;
import com.company.nopar.repository.AuditEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registro de auditoría de las acciones sobre cuentas. Escribe en la transacción de quien lo llama: si la acción se
 * deshace, su línea también, y no puede quedar una línea de algo que no ocurrió.
 */
@Service
@RequiredArgsConstructor
public class AuditService {

    /** Actor de lo que ocurre sin un usuario de por medio, como el arranque de la aplicación. */
    public static final String SYSTEM = "sistema";

    private static final int MAX_DETAIL_LENGTH = 500;

    private final AuditEventRepository auditEventRepository;

    /** Anota una acción hecha por un usuario concreto. */
    @Transactional
    public void record(AuditEvent.Action action, User actor, User target, String detail) {
        save(action, actor != null ? actor.getUsername() : SYSTEM, target, detail);
    }

    /** Anota una acción hecha por quien tiene la sesión abierta en esta petición, o por "sistema" si no hay ninguna. */
    @Transactional
    public void record(AuditEvent.Action action, User target, String detail) {
        save(action, currentActorName(), target, detail);
    }

    /** Del más reciente al más antiguo. */
    @Transactional(readOnly = true)
    public Page<AuditEvent> list(int page, int size) {
        return auditEventRepository.findAll(PageRequest.of(page, size,
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))));
    }

    private void save(AuditEvent.Action action, String actorUsername, User target, String detail) {
        AuditEvent event = new AuditEvent();
        event.setAction(action);
        event.setActorUsername(actorUsername);
        if (target != null) {
            event.setTargetUserId(target.getId());
            event.setTargetUsername(target.getUsername());
        }
        if (detail != null) {
            event.setDetail(detail.length() > MAX_DETAIL_LENGTH ? detail.substring(0, MAX_DETAIL_LENGTH) : detail);
        }
        auditEventRepository.save(event);
    }

    private static String currentActorName() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated() || authentication instanceof AnonymousAuthenticationToken) {
            return SYSTEM;
        }
        String name = authentication.getName();
        return name == null || name.isBlank() ? SYSTEM : name;
    }
}
