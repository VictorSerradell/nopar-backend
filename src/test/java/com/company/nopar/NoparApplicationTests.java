package com.company.nopar;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Prueba de humo: la aplicación arranca entera (Flyway, Hibernate con ddl-auto: validate, seguridad...) contra un
 * PostgreSQL desechable levantado con Testcontainers. No depende de una base de datos local ni de variables de
 * entorno (DB_PASSWORD, JWT_SECRET), así que corre igual en cualquier máquina y en el CI. Hace falta Docker en marcha.
 */
@SpringBootTest(properties = {
        // Ni el .env local ni un administrador inicial deben influir en este test
        "application.bootstrap.admin.username=",
        "application.bootstrap.admin.password="
})
@Testcontainers
class NoparApplicationTests {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // Clave de firma solo para este test (el JwtService la exige al arrancar; 40 bytes, más de los 32 de HS256)
        registry.add("application.security.jwt.secret-key", () -> Base64.getEncoder()
                .encodeToString("smoke-test-only-signing-key-0123456789ab".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void contextLoads() {
    }

}
