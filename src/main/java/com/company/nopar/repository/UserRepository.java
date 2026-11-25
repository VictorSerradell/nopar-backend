package com.company.nopar.repository;

import com.company.nopar.entity.User;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUsername(String username);

    boolean existsByUsername(String username);

    boolean existsByRole(User.Role role);

    /** Los usuarios de un rol, en el orden que pida quien llama (por ejemplo, por nombre). */
    List<User> findByRole(User.Role role, Sort sort);
}
