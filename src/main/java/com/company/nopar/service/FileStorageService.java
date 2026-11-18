package com.company.nopar.service;

import com.company.nopar.exception.InvalidFileException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.UUID;

/**
 * Guarda en disco las fotos de las inspecciones.
 *
 * El tipo de fichero se decide por su contenido (firma JPEG/PNG), no por el nombre ni por el Content-Type
 * que declare el cliente: ambos los controla quien sube el fichero y cada plataforma (Android, iOS, Windows)
 * los rellena a su manera. El nombre y la extensión finales los genera siempre el servidor.
 */
@Slf4j
@Service
public class FileStorageService {

    private static final int SIGNATURE_LENGTH = 8;
    private static final byte[] JPEG_SIGNATURE = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};

    private final Path storageLocation;

    public FileStorageService(@Value("${application.storage.location:uploads/inspections}") String location) {
        this.storageLocation = Paths.get(location).toAbsolutePath().normalize();
    }

    /**
     * @return el nombre del fichero generado (relativo a la carpeta de almacenamiento, sin rutas del servidor),
     *         o null si no se envió ningún fichero
     * @throws InvalidFileException si el contenido no es una imagen JPEG o PNG
     */
    public String storeFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return null;
        }

        Path target = null;
        try (InputStream in = new BufferedInputStream(file.getInputStream())) {
            String extension = detectExtension(in);
            Files.createDirectories(storageLocation);
            String fileName = UUID.randomUUID() + extension;
            target = storageLocation.resolve(fileName);
            Files.copy(in, target);
            return fileName;
        } catch (IOException ex) {
            deleteQuietly(target); // no dejar ficheros a medio escribir
            throw new UncheckedIOException("Error al almacenar el archivo adjunto", ex);
        }
    }

    /** Borra un fichero guardado antes con {@link #storeFile}. Ignora nombres que apunten fuera de la carpeta. */
    public void delete(String storedFileName) {
        if (storedFileName == null) {
            return;
        }
        Path file = storageLocation.resolve(storedFileName).normalize();
        if (!file.startsWith(storageLocation)) {
            log.warn("Se ignora el borrado de un fichero fuera de la carpeta de almacenamiento");
            return;
        }
        deleteQuietly(file);
    }

    private static String detectExtension(InputStream in) throws IOException {
        in.mark(SIGNATURE_LENGTH);
        byte[] header = in.readNBytes(SIGNATURE_LENGTH);
        in.reset();

        if (startsWith(header, JPEG_SIGNATURE)) {
            return ".jpg";
        }
        if (startsWith(header, PNG_SIGNATURE)) {
            return ".png";
        }
        throw new InvalidFileException("Formato de archivo no permitido. Solo se aceptan imágenes JPEG y PNG.");
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        return data.length >= prefix.length
                && Arrays.equals(data, 0, prefix.length, prefix, 0, prefix.length);
    }

    private void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException ex) {
            log.warn("No se pudo borrar el fichero {}", file.getFileName(), ex);
        }
    }
}
