package com.company.nopar.service;

import com.company.nopar.entity.User;
import com.company.nopar.exception.DuplicateResourceException;
import com.company.nopar.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public User createDriver(String username, String rawPassword) {
        return createUser(username, rawPassword, User.Role.DRIVER);
    }

    @Transactional
    public User createUser(String username, String rawPassword, User.Role role) {
        if (userRepository.existsByUsername(username)) {
            throw new DuplicateResourceException("Ya existe un usuario con el nombre: " + username);
        }

        User user = new User();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(rawPassword));
        user.setRole(role);

        try {
            return userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException ex) {
            // Otra petición creó el mismo usuario entre la comprobación y el guardado
            throw new DuplicateResourceException("Ya existe un usuario con el nombre: " + username);
        }
    }
}
