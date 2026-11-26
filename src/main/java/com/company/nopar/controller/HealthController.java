package com.company.nopar.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Estado del servicio, para el healthcheck de Railway y para monitores externos. Es público (sin token) y no consulta
 * la base de datos: si responde, la aplicación ha arrancado (Flyway y la conexión a la base de datos ya se
 * comprobaron al arrancar), y así el endpoint no se convierte en una forma barata de cargar la base de datos.
 */
@Tag(name = "Sistema", description = "Estado del servicio")
@RestController
@RequestMapping("/health")
public class HealthController {

    @Operation(summary = "Comprueba que la API está en marcha",
            description = "Público, sin token. Responde 200 con {\"status\": \"UP\"} en cuanto la aplicación ha arrancado; no consulta la base de datos.")
    @GetMapping
    public Map<String, String> health() {
        return Map.of("status", "UP");
    }
}
