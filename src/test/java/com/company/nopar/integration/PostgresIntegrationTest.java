package com.company.nopar.integration;

import com.company.nopar.config.ExpiredRefreshTokenCleanup;
import com.company.nopar.config.NightlyVehicleReset;
import com.company.nopar.dto.InspectionCreateRequest;
import com.company.nopar.dto.InspectionResponse;
import com.company.nopar.dto.InspectionReviewRequest.Decision;
import com.company.nopar.dto.VehicleReturnRequest;
import com.company.nopar.dto.VehicleReturnResponse;
import com.company.nopar.entity.RefreshToken;
import com.company.nopar.entity.User;
import com.company.nopar.entity.Vehicle;
import com.company.nopar.entity.VehicleInspection;
import com.company.nopar.entity.VehicleReturn;
import com.company.nopar.exception.InvalidKilometersException;
import com.company.nopar.exception.InvalidRefreshTokenException;
import com.company.nopar.exception.InvalidStateException;
import com.company.nopar.exception.ResourceInUseException;
import com.company.nopar.repository.RefreshTokenRepository;
import com.company.nopar.repository.UserRepository;
import com.company.nopar.repository.VehicleInspectionRepository;
import com.company.nopar.repository.VehicleRepository;
import com.company.nopar.repository.VehicleReturnRepository;
import com.company.nopar.service.InspectionService;
import com.company.nopar.service.RefreshTokenService;
import com.company.nopar.service.VehicleReturnService;
import com.company.nopar.service.VehicleService;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.domain.Page;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.util.FileSystemUtils;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Prueba de integración contra un PostgreSQL real levantado con Testcontainers (hace falta Docker en marcha).
 *
 * Lo que no pueden comprobar los tests con mocks:
 * - que Flyway construye el esquema completo (V1, V2 y V3) sobre una base de datos vacía y que Hibernate
 *   (ddl-auto: validate) acepta ese esquema: si un nombre de columna no coincide, el contexto ni arranca;
 * - que los listados paginados se convierten a respuestas FUERA de la transacción, como hace el controlador con
 *   open-in-view desactivado, sin LazyInitializationException y sin una consulta extra por inspección (N+1);
 * - que las reglas de negocio (estado del vehículo, kilómetros, revisión) se persisten de verdad.
 *
 * La clase NO es @Transactional a propósito: cada llamada a un servicio abre y cierra su propia transacción,
 * igual que en producción. Los datos se borran antes de cada test.
 */
