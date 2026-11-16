package com.company.nopar.service;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.UUID;

@Service
public class FileStorageService {

    private final Path storageLocation = Paths.get("uploads/inspections").toAbsolutePath().normalize();

    public String storeFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return null;
        }

        // Validar Content-Type por seguridad
        String contentType = file.getContentType();
        if (contentType == null || (!contentType.equals("image/jpeg") && !contentType.equals("image/png"))) {
            throw new SecurityException("Formato de archivo no permitido. Solo se aceptan JPEG y PNG.");
        }

        try {
            Files.createDirectories(this.storageLocation);
            String extension = Objects.requireNonNull(file.getOriginalFilename()).contains(".")
                    ? file.getOriginalFilename().substring(file.getOriginalFilename().lastIndexOf("."))
                    : ".jpg";

            String fileName = UUID.randomUUID().toString() + extension;
            Path targetLocation = this.storageLocation.resolve(fileName);

            Files.copy(file.getInputStream(), targetLocation, StandardCopyOption.REPLACE_EXISTING);
            return targetLocation.toString();
        } catch (IOException ex) {
            throw new RuntimeException("Error al almacenar el archivo adjunto", ex);
        }
    }
}