package com.company.nopar.dto;

import com.company.nopar.entity.User;
import com.company.nopar.entity.WaveMessage;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Un mensaje del chat de una ola. La foto no va dentro: {@code photoUrl} es la ruta relativa de la API
 * ({@code /api/v1/waves/{waveId}/chat/messages/{id}/photo}) o null si el mensaje no lleva foto. {@code text} es null
 * si el mensaje es solo una foto.
 */
public record WaveMessageResponse(
        Long id,
        Long waveId,
        LocalDate workDate,
        Long senderId,
        String senderUsername,
        User.Role senderRole,
        String text,
        String photoUrl,
        LocalDateTime sentAt
) {

    public static WaveMessageResponse from(WaveMessage message) {
        User sender = message.getSender();
        return new WaveMessageResponse(
                message.getId(),
                message.getWaveId(),
                message.getWorkDate(),
                sender.getId(),
                sender.getUsername(),
                sender.getRole(),
                message.getBody(),
                message.hasPhoto()
                        ? "/api/v1/waves/" + message.getWaveId() + "/chat/messages/" + message.getId() + "/photo"
                        : null,
                message.getSentAt());
    }

    public static List<WaveMessageResponse> fromAll(List<WaveMessage> messages) {
        return messages.stream().map(WaveMessageResponse::from).toList();
    }
}