@SpringBootTest(properties = {
        // Estadísticas de Hibernate para contar las sentencias SQL de un listado
        "spring.jpa.properties.hibernate.generate_statistics=true",
        // Ni el .env local ni un administrador inicial deben influir en estos tests
        "application.bootstrap.admin.username=",
        "application.bootstrap.admin.password=",
        // El reseteo nocturno no debe dispararse durante los tests: se invoca a mano
        "application.fleet.nightly-reset.cron=-",
        "application.security.refresh-token-cleanup.cron=-"
})
@AutoConfigureMockMvc
@Testcontainers
class PostgresIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    /** Carpeta temporal para las fotos: los tests no tocan uploads/inspections. */
    private static final Path UPLOADS = createUploadsDir();

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("application.storage.location", UPLOADS::toString);
        // Clave de firma solo para estos tests (el JwtService la exige aunque aquí no se firme nada)
        registry.add("application.security.jwt.secret-key", () -> Base64.getEncoder()
                .encodeToString("integration-test-only-signing-key-0123456789".getBytes(StandardCharsets.UTF_8)));
    }

    @Autowired
    private InspectionService inspectionService;
    @Autowired
    private VehicleService vehicleService;
    @Autowired
    private VehicleReturnService vehicleReturnService;
    @Autowired
    private VehicleReturnRepository vehicleReturnRepository;
    @Autowired
    private NightlyVehicleReset nightlyVehicleReset;
    @Autowired
    private RefreshTokenService refreshTokenService;
    @Autowired
    private RefreshTokenRepository refreshTokenRepository;
    @Autowired
    private ExpiredRefreshTokenCleanup expiredRefreshTokenCleanup;
    @Autowired
    private VehicleInspectionRepository inspectionRepository;
    @Autowired
    private VehicleRepository vehicleRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private EntityManagerFactory entityManagerFactory;
    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    void cleanDatabase() {
        refreshTokenRepository.deleteAllInBatch();
        vehicleReturnRepository.deleteAllInBatch();
        inspectionRepository.deleteAllInBatch();
        vehicleRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    @AfterAll
    static void deleteUploads() throws IOException {
        FileSystemUtils.deleteRecursively(UPLOADS);
    }

    // ------------------------------------------------------------------ esquema: Flyway + validate de Hibernate

    @Test
    void flywayBuildsTheWholeSchemaOnAnEmptyDatabaseAndHibernateAcceptsIt() {
        // Si este test llega a ejecutarse es que el contexto arrancó: Flyway aplicó las migraciones y Hibernate
        // comprobó que las entidades coinciden con el esquema (incluida la columna beacon_v16_present de V1)
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        List<String> applied = jdbc.queryForList(
                "select version from flyway_schema_history where success order by installed_rank", String.class);
        List<String> indexes = jdbc.queryForList(
                "select indexname from pg_indexes where schemaname = 'public'", String.class);

        assertThat(applied).containsExactly("1", "2", "3", "4");
        assertThat(indexes).contains(
                "idx_vehicle_inspections_vehicle_date",
                "idx_vehicle_inspections_driver_date",
                "idx_vehicle_inspections_status_date",
                "idx_vehicle_inspections_date",
                "idx_refresh_tokens_user",
                "idx_vehicle_returns_vehicle_date");
    }

    // ------------------------------------------------------------------ listados paginados

    @Test
    void listingsBuildTheirResponsesOutsideTheTransactionWithoutLazyErrorsOrNPlusOne() {
        Scenario s = fiveInspections();
        Statistics statistics = statistics();

        statistics.clear();
        Page<VehicleInspection> all = inspectionService.getAllInspections(0, 20);
        List<InspectionResponse> responses = responses(all); // aquí ya no hay sesión de Hibernate abierta

        // Una consulta para la página y, como mucho, otra para el total: no una por inspección
        assertThat(statistics.getPrepareStatementCount()).isLessThanOrEqualTo(2L);
        assertThat(all.getTotalElements()).isEqualTo(5);
        assertThat(responses).hasSize(5);
        assertThat(responses).extracting(InspectionResponse::id).isSortedAccordingTo(Comparator.<Long>reverseOrder());
        assertThat(responses).allSatisfy(r -> {
            assertThat(r.vehicleLicensePlate()).isNotNull();
            assertThat(r.driverUsername()).isNotNull();
        });
        assertThat(responses.get(0).status()).isEqualTo(VehicleInspection.InspectionStatus.REJECTED);
        assertThat(responses.get(0).reviewedByUsername()).isEqualTo("admin1");
        assertThat(responses.get(0).reviewedAt()).isNotNull();

        // Última página: queda solo la inspección más antigua
        Page<VehicleInspection> lastPage = inspectionService.getAllInspections(2, 2);
        assertThat(lastPage.getTotalPages()).isEqualTo(3);
        assertThat(responses(lastPage)).extracting(InspectionResponse::id).containsExactly(s.oldest().getId());
    }

    @Test
    void filteredListingsReturnTheRightInspectionsWithTheirRelationsLoaded() {
        Scenario s = fiveInspections();

        assertThat(responses(inspectionService.getInspectionsByVehicle(s.van1().getId(), 0, 20)))
                .hasSize(2)
                .allSatisfy(r -> assertThat(r.vehicleLicensePlate()).isEqualTo("1111AAA"));

        assertThat(responses(inspectionService.getInspectionsByDriver(s.driver1().getId(), 0, 20)))
                .hasSize(3)
                .allSatisfy(r -> assertThat(r.driverUsername()).isEqualTo("driver1"));

        assertThat(responses(inspectionService.getInspectionsByStatus(VehicleInspection.InspectionStatus.SUBMITTED, 0, 20)))
                .hasSize(2);
        assertThat(responses(inspectionService.getInspectionsByStatus(VehicleInspection.InspectionStatus.APPROVED, 0, 20)))
                .hasSize(2);
        assertThat(responses(inspectionService.getInspectionsByStatus(VehicleInspection.InspectionStatus.REJECTED, 0, 20)))
                .singleElement()
                .satisfies(r -> assertThat(r.reviewedByUsername()).isEqualTo("admin1"));
    }

    @Test
    void vehiclesAreListedPagedByLicensePlate() {
        saveVehicle("2222BBB", 2000);
        saveVehicle("1111AAA", 1000);

        Page<Vehicle> firstPage = vehicleService.getAllVehicles(0, 1);

        assertThat(firstPage.getTotalElements()).isEqualTo(2);
        assertThat(firstPage.getContent()).extracting(Vehicle::getLicensePlate).containsExactly("1111AAA");
    }

    @Test
    void adminListingOverHttpReturnsThePageWithoutLazyLoadingErrors() throws Exception {
        Scenario s = fiveInspections();

        mockMvc.perform(get("/api/v1/admin/inspections")
                        .param("page", "0")
                        .param("size", "2")
                        .with(user(s.admin())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.totalElements").value(5))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.content[0].status").value("REJECTED"))
                .andExpect(jsonPath("$.content[0].vehicleLicensePlate").value("2222BBB"))
                .andExpect(jsonPath("$.content[0].driverUsername").value("driver1"))
                .andExpect(jsonPath("$.content[0].reviewedByUsername").value("admin1"));
    }

    // ------------------------------------------------------------------ reglas de negocio contra la base de datos

    @Test
    void aReportUpdatesTheVehicleAndKeepsTheDamagedState() {
        User driver = saveUser("driver1", User.Role.DRIVER);
        Vehicle available = saveVehicle("1111AAA", 1000);
        Vehicle damaged = saveVehicle("2222BBB", 2000);
        damaged.setStatus(Vehicle.VehicleStatus.DAMAGED);
        vehicleRepository.save(damaged);

        report(available, driver, 1150, null);
        report(damaged, driver, 2150, null);

        Vehicle availableAfter = vehicleRepository.findById(available.getId()).orElseThrow();
        assertThat(availableAfter.getCurrentKilometers()).isEqualTo(1150);
        assertThat(availableAfter.getStatus()).isEqualTo(Vehicle.VehicleStatus.IN_USE);

        Vehicle damagedAfter = vehicleRepository.findById(damaged.getId()).orElseThrow();
        assertThat(damagedAfter.getCurrentKilometers()).isEqualTo(2150);
        assertThat(damagedAfter.getStatus()).isEqualTo(Vehicle.VehicleStatus.DAMAGED);
    }

    @Test
    void aReportOnAVehicleInMaintenanceIsRejectedAndLeavesNothingStored() {
        User driver = saveUser("driver1", User.Role.DRIVER);
        Vehicle van = saveVehicle("1111AAA", 1000);
        van.setStatus(Vehicle.VehicleStatus.MAINTENANCE);
        vehicleRepository.save(van);

        assertThatThrownBy(() -> report(van, driver, 1150, null))
                .isInstanceOf(InvalidStateException.class)
                .hasMessageContaining("mantenimiento");

        Vehicle after = vehicleRepository.findById(van.getId()).orElseThrow();
        assertThat(after.getCurrentKilometers()).isEqualTo(1000);
        assertThat(after.getStatus()).isEqualTo(Vehicle.VehicleStatus.MAINTENANCE);
        assertThat(inspectionRepository.count()).isZero();

        // en cuanto un administrador lo pone de nuevo en servicio, vuelve a admitir partes
        vehicleService.changeVehicleStatus(van.getId(), Vehicle.VehicleStatus.AVAILABLE);
        report(van, driver, 1150, null);
        assertThat(statusOf(van)).isEqualTo(Vehicle.VehicleStatus.IN_USE);
    }

    @Test
    void aReportCannotLowerTheOdometerAndLeavesNothingStored() {
        User driver = saveUser("driver1", User.Role.DRIVER);
        Vehicle van = saveVehicle("1111AAA", 5000);
        InspectionCreateRequest rollback =
                new InspectionCreateRequest(van.getId(), 4999, true, true, true, null, true, true, true);

        assertThatThrownBy(() -> inspectionService.createInspection(rollback, odometerPhoto(), null, null, driver))
                .isInstanceOf(InvalidKilometersException.class);

        Vehicle after = vehicleRepository.findById(van.getId()).orElseThrow();
        assertThat(after.getCurrentKilometers()).isEqualTo(5000);
        assertThat(after.getStatus()).isEqualTo(Vehicle.VehicleStatus.AVAILABLE);
        assertThat(inspectionRepository.count()).isZero();
    }

    @Test
    void adminReviewOverHttpStoresTheReviewerAndARejectionSendsTheVehicleToMaintenance() throws Exception {
        User driver = saveUser("driver1", User.Role.DRIVER);
        User admin = saveUser("admin1", User.Role.ADMIN);
        Vehicle van = saveVehicle("1111AAA", 1000);
        VehicleInspection pending = report(van, driver, 1100, "Golpe en el paragolpes");
        assertThat(pending.getStatus()).isEqualTo(VehicleInspection.InspectionStatus.SUBMITTED);

        mockMvc.perform(patch("/api/v1/admin/inspections/{id}/review", pending.getId())
                        .with(user(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\": \"REJECTED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.reviewedByUsername").value("admin1"));

        assertThat(vehicleRepository.findById(van.getId()).orElseThrow().getStatus())
                .isEqualTo(Vehicle.VehicleStatus.MAINTENANCE);

        // Una inspección ya revisada no puede volver a revisarse
        mockMvc.perform(patch("/api/v1/admin/inspections/{id}/review", pending.getId())
                        .with(user(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\": \"APPROVED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Estado no válido"));
    }

    // ------------------------------------------------------------------ devolución del vehículo y reseteo nocturno

    @Test
    void theDriverReturnsTheVehicleAndTheReturnIsStored() {
        User driver = saveUser("driver1", User.Role.DRIVER);
        Vehicle van = saveVehicle("1111AAA", 1000);
        report(van, driver, 1100, null);
        assertThat(statusOf(van)).isEqualTo(Vehicle.VehicleStatus.IN_USE);

        VehicleReturn saved = vehicleReturnService.returnVehicle(van.getId(), new VehicleReturnRequest(1180, null), driver);
        VehicleReturnResponse response = VehicleReturnResponse.from(saved); // fuera de la transacción

        assertThat(response.vehicleLicensePlate()).isEqualTo("1111AAA");
        assertThat(response.returnedByUsername()).isEqualTo("driver1");
        assertThat(response.vehicleStatus()).isEqualTo(Vehicle.VehicleStatus.AVAILABLE);
        Vehicle after = vehicleRepository.findById(van.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(Vehicle.VehicleStatus.AVAILABLE);
        assertThat(after.getCurrentKilometers()).isEqualTo(1180);
        assertThat(vehicleReturnRepository.count()).isEqualTo(1);

        // Devolverlo otra vez (p. ej. un doble toque en el móvil) no crea una segunda devolución
        assertThatThrownBy(() -> vehicleReturnService.returnVehicle(
                van.getId(), new VehicleReturnRequest(1180, null), driver))
                .isInstanceOf(InvalidStateException.class);
        assertThat(vehicleReturnRepository.count()).isEqualTo(1);
    }

    @Test
    void aReturnWithDamagesSendsTheVehicleToMaintenanceAndKeepsTheDescription() {
        User driver = saveUser("driver1", User.Role.DRIVER);
        Vehicle van = saveVehicle("1111AAA", 1000);
        report(van, driver, 1100, null);

        vehicleReturnService.returnVehicle(van.getId(), new VehicleReturnRequest(1150, "Espejo roto"), driver);

        assertThat(statusOf(van)).isEqualTo(Vehicle.VehicleStatus.MAINTENANCE);
        assertThat(vehicleReturnRepository.findAll())
                .singleElement()
                .satisfies(r -> assertThat(r.getDamagesReported()).isEqualTo("Espejo roto"));
    }

    @Test
    void onlyTheDriverOfTheLastReportOrAnAdminCanReturnTheVehicle() {
        User driver = saveUser("driver1", User.Role.DRIVER);
        User other = saveUser("driver2", User.Role.DRIVER);
        User admin = saveUser("admin1", User.Role.ADMIN);
        Vehicle van = saveVehicle("1111AAA", 1000);
        report(van, driver, 1100, null);
        VehicleReturnRequest request = new VehicleReturnRequest(1150, null);

        assertThatThrownBy(() -> vehicleReturnService.returnVehicle(van.getId(), request, other))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(vehicleReturnRepository.count()).isZero();
        assertThat(statusOf(van)).isEqualTo(Vehicle.VehicleStatus.IN_USE);

        vehicleReturnService.returnVehicle(van.getId(), request, admin);

        assertThat(vehicleReturnRepository.count()).isEqualTo(1);
        assertThat(statusOf(van)).isEqualTo(Vehicle.VehicleStatus.AVAILABLE);
    }

    @Test
    void returnOverHttpIsCreatedForTheDriverAndRejectedForOthers() throws Exception {
        User driver = saveUser("driver1", User.Role.DRIVER);
        User other = saveUser("driver2", User.Role.DRIVER);
        Vehicle van = saveVehicle("1111AAA", 1000);
        report(van, driver, 1100, null);
        String body = "{\"kilometers\": 1180}";

        mockMvc.perform(post("/api/v1/fleet/vehicles/{id}/return", van.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/fleet/vehicles/{id}/return", van.getId())
                        .with(user(other))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/fleet/vehicles/{id}/return", van.getId())
                        .with(user(driver))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.vehicleLicensePlate").value("1111AAA"))
                .andExpect(jsonPath("$.returnedByUsername").value("driver1"))
                .andExpect(jsonPath("$.vehicleStatus").value("AVAILABLE"));
    }

    @Test
    void theNightlyResetReturnsOnlyTheVehiclesStillInUseToAvailable() {
        User driver = saveUser("driver1", User.Role.DRIVER);
        Vehicle inUse1 = saveVehicle("1111AAA", 1000);
        Vehicle inUse2 = saveVehicle("2222BBB", 2000);
        Vehicle inMaintenance = saveVehicle("3333CCC", 3000);
        inMaintenance.setStatus(Vehicle.VehicleStatus.MAINTENANCE);
        vehicleRepository.save(inMaintenance);
        Vehicle available = saveVehicle("4444DDD", 4000);
        report(inUse1, driver, 1100, null);
        report(inUse2, driver, 2100, null);

        nightlyVehicleReset.releaseVehiclesInUse();

        assertThat(statusOf(inUse1)).isEqualTo(Vehicle.VehicleStatus.AVAILABLE);
        assertThat(statusOf(inUse2)).isEqualTo(Vehicle.VehicleStatus.AVAILABLE);
        assertThat(statusOf(inMaintenance)).isEqualTo(Vehicle.VehicleStatus.MAINTENANCE);
        assertThat(statusOf(available)).isEqualTo(Vehicle.VehicleStatus.AVAILABLE);
        assertThat(vehicleService.releaseVehiclesInUse()).isZero();
    }

    // ------------------------------------------------------------------ cierre de sesión

    @Test
    void logoutRevokesOnlyThePresentedRefreshTokenAndLogoutAllRevokesEveryOne() throws Exception {
        User driver = saveUser("driver1", User.Role.DRIVER);
        String phone = refreshTokenService.createRefreshToken(driver).token();
        String tablet = refreshTokenService.createRefreshToken(driver).token();
        String laptop = refreshTokenService.createRefreshToken(driver).token();
        assertThat(refreshTokenRepository.count()).isEqualTo(3);

        // Cerrar la sesión del móvil no exige access token: el refresh token es la credencial
        String logoutPhone = "{\"refreshToken\": \"" + phone + "\"}";
        mockMvc.perform(post("/api/v1/auth/logout").contentType(MediaType.APPLICATION_JSON).content(logoutPhone))
                .andExpect(status().isNoContent());
        assertThat(refreshTokenRepository.count()).isEqualTo(2);
        assertThatThrownBy(() -> refreshTokenService.rotateRefreshToken(phone))
                .isInstanceOf(InvalidRefreshTokenException.class);

        // Repetirlo no falla ni revela nada, y las otras sesiones siguen abiertas
        mockMvc.perform(post("/api/v1/auth/logout").contentType(MediaType.APPLICATION_JSON).content(logoutPhone))
                .andExpect(status().isNoContent());
        assertThat(refreshTokenRepository.count()).isEqualTo(2);
        assertThat(refreshTokenService.rotateRefreshToken(tablet).user().getUsername()).isEqualTo("driver1");

        // Cerrar todas las sesiones sí exige estar autenticado
        mockMvc.perform(post("/api/v1/auth/logout-all")).andExpect(status().isUnauthorized());
        assertThat(refreshTokenRepository.count()).isEqualTo(2);

        mockMvc.perform(post("/api/v1/auth/logout-all").with(user(driver))).andExpect(status().isNoContent());
        assertThat(refreshTokenRepository.count()).isZero();
        assertThatThrownBy(() -> refreshTokenService.rotateRefreshToken(laptop))
                .isInstanceOf(InvalidRefreshTokenException.class);
    }

    // ------------------------------------------------------------------ limpieza de refresh tokens

    @Test
    void theCleanupDeletesOnlyTheExpiredRefreshTokens() {
        User driver = saveUser("driver1", User.Role.DRIVER);
        saveRefreshToken(driver, "expired-hash-1", Instant.now().minus(Duration.ofHours(1)));
        saveRefreshToken(driver, "expired-hash-2", Instant.now().minus(Duration.ofDays(30)));
        saveRefreshToken(driver, "valid-hash", Instant.now().plus(Duration.ofDays(6)));

        expiredRefreshTokenCleanup.deleteExpiredTokens();

        assertThat(refreshTokenRepository.findAll())
                .extracting(RefreshToken::getTokenHash)
                .containsExactly("valid-hash");
        // Sin nada caducado, no borra nada
        assertThat(refreshTokenService.deleteExpiredTokens()).isZero();
        assertThat(refreshTokenRepository.count()).isEqualTo(1);
    }

    @Test
    void deletingAVehicleWithInspectionsIsRejectedAndOneWithoutInspectionsIsDeleted() {
        User driver = saveUser("driver1", User.Role.DRIVER);
        Vehicle used = saveVehicle("1111AAA", 1000);
        Vehicle unused = saveVehicle("2222BBB", 2000);
        report(used, driver, 1100, null);

        assertThatThrownBy(() -> vehicleService.deleteVehicle(used.getId()))
                .isInstanceOf(ResourceInUseException.class);
        vehicleService.deleteVehicle(unused.getId());

        assertThat(vehicleRepository.findAll()).extracting(Vehicle::getLicensePlate).containsExactly("1111AAA");
    }

    // ------------------------------------------------------------------ datos de prueba

    /**
     * Cinco inspecciones sobre dos vehículos y dos conductores, de más antigua a más reciente:
     * APPROVED, SUBMITTED, SUBMITTED, APPROVED y REJECTED (la última, revisada por admin1).
     */
    private Scenario fiveInspections() {
        User admin = saveUser("admin1", User.Role.ADMIN);
        User driver1 = saveUser("driver1", User.Role.DRIVER);
        User driver2 = saveUser("driver2", User.Role.DRIVER);
        Vehicle van1 = saveVehicle("1111AAA", 1000);
        Vehicle van2 = saveVehicle("2222BBB", 2000);

        VehicleInspection oldest = report(van1, driver1, 1100, null);
        report(van1, driver1, 1200, "Golpe en el paragolpes");
        report(van2, driver2, 2100, "Faro roto");
        report(van2, driver2, 2200, null);
        VehicleInspection toReject = report(van2, driver1, 2300, "Rayón en la puerta");
        inspectionService.reviewInspection(toReject.getId(), Decision.REJECTED, admin);

        return new Scenario(admin, driver1, van1, oldest);
    }

    private record Scenario(User admin, User driver1, Vehicle van1, VehicleInspection oldest) {
    }

    private void saveRefreshToken(User user, String tokenHash, Instant expiry) {
        RefreshToken token = new RefreshToken();
        token.setUser(user);
        token.setTokenHash(tokenHash);
        token.setExpiryDate(expiry);
        refreshTokenRepository.save(token);
    }

    private Vehicle.VehicleStatus statusOf(Vehicle vehicle) {
        return vehicleRepository.findById(vehicle.getId()).orElseThrow().getStatus();
    }

    private User saveUser(String username, User.Role role) {
        User user = new User();
        user.setUsername(username);
        user.setPassword("not-a-real-hash");
        user.setRole(role);
        return userRepository.save(user);
    }

    private Vehicle saveVehicle(String licensePlate, int kilometers) {
        Vehicle vehicle = new Vehicle();
        vehicle.setLicensePlate(licensePlate);
        vehicle.setModel("Ford Transit");
        vehicle.setHasBeacon(true);
        vehicle.setCurrentKilometers(kilometers);
        vehicle.setStatus(Vehicle.VehicleStatus.AVAILABLE);
        return vehicleRepository.save(vehicle);
    }

    /** Parte matutino con el checklist completo; con daños queda SUBMITTED y sin ellos, APPROVED. */
    private VehicleInspection report(Vehicle vehicle, User driver, int kilometers, String damages) {
        InspectionCreateRequest request = new InspectionCreateRequest(
                vehicle.getId(), kilometers, true, true, true, damages, true, true, true);
        return inspectionService.createInspection(request, odometerPhoto(), null, null, driver);
    }

    /** Cabecera JPEG válida: el servicio decide el tipo de fichero por su contenido, no por el nombre. */
    private static MockMultipartFile odometerPhoto() {
        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16, 'J', 'F', 'I', 'F', 0, 1, 1, 0, 0, 1};
        return new MockMultipartFile("odometerPhoto", "odometer.jpg", "image/jpeg", jpeg);
    }

    private static List<InspectionResponse> responses(Page<VehicleInspection> page) {
        return page.map(InspectionResponse::from).getContent();
    }

    private Statistics statistics() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    private static Path createUploadsDir() {
        try {
            return Files.createTempDirectory("nopar-it-uploads");
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
