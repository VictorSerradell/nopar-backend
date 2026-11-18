package com.company.nopar.service;

import com.company.nopar.exception.InvalidFileException;
import com.company.nopar.exception.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileStorageServiceTest {

    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00, 0x10, 0x4A, 0x46, 0x49, 0x46};
    private static final byte[] PNG = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D};
    private static final byte[] HTML = "<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path tempDir;

    private Path storageDir;
    private FileStorageService service;

    @BeforeEach
    void setUp() {
        storageDir = tempDir.resolve("uploads");
        service = new FileStorageService(storageDir.toString());
    }

    @Test
    void givenJpeg_whenStoreFile_thenSavedWithGeneratedNameAndJpgExtension() throws IOException {
        String name = service.storeFile(new MockMultipartFile("odometerPhoto", "foto.jpeg", "image/jpeg", JPEG));

        assertThat(name).endsWith(".jpg").doesNotContain("/").doesNotContain("\\");
        assertThat(Files.readAllBytes(storageDir.resolve(name))).isEqualTo(JPEG);
    }

    @Test
    void givenPng_whenStoreFile_thenSavedWithPngExtension() throws IOException {
        String name = service.storeFile(new MockMultipartFile("beaconPhoto", "foto.png", "image/png", PNG));

        assertThat(name).endsWith(".png");
        assertThat(Files.readAllBytes(storageDir.resolve(name))).isEqualTo(PNG);
    }

    @Test
    void givenClientControlledFilenameAndContentType_whenStoreFile_thenOnlyTheRealContentCounts() {
        // el nombre y el Content-Type los pone el cliente: ni la extension ni la ruta se toman de ahi
        String name = service.storeFile(new MockMultipartFile("f", "../../evil.jsp", "text/plain", JPEG));

        assertThat(name).endsWith(".jpg").doesNotContain("evil").doesNotContain("..");
        assertThat(storageDir.resolve(name)).exists();
    }

    @Test
    void givenNonImageContentDeclaredAsJpeg_whenStoreFile_thenInvalidFileAndNothingIsWritten() {
        MockMultipartFile fake = new MockMultipartFile("f", "foto.jpg", "image/jpeg", HTML);

        assertThatThrownBy(() -> service.storeFile(fake)).isInstanceOf(InvalidFileException.class);
        assertThat(storageDir).doesNotExist();
    }

    @Test
    void givenFileShorterThanAnyImageSignature_whenStoreFile_thenInvalidFile() {
        MockMultipartFile tiny = new MockMultipartFile("f", "foto.jpg", "image/jpeg", new byte[]{(byte) 0xFF, (byte) 0xD8});

        assertThatThrownBy(() -> service.storeFile(tiny)).isInstanceOf(InvalidFileException.class);
    }

    @Test
    void givenNullOrEmptyFile_whenStoreFile_thenReturnsNull() {
        assertThat(service.storeFile(null)).isNull();
        assertThat(service.storeFile(new MockMultipartFile("f", "foto.jpg", "image/jpeg", new byte[0]))).isNull();
    }

    @Test
    void givenStoredFile_whenDelete_thenItIsRemoved() {
        String name = service.storeFile(new MockMultipartFile("f", "foto.jpg", "image/jpeg", JPEG));
        assertThat(storageDir.resolve(name)).exists();

        service.delete(name);

        assertThat(storageDir.resolve(name)).doesNotExist();
    }

    @Test
    void givenNameOutsideStorage_whenDelete_thenFileIsLeftAlone() throws IOException {
        Path outside = Files.writeString(tempDir.resolve("secret.txt"), "no se toca");

        service.delete("../secret.txt");
        service.delete(null);

        assertThat(outside).exists();
    }

    // ------------------------------------------------------------ lectura

    @Test
    void givenStoredJpeg_whenLoad_thenReturnsItsBytesAndImageJpeg() throws IOException {
        String name = service.storeFile(new MockMultipartFile("f", "foto.jpg", "image/jpeg", JPEG));

        FileStorageService.StoredFile stored = service.load(name);

        assertThat(stored.mediaType()).isEqualTo(MediaType.IMAGE_JPEG);
        assertThat(stored.resource().getContentAsByteArray()).isEqualTo(JPEG);
    }

    @Test
    void givenStoredPng_whenLoad_thenReturnsImagePng() {
        String name = service.storeFile(new MockMultipartFile("f", "foto.png", "image/png", PNG));

        assertThat(service.load(name).mediaType()).isEqualTo(MediaType.IMAGE_PNG);
    }

    @Test
    void givenFileWithAnotherExtension_whenLoad_thenOctetStream() throws IOException {
        Files.createDirectories(storageDir);
        Files.write(storageDir.resolve("raro.bin"), JPEG);

        assertThat(service.load("raro.bin").mediaType()).isEqualTo(MediaType.APPLICATION_OCTET_STREAM);
    }

    @Test
    void givenUnknownFile_whenLoad_thenNotFound() {
        assertThatThrownBy(() -> service.load("no-existe.jpg")).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void givenNameOutsideStorage_whenLoad_thenNotFoundEvenIfTheFileExists() throws IOException {
        Files.write(tempDir.resolve("secret.jpg"), JPEG);

        assertThatThrownBy(() -> service.load("../secret.jpg")).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.load(tempDir.resolve("secret.jpg").toString()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void givenNullEmptyOrFolderName_whenLoad_thenNotFound() throws IOException {
        Files.createDirectories(storageDir);

        assertThatThrownBy(() -> service.load(null)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.load("")).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.load(".")).isInstanceOf(ResourceNotFoundException.class);
    }
}
