package com.company.nopar.controller;

import com.company.nopar.dto.AuditEventResponse;
import com.company.nopar.dto.PageResponse;
import com.company.nopar.service.AuditService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Bajo /api/v1/admin: SecurityConfig lo reserva al rol ADMIN. Solo lectura: el registro no se puede editar ni borrar. */
@Tag(name = "Administración", description = "Operaciones reservadas al rol ADMIN")
@RestController
@RequestMapping("/api/v1/admin/audit")
@RequiredArgsConstructor
public class AdminAuditController {

    private final AuditService auditService;

    @Operation(summary = "Lista el registro de auditoría de cuentas",
            description = "Paginado, del más reciente al más antiguo: altas, desactivaciones y reactivaciones de cuentas, cambios y restablecimientos de contraseña y ascensos a super administrador, con quién lo hizo y sobre quién (\"sistema\" si no fue un usuario).")
    @GetMapping
    public ResponseEntity<PageResponse<AuditEventResponse>> list(
            @RequestParam(defaultValue = PageResponse.DEFAULT_PAGE)
            @Min(value = 0, message = PageResponse.PAGE_MIN_MESSAGE) int page,
            @RequestParam(defaultValue = PageResponse.DEFAULT_SIZE)
            @Min(value = 1, message = PageResponse.SIZE_MIN_MESSAGE)
            @Max(value = PageResponse.MAX_SIZE, message = PageResponse.SIZE_MAX_MESSAGE) int size) {
        return ResponseEntity.ok(PageResponse.of(auditService.list(page, size), AuditEventResponse::from));
    }
}
