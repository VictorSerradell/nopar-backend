package com.company.nopar.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Activa las tareas programadas (@Scheduled), como el reseteo nocturno de vehículos. */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
