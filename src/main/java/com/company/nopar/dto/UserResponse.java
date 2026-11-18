package com.company.nopar.dto;

import com.company.nopar.entity.User;

/** Vista pública de un usuario: nunca incluye la contraseña (ni su hash). */
public record UserResponse(
        Long id,
        String username,
        User.Role role
) {

    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getUsername(), user.getRole());
    }
}
