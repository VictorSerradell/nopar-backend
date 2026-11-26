package com.company.nopar.dto;

import com.company.nopar.entity.User;

/** Vista pública de un usuario: nunca incluye la contraseña (ni su hash). */
public record UserResponse(
        Long id,
        String username,
        User.Role role,
        /** false si la cuenta está desactivada: conserva su historial pero ya no puede iniciar sesión. */
        boolean enabled,
        /** true si es un super administrador: puede desactivar y reactivar a otros usuarios. */
        boolean superAdmin
) {

    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getUsername(), user.getRole(), user.isEnabled(), user.isSuperAdmin());
    }
}
