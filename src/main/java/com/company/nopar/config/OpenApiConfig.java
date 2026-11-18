package com.company.nopar.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.annotation.AuthenticationPrincipal;

import java.util.List;
import java.util.Set;

/**
 * Contrato OpenAPI de la API: lo leen los equipos de los clientes (Android, iOS y web), también para generar
 * código. Se publica en /v3/api-docs y en Swagger UI (/swagger-ui.html).
 */
@Configuration
public class OpenApiConfig {

    static final String BEARER_AUTH = "bearerAuth";

    /** Endpoints que se llaman sin access token: en el contrato no llevan candado. Deben coincidir con SecurityConfig. */
    static final Set<String> PUBLIC_PATHS = Set.of(
            "/api/v1/auth/login",
            "/api/v1/auth/refresh",
            "/api/v1/auth/logout");

    static {
        // El usuario autenticado sale del token, no es un parámetro de la petición: que no aparezca en el contrato
        SpringDocUtils.getConfig().addAnnotationsToIgnore(AuthenticationPrincipal.class);
    }

    @Bean
    public OpenAPI noparOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Nopar API")
                        .version("v1")
                        .description("""
                                Partes matutinos de inspección, control de flota y devolución de vehículos.

                                **Autenticación.** `POST /api/v1/auth/login` devuelve un access token JWT (15 minutos) y un \
                                refresh token (7 días, de un solo uso: cada `/refresh` emite uno nuevo). El access token se \
                                envía en la cabecera `Authorization: Bearer <token>`.

                                **Errores.** Se devuelven como `application/problem+json` (RFC 9457) con `title`, `status` \
                                y `detail`; los errores de validación añaden `errors` (campo → mensaje).

                                **Fotos.** Los campos `*PhotoUrl` son rutas relativas de esta API y también exigen la \
                                cabecera `Authorization`: un cliente web debe descargarlas con `fetch` como blob, no con \
                                `<img src>`.
                                """))
                .components(new Components().addSecuritySchemes(BEARER_AUTH, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")))
                // Todas las operaciones exigen el token salvo las de PUBLIC_PATHS (ver el customizer de abajo)
                .addSecurityItem(new SecurityRequirement().addList(BEARER_AUTH));
    }

    @Bean
    public OpenApiCustomizer publicEndpointsCustomizer() {
        return openApi -> {
            if (openApi.getPaths() == null) {
                return;
            }
            PUBLIC_PATHS.forEach(path -> {
                PathItem item = openApi.getPaths().get(path);
                if (item != null) {
                    // "security": [] en la operación anula el requisito global
                    item.readOperations().forEach(operation -> operation.setSecurity(List.of()));
                }
            });
        };
    }
}
