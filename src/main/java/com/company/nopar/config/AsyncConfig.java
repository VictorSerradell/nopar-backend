package com.company.nopar.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/** Activa la ejecución en segundo plano (@Async), como el envío de las notificaciones push. */
@Configuration
@EnableAsync
public class AsyncConfig {
}
