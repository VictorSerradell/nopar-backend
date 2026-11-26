package com.company.nopar.controller;

import com.company.nopar.dto.ChatUnreadResponse;
import com.company.nopar.entity.User;
import com.company.nopar.service.WaveChatService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Chat de ola")
@RestController
@RequestMapping("/api/v1/chat")
@RequiredArgsConstructor
public class ChatUnreadController {

    private final WaveChatService chatService;

    @Operation(summary = "Mensajes sin leer de hoy",
            description = "Una entrada por cada ola con mensajes sin leer en el chat de hoy: los de otros posteriores al último que el usuario marcó como leído (`POST /waves/{id}/chat/read`). Los suyos no cuentan. Un conductor solo ve la ola en la que tiene turno hoy; encargados y administradores, todas. Sin mensajes sin leer devuelve una lista vacía.")
    @GetMapping("/unread")
    @PreAuthorize("hasAnyRole('DRIVER', 'MANAGER', 'ADMIN')")
    public ResponseEntity<List<ChatUnreadResponse>> getUnread(@AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(ChatUnreadResponse.fromMap(chatService.getUnread(currentUser)));
    }
}
