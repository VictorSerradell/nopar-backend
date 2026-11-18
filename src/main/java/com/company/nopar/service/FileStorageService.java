package com.company.nopar.service;

import com.company.nopar.exception.InvalidFileException;
import com.company.nopar.exception.ResourceNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
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

    /** Un fichero guardado, listo para enviarse al cliente: su contenido y su tipo MIME. */
    public record StoredFile(Resource resource, MediaType mediaType) {
    }

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

    /**
     * Abre un fichero guardado antes con {@link #storeFile}. El tipo MIME sale de la extensión que puso el servidor
     * al guardarlo (que a su vez se decidió por el contenido real de la imagen).
     *
     * @throws ResourceNotFoundException si el fichero no existe o el nombre apunta fuera de la carpeta de almacenamiento
     */
    public StoredFile load(String storedFileName) {
        Path file = storedFileName == null ? null : resolveInsideStorage(storedFileName);
        if (file == null || !Files.isRegularFile(file)) {
            if (storedFileName != null) {
                log.warn("Se pidió un fichero que no está en la carpeta de almacenamiento: {}", storedFileName);
            }
            throw new ResourceNotFoundException("El archivo solicitado ya no está disponible");
        }
        return new StoredFile(new FileSystemResource(file), mediaTypeOf(file));
    }

    /** Borra un fichero guardado antes con {@link #storeFile}. Ignora nombres que apunten fuera de la carpeta. */
    public void delete(String storedFileName) {
        if (storedFileName == null) {
            return;
        }
        Path file = resolveInsideStorage(storedFileName);
        if (file == null) {
            log.warn("Se ignora el borrado de un fichero fuera de la carpeta de almacenamiento");
            return;
        }
        deleteQuietly(file);
    }

    /** La ruta del fichero dentro de la carpeta de almacenamiento, o null si el nombre apunta fuera de ella. */
    private Path resolveInsideStorage(String storedFileName) {
        try {
            Path file = storageLocation.resolve(storedFileName).normalize();
            return file.startsWith(storageLocation) && !file.equals(storageLocation) ? file : null;
        } catch (InvalidPathException ex) {
            return null;
        }
    }

    private static MediaType mediaTypeOf(Path file) {
        String name = file.getFileName().toString();
        if (name.endsWith(".jpg")) {
            return MediaType.IMAGE_JPEG;
        }
        if (name.endsWith(".png")) {
            return MediaType.IMAGE_PNG;
        }
        return MediaType.APPLICATION_OCTET_STREAM;
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
