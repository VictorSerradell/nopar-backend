package com.company.nopar.controller;

import com.company.nopar.dto.AnnouncementReaderResponse;
import com.company.nopar.dto.AnnouncementRequest;
import com.company.nopar.dto.AnnouncementResponse;
import com.company.nopar.dto.AnnouncementUpdateRequest;
import com.company.nopar.entity.User;
import com.company.nopar.service.AnnouncementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Bajo /api/v1/admin: SecurityConfig lo reserva al rol ADMIN. */
@Tag(name = "Administración", description = "Operaciones reservadas al rol ADMIN")
@RestController
@RequestMapping("/api/v1/admin/announcements")
@RequiredArgsConstructor
public class AdminAnnouncementController {

    private final AnnouncementService announcementService;

    @Operation(summary = "Publica un aviso",
            description = "Título (máximo 120 caracteres) y texto (máximo 4000). Con pinned a true queda fijado al principio del tablón. Los conductores reciben una notificación push, salvo que se envíe notifyDrivers a false (publicación en silencio). Modificar un aviso no vuelve a notificar.")
    @PostMapping
    public ResponseEntity<AnnouncementResponse> createAnnouncement(
            @Valid @RequestBody AnnouncementRequest request,
            @AuthenticationPrincipal User currentAdmin
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(AnnouncementResponse.from(announcementService.createAnnouncement(request, currentAdmin)));
    }

    @Operation(summary = "Modifica un aviso",
            description = "Solo cambian los campos enviados (título, texto, fijado). Hay que enviar al menos uno.")
    @ApiResponse(responseCode = "400", description = "No se envió ningún cambio, o el título o el texto quedan vacíos")
    @ApiResponse(responseCode = "404", description = "El aviso no existe")
    @PatchMapping("/{id}")
    public ResponseEntity<AnnouncementResponse> updateAnnouncement(
            @PathVariable Long id,
            @Valid @RequestBody AnnouncementUpdateRequest request
    ) {
        return ResponseEntity.ok(AnnouncementResponse.from(announcementService.updateAnnouncement(id, request)));
    }

    @Operation(summary = "Elimina un aviso", description = "También se borra la constancia de quién lo había leído.")
    @ApiResponse(responseCode = "204", description = "Aviso eliminado")
    @ApiResponse(responseCode = "404", description = "El aviso no existe")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteAnnouncement(@PathVariable Long id) {
        announcementService.deleteAnnouncement(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Quién ha leído un aviso", description = "Usuario y fecha de lectura, por orden de lectura.")
    @ApiResponse(responseCode = "404", description = "El aviso no existe")
    @GetMapping("/{id}/reads")
    public ResponseEntity<List<AnnouncementReaderResponse>> getReaders(@PathVariable Long id) {
        return ResponseEntity.ok(AnnouncementReaderResponse.fromAll(announcementService.getReaders(id)));
    }
}
