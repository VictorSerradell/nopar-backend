package com.company.nopar.repository;

import com.company.nopar.entity.User;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUsername(String username);

    boolean existsByUsername(String username);

    boolean existsByRole(User.Role role);

    boolean existsBySuperAdminTrue();

    /** Los usuarios de un rol, en el orden que pida quien llama (por ejemplo, por nombre). */
    List<User> findByRole(User.Role role, Sort sort);

    /**
     * Solo los ids de los usuarios activos de esos roles: a quién avisa el chat de una ola además de a sus conductores.
     * Las cuentas desactivadas no reciben nada.
     */
    @Query("select u.id from User u where u.role in :roles and u.enabled = true")
    List<Long> findIdsByRoleIn(@Param("roles") Collection<User.Role> roles);
}
