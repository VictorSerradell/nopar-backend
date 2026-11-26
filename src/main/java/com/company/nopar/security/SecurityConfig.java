package com.company.nopar.security;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.time.Duration;
import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthFilter;
    private final UserDetailsService userDetailsService; // <--- Inyectamos el servicio directamente

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                // usa el bean corsConfigurationSource; en la cadena de seguridad, para que los preflight (OPTIONS, sin
                // token) se contesten antes de exigir autenticación
                .cors(Customizer.withDefaults())
                .authorizeHttpRequests(auth -> auth
                        // login, renovar y cerrar la sesión de este dispositivo: la credencial es el refresh token
                        .requestMatchers("/api/v1/auth/login", "/api/v1/auth/refresh", "/api/v1/auth/logout").permitAll()
                        // cerrar todas las sesiones y cambiar la contraseña exigen un access token válido
                        .requestMatchers("/api/v1/auth/logout-all", "/api/v1/auth/password").authenticated()
                        // estado del servicio (healthcheck de Railway y monitores externos): público y sin datos
                        .requestMatchers("/health").permitAll()
                        // contrato OpenAPI y Swagger UI: los equipos de Android, iOS y web los leen sin token
                        // (en producción se desactivan con OPENAPI_ENABLED=false)
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        // /error es adonde Spring reenvía las respuestas de error (403 de @PreAuthorize, 500...). Sin token en
                        // ese reenvío, si se exigiera autenticación todo error saldría como un 401 vacío
                        .requestMatchers("/error").permitAll()
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        // dar la orden de subir a una ola: administradores y encargados
                        .requestMatchers("/api/v1/dispatch/**").hasAnyRole("ADMIN", "MANAGER")
                        .anyRequest().authenticated()
                )
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(ex -> ex.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .authenticationProvider(authenticationProvider())
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }



    /**
     * CORS para el cliente web: el navegador bloquea las llamadas a otro origen (la web en https://app.empresa.com y
     * la API en otro dominio) salvo que la API lo autorice. Android e iOS no lo necesitan.
     *
     * Los orígenes permitidos van en application.security.cors.allowed-origins (variable CORS_ALLOWED_ORIGINS,
     * separados por comas; admiten patrones como http://localhost:*). Vacío = ningún origen. La autenticación va en la
     * cabecera Authorization, no en cookies, así que no se permiten credenciales.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource(
            @Value("${application.security.cors.allowed-origins:}") List<String> allowedOrigins) {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        List<String> origins = allowedOrigins.stream().map(String::strip).filter(origin -> !origin.isEmpty()).toList();
        if (origins.isEmpty()) {
            return source; // sin configuración registrada: ningún origen distinto del propio queda autorizado
        }

        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(origins);
        config.setAllowedMethods(List.of("GET", "POST", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept"));
        config.setAllowCredentials(false);
        config.setMaxAge(Duration.ofHours(1)); // el navegador no repite el preflight durante una hora
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    public AuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider authProvider = new DaoAuthenticationProvider(userDetailsService);
        authProvider.setPasswordEncoder(passwordEncoder());
        return authProvider;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

}
