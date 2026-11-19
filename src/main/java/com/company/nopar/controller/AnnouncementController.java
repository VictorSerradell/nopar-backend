package com.company.nopar.controller;

import com.company.nopar.dto.BoardAnnouncementResponse;
import com.company.nopar.dto.PageResponse;
import com.company.nopar.entity.User;
import com.company.nopar.service.AnnouncementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.function.Function;

@Tag(name = "Avisos", description = "Tablón de avisos de la empresa")
@RestController
@RequestMapping("/api/v1/announcements")
@RequiredArgsConstructor
public class AnnouncementController {

    private final AnnouncementService announcementService;

    @Operation(summary = "Tablón de avisos",
            description = "Paginado: primero los fijados y luego los más recientes. Cada aviso indica si el usuario autenticado ya lo ha leído.")
    @GetMapping
    @PreAuthorize("hasAnyRole('DRIVER', 'ADMIN')")
    public ResponseEntity<PageResponse<BoardAnnouncementResponse>> getBoard(
            @RequestParam(defaultValue = PageResponse.DEFAULT_PAGE)
            @Min(value = 0, message = PageResponse.PAGE_MIN_MESSAGE) int page,
            @RequestParam(defaultValue = PageResponse.DEFAULT_SIZE)
            @Min(value = 1, message = PageResponse.SIZE_MIN_MESSAGE)
            @Max(value = PageResponse.MAX_SIZE, message = PageResponse.SIZE_MAX_MESSAGE) int size,
            @AuthenticationPrincipal User currentUser // quién lee sale del token JWT, no lo envía el cliente
    ) {
        return ResponseEntity.ok(PageResponse.of(
                announcementService.getBoard(currentUser, page, size), Function.identity()));
    }

    @Operation(summary = "Marca un aviso como leído",
            description = "Idempotente: si el usuario ya lo había leído no cambia nada y se conserva la primera fecha de lectura.")
    @ApiResponse(responseCode = "204", description = "Aviso marcado como leído")
    @ApiResponse(responseCode = "404", description = "El aviso no existe")
    @PostMapping("/{id}/read")
    @PreAuthorize("hasAnyRole('DRIVER', 'ADMIN')")
    public ResponseEntity<Void> markAsRead(@PathVariable Long id, @AuthenticationPrincipal User currentUser) {
        announcementService.markAsRead(id, currentUser);
        return ResponseEntity.noContent().build();
    }
}
