package com.company.nopar.dto;

/**
 * Lo que se envía al chat de una ola. El texto es opcional solo si el mensaje lleva foto; el servidor lo recorta, lo
 * comprueba (no vacío, máximo {@code WaveChatService.MAX_TEXT_CHARS} caracteres) y responde 400 si no vale.
 */
public record WaveMessageRequest(String text) {
}
