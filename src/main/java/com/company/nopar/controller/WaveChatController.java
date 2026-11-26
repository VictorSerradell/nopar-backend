package com.company.nopar.controller;

import com.company.nopar.dto.WaveChatReadRequest;
import com.company.nopar.dto.WaveMessageRequest;
import com.company.nopar.dto.WaveMessageResponse;
import com.company.nopar.entity.User;
import com.company.nopar.service.FileStorageService;
import com.company.nopar.service.WaveChatService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;
import java.util.List;

@Tag(name = "Chat de ola", description = "Chat de cada ola (uno por ola y día): texto y fotos entre los conductores de la ola, los encargados y los administradores")
@RestController
@RequestMapping("/api/v1/waves/{waveId}/chat")
@RequiredArgsConstructor
public class WaveChatController {

    private static final String DEFAULT_LIMIT = "100";

    private final WaveChatService chatService;

    @Operation(summary = "Mensajes del chat de hoy",
            description = "Sin `after`, los últimos mensajes del chat de hoy de la ola (hasta `limit`), por orden de llegada. Con `after` (el id del último mensaje que la app ya tiene), solo los posteriores: así la app se pone al día cada pocos segundos. Los conductores solo entran al chat de la ola en la que tienen turno hoy; encargados y administradores, a cualquiera.")
    @ApiResponse(responseCode = "403", description = "Eres un conductor sin turno hoy en esa ola")
    @ApiResponse(responseCode = "404", description = "La ola no existe")
    @GetMapping("/messages")
    @PreAuthorize("hasAnyRole('DRIVER', 'MANAGER', 'ADMIN')")
    public ResponseEntity<List<WaveMessageResponse>> getMessages(
            @PathVariable Long waveId,
            @RequestParam(required = false) @Min(value = 0, message = "after no puede ser negativo") Long after,
            @RequestParam(defaultValue = DEFAULT_LIMIT)
            @Min(value = 1, message = "limit debe ser al menos 1")
            @Max(value = WaveChatService.MAX_LIMIT, message = "limit no puede pasar de 200") int limit,
            @AuthenticationPrincipal User currentUser // quien lee sale del token JWT, no lo envía el cliente
    ) {
        return ResponseEntity.ok(WaveMessageResponse.fromAll(chatService.getMessages(waveId, after, limit, currentUser)));
    }

    @Operation(summary = "Escribe un mensaje de texto",
            description = "El texto se recorta y no puede estar vacío ni pasar de 1000 caracteres. Para enviar una foto (con o sin texto) usa `POST /chat/photos`.")
    @ApiResponse(responseCode = "400", description = "Mensaje vacío o demasiado largo")
    @ApiResponse(responseCode = "403", description = "Eres un conductor sin turno hoy en esa ola")
    @ApiResponse(responseCode = "404", description = "La ola no existe")
    @PostMapping(value = "/messages", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAnyRole('DRIVER', 'MANAGER', 'ADMIN')")
    public ResponseEntity<WaveMessageResponse> sendText(
            @PathVariable Long waveId,
            @RequestBody WaveMessageRequest request,
            @AuthenticationPrincipal User currentUser
    ) {
        WaveMessageResponse saved = WaveMessageResponse.from(
                chatService.send(waveId, request.text(), null, currentUser));
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    @Operation(summary = "Escribe un mensaje con foto",
            description = "Multipart: `photo` es la imagen (solo JPEG o PNG: se comprueba el contenido, no el nombre) y `data` es opcional, un JSON (Content-Type application/json) con el `text` que la acompaña.")
    @ApiResponse(responseCode = "400", description = "Texto demasiado largo o archivo que no es JPEG/PNG")
    @ApiResponse(responseCode = "403", description = "Eres un conductor sin turno hoy en esa ola")
    @ApiResponse(responseCode = "404", description = "La ola no existe")
    @PostMapping(value = "/photos", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('DRIVER', 'MANAGER', 'ADMIN')")
    public ResponseEntity<WaveMessageResponse> sendPhoto(
            @PathVariable Long waveId,
            @RequestPart(value = "data", required = false) WaveMessageRequest request,
            @RequestPart("photo") MultipartFile photo,
            @AuthenticationPrincipal User currentUser
    ) {
        String text = request == null ? null : request.text();
        WaveMessageResponse saved = WaveMessageResponse.from(chatService.send(waveId, text, photo, currentUser));
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    @Operation(summary = "Marca el chat de hoy como leído",
            description = "La app lo llama mientras el chat está a la vista, con el id del mensaje más reciente que ha enseñado. Solo avanza (marcar uno anterior no cambia nada) y un id mayor que el último mensaje real se recorta a ese último. De ahí salen los sin leer de `GET /chat/unread`.")
    @ApiResponse(responseCode = "204", description = "Marcado")
    @ApiResponse(responseCode = "400", description = "Falta `lastReadId` o no es positivo")
    @ApiResponse(responseCode = "403", description = "Eres un conductor sin turno hoy en esa ola")
    @ApiResponse(responseCode = "404", description = "La ola no existe")
    @PostMapping("/read")
    @PreAuthorize("hasAnyRole('DRIVER', 'MANAGER', 'ADMIN')")
    public ResponseEntity<Void> markRead(
            @PathVariable Long waveId,
            @Valid @RequestBody WaveChatReadRequest request,
            @AuthenticationPrincipal User currentUser
    ) {
        chatService.markRead(waveId, request.lastReadId(), currentUser);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Descarga la foto de un mensaje",
            description = "Devuelve la imagen (image/jpeg o image/png). La URL viene en el campo `photoUrl` del mensaje. Los conductores solo ven las fotos del chat de hoy de su ola. Es privada para la caché: solo la guarda el dispositivo de quien la pidió.")
    @ApiResponse(responseCode = "200", description = "La imagen",
            content = {@Content(mediaType = "image/jpeg", schema = @Schema(type = "string", format = "binary")),
                    @Content(mediaType = "image/png", schema = @Schema(type = "string", format = "binary"))})
    @ApiResponse(responseCode = "403", description = "No puedes ver ese chat")
    @ApiResponse(responseCode = "404", description = "La ola o el mensaje no existen, o el mensaje no lleva foto")
    @GetMapping("/messages/{messageId}/photo")
    @PreAuthorize("hasAnyRole('DRIVER', 'MANAGER', 'ADMIN')")
    public ResponseEntity<Resource> getPhoto(
            @PathVariable Long waveId,
            @PathVariable Long messageId,
            @AuthenticationPrincipal User currentUser
    ) {
        FileStorageService.StoredFile photo = chatService.getPhoto(waveId, messageId, currentUser);
        return ResponseEntity.ok()
                .contentType(photo.mediaType())
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePrivate())
                .body(photo.resource());
    }
}
