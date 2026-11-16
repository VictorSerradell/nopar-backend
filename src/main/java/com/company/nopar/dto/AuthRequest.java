package com.company.nopar.dto;

public record AuthRequest(
        String username,
        String password
) {}