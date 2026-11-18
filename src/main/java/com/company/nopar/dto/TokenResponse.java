package com.company.nopar.dto;

/** Par de tokens que reciben los clientes al iniciar sesión o renovarla. */
public record TokenResponse(
        String accessToken,
        String refreshToken
) {}
