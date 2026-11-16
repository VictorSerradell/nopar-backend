package com.company.nopar;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@SpringBootApplication
@EnableJpaAuditing // <--- Añade esta línea para que funcione @CreatedDate
public class NoparApplication {

    public static void main(String[] args) {
        SpringApplication.run(NoparApplication.class, args);
    }

}