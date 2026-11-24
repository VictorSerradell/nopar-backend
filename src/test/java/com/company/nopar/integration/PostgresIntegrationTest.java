package com.company.nopar.integration;

import com.company.nopar.config.ExpiredRefreshTokenCleanup;
import com.company.nopar.config.NightlyVehicleReset;
import com.company.nopar.dto.AnnouncementRequest;
import com.company.nopar.dto.BoardAnnouncementResponse;
import com.company.nopar.dto.InspectionCreateRequest;
import com.company.nopar.dto.InspectionResponse;
import com.company.nopar.dto.InspectionReviewRequest.Decision;
import com.company.nopar.dto.ShiftCreateRequest;
import com.company.nopar.dto.ShiftResponse;
import com.company.nopar.dto.VehicleReturnRequest;
import com.company.nopar.dto.VehicleReturnResponse;
import com.company.nopar.entity.Announcement;
import com.company.nopar.entity.RefreshToken;
import com.company.nopar.entity.ShiftAssignment;
import com.company.nopar.entity.User;
import com.company.nopar.entity.Vehicle;
import com.company.nopar.entity.VehicleInspection;
import com.company.nopar.entity.VehicleReturn;
import com.company.nopar.entity.Wave;
import com.company.nopar.exception.InvalidKilometersException;
import com.company.nopar.exception.InvalidRefreshTokenException;
import com.company.nopar.exception.InvalidStateException;
import com.company.nopar.exception.ResourceInUseException;
import com.company.nopar.repository.AnnouncementReadRepository;
import com.company.nopar.repository.AnnouncementRepository;
import com.company.nopar.repository.DeviceTokenRepository;
import com.company.nopar.repository.RefreshTokenRepository;
import com.company.nopar.repository.ShiftAssignmentRepository;
import com.company.nopar.repository.UserRepository;
import com.company.nopar.repository.VehicleInspectionRepository;
import com.company.nopar.repository.VehicleRepository;
import com.company.nopar.repository.VehicleReturnRepository;
import com.company.nopar.repository.WaveRepository;
import com.company.nopar.service.AnnouncementService;
import com.company.nopar.service.DeviceService;
import com.company.nopar.service.InspectionService;
import com.company.nopar.service.RefreshTokenService;
import com.company.nopar.service.ShiftService;
import com.company.nopar.service.UserService;
import com.company.nopar.service.VehicleReturnService;
import com.company.nopar.service.VehicleService;
import com.jayway.jsonpath.JsonPath;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Prueba de integración contra un PostgreSQL real levantado con Testcontainers (hace falta Docker en marcha).
 *
 * Lo que no pueden comprobar los tests con mocks:
 * - que Flyway construye el esquema completo (de V1 a V6) sobre una base de datos vacía y que Hibernate
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

    /** Cabecera JPEG válida: el servicio decide el tipo de fichero por su contenido, no por el nombre. */
    private static final byte[] JPEG = {
            (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16, 'J', 'F', 'I', 'F', 0, 1, 1, 0, 0, 1};

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
    private ShiftService shiftService;
    @Autowired
    private UserService userService;
    @Autowired
    private ShiftAssignmentRepository shiftAssignmentRepository;
    @Autowired
    private WaveRepository waveRepository;
    @Autowired
    private AnnouncementService announcementService;
    @Autowired
    private AnnouncementRepository announcementRepository;
    @Autowired
    private AnnouncementReadRepository announcementReadRepository;
    @Autowired
    private NightlyVehicleReset nightlyVehicleReset;
    @Autowired
    private RefreshTokenService refreshTokenService;
    @Autowired
    private RefreshTokenRepository refreshTokenRepository;
    @Autowired
    private DeviceTokenRepository deviceTokenRepository;
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
        // Los turnos apuntan a olas, conductores y furgonetas: se borran antes que ellos
        shiftAssignmentRepository.deleteAllInBatch();
        waveRepository.deleteAllInBatch();
        // Las lecturas apuntan al aviso y al usuario; los avisos, a su autor
        announcementReadRepository.deleteAllInBatch();
        announcementRepository.deleteAllInBatch();
        refreshTokenRepository.deleteAllInBatch();
        deviceTokenRepository.deleteAllInBatch();
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

        List<String> uniques = jdbc.queryForList(
                "select conname from pg_constraint where contype = 'u'", String.class);

        assertThat(applied).containsExactly("1", "2", "3", "4", "5", "6", "7", "8");
        assertThat(indexes).contains(
                "idx_vehicle_inspections_vehicle_date",
                "idx_vehicle_inspections_driver_date",
                "idx_vehicle_inspections_status_date",
                "idx_vehicle_inspections_date",
                "idx_vehicle_inspections_shift",
                "idx_refresh_tokens_user",
                "idx_device_tokens_user",
                "idx_vehicle_returns_vehicle_date",
                "idx_shift_assignments_driver_date",
                "idx_shift_assignments_vehicle",
                "idx_shift_assignments_wave",
                "idx_announcements_board",
                "idx_announcement_reads_user");
        assertThat(uniques).contains(
                "uk_waves_name",
                "uk_shift_assignments_driver_day",
                "uk_shift_assignments_vehicle_day",
                "uk_announcement_reads_announcement_user",
                "uk_device_tokens_token");
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

    // ------------------------------------------------------------------ fotos de los partes

    @Test
    void inspectionPhotosAreServedOverHttpOnlyToTheDriverWhoMadeTheReportAndToAdmins() throws Exception {
        User admin = saveUser("admin1", User.Role.ADMIN);
        User author = saveUser("author", User.Role.DRIVER);
        User other = saveUser("other", User.Role.DRIVER);
        VehicleInspection report = report(saveVehicle("1111AAA", 1000), author, 1100, null);
        String odometerUrl = "/api/v1/inspections/" + report.getId() + "/photos/odometer";

        // El listado trae la URL de la foto que existe (la misma que atiende el controlador) y nada para las demás
        mockMvc.perform(get("/api/v1/inspections/driver/me").with(user(author)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].odometerPhotoUrl").value(odometerUrl))
                .andExpect(jsonPath("$.content[0].beaconPhotoUrl").doesNotExist())
                .andExpect(jsonPath("$.content[0].exteriorPhotoUrl").doesNotExist());

        // El conductor del parte y un administrador la descargan, con su tipo y con caché solo privada
        for (User allowed : List.of(author, admin)) {
            mockMvc.perform(get(odometerUrl).with(user(allowed)))
                    .andExpect(status().isOk())
                    .andExpect(content().contentType(MediaType.IMAGE_JPEG))
                    .andExpect(content().bytes(JPEG))
                    .andExpect(header().string("Cache-Control", "max-age=3600, private"));
        }

        // Otro conductor no ve las fotos de un parte ajeno, y sin token ni se llega al controlador
        mockMvc.perform(get(odometerUrl).with(user(other))).andExpect(status().isForbidden());
        mockMvc.perform(get(odometerUrl)).andExpect(status().isUnauthorized());

        // Foto que no se envió, tipo desconocido y parte inexistente: 404
        mockMvc.perform(get("/api/v1/inspections/{id}/photos/beacon", report.getId()).with(user(author)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/inspections/{id}/photos/passport", report.getId()).with(user(author)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/inspections/{id}/photos/odometer", report.getId() + 1000).with(user(admin)))
                .andExpect(status().isNotFound());
    }

    @Test
    void givenPhotoFileMissingFromDisk_whenGetPhoto_thenNotFound() throws Exception {
        User author = saveUser("author", User.Role.DRIVER);
        VehicleInspection report = report(saveVehicle("1111AAA", 1000), author, 1100, null);
        String storedName = inspectionRepository.findById(report.getId()).orElseThrow().getOdometerPhotoPath();
        Files.delete(UPLOADS.resolve(storedName));

        mockMvc.perform(get("/api/v1/inspections/{id}/photos/odometer", report.getId()).with(user(author)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Recurso no encontrado"));
    }

    // ------------------------------------------------------------------ contrato OpenAPI

    @Test
    void openApiContractIsPublicAndDescribesSecurityPhotosAndPages() throws Exception {
        String photoPath = "$.paths['/api/v1/inspections/{id}/photos/{type}'].get.responses['200'].content";

        // Sin token: los equipos de Android, iOS y web (y sus generadores de código) leen el contrato directamente
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("Nopar API"))
                // Bearer JWT, exigido por defecto en todas las operaciones
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.type").value("http"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.bearerFormat").value("JWT"))
                .andExpect(jsonPath("$.security[0].bearerAuth").isArray())
                // login, refresh y logout se llaman sin token (security: []); logout-all hereda el requisito global
                .andExpect(jsonPath("$.paths['/api/v1/auth/login'].post.security").isEmpty())
                .andExpect(jsonPath("$.paths['/api/v1/auth/refresh'].post.security").isEmpty())
                .andExpect(jsonPath("$.paths['/api/v1/auth/logout'].post.security").isEmpty())
                .andExpect(jsonPath("$.paths['/api/v1/auth/logout-all'].post.security").doesNotExist())
                // cambiar la contraseña exige token (hereda el requisito global) y el cuerpo lleva la actual y la nueva
                .andExpect(jsonPath("$.paths['/api/v1/auth/password'].patch").exists())
                .andExpect(jsonPath("$.paths['/api/v1/auth/password'].patch.security").doesNotExist())
                .andExpect(jsonPath("$.components.schemas.ChangePasswordRequest.properties.currentPassword").exists())
                .andExpect(jsonPath("$.components.schemas.ChangePasswordRequest.properties.newPassword").exists())
                // el administrador restablece la contraseña de un conductor sin conocer la actual
                .andExpect(jsonPath("$.paths['/api/v1/admin/users/{id}/password'].patch").exists())
                .andExpect(jsonPath("$.components.schemas.ResetPasswordRequest.properties.newPassword").exists())
                // el alta del parte es multipart y la foto se describe como imagen binaria
                .andExpect(jsonPath("$.paths['/api/v1/inspections'].post.requestBody.content['multipart/form-data']").exists())
                .andExpect(jsonPath(photoPath + "['image/jpeg']").exists())
                .andExpect(jsonPath(photoPath + "['image/png']").exists())
                // el parte expone las URLs de las fotos y los listados son páginas de partes
                .andExpect(jsonPath("$.components.schemas.InspectionResponse.properties.odometerPhotoUrl").exists())
                // enlace informativo con el turno del día
                .andExpect(jsonPath("$.components.schemas.InspectionResponse.properties.shiftId").exists())
                .andExpect(jsonPath("$.components.schemas.InspectionResponse.properties.assignedVehicleMatches").exists())
                .andExpect(jsonPath("$.components.schemas.PageResponseInspectionResponse.properties.content.items").exists())
                // cuadrantes: el rango de fechas es obligatorio y la respuesta describe la ola, el conductor y la furgoneta
                .andExpect(jsonPath("$.paths['/api/v1/shifts'].get.parameters[?(@.name == 'from')]").isNotEmpty())
                .andExpect(jsonPath("$.paths['/api/v1/shifts'].get.parameters[?(@.name == 'to')]").isNotEmpty())
                .andExpect(jsonPath("$.paths['/api/v1/shifts/me'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/admin/shifts'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/admin/shifts/{id}'].patch").exists())
                .andExpect(jsonPath("$.paths['/api/v1/admin/waves'].post").exists())
                .andExpect(jsonPath("$.components.schemas.ShiftResponse.properties.waveStartTime").exists())
                .andExpect(jsonPath("$.components.schemas.ShiftResponse.properties.vehicleLicensePlate").exists())
                // tablón de avisos: páginas con el indicador de lectura, marcar como leído y quién lo ha leído
                .andExpect(jsonPath("$.paths['/api/v1/announcements'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/announcements/{id}/read'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/admin/announcements'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/admin/announcements/{id}'].patch").exists())
                .andExpect(jsonPath("$.paths['/api/v1/admin/announcements/{id}/reads'].get").exists())
                .andExpect(jsonPath("$.components.schemas.BoardAnnouncementResponse.properties.read").exists())
                .andExpect(jsonPath("$.components.schemas.PageResponseBoardAnnouncementResponse.properties.content.items").exists())
                // dispositivos para las notificaciones push: se registran con POST y se dan de baja con el token en la query
                .andExpect(jsonPath("$.paths['/api/v1/devices'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/devices'].delete.parameters[?(@.name == 'token')]").isNotEmpty())
                .andExpect(jsonPath("$.components.schemas.DeviceRegistrationRequest.properties.token").exists())
                .andExpect(jsonPath("$.components.schemas.DeviceRegistrationRequest.properties.platform").exists())
                // el usuario autenticado sale del token: ni es un parámetro ni su entidad aparece en el contrato
                .andExpect(jsonPath("$.components.schemas.User").doesNotExist())
                .andExpect(jsonPath("$.paths..parameters[?(@.name in ['currentUser', 'currentAdmin'])]").isEmpty());
    }

    @Test
    void swaggerUiIsReachableWithoutToken() throws Exception {
        for (String path : List.of("/swagger-ui.html", "/swagger-ui/index.html")) {
            int status = mockMvc.perform(get(path)).andReturn().getResponse().getStatus();

            assertThat(status).as(path).isNotIn(401, 403);
        }
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

    @Test
    void changingThePasswordKeepsThisDeviceLoggedInAndClosesEveryOtherSession() throws Exception {
        userService.createDriver("driver1", "clave-vieja-123");

        // Dos dispositivos con la sesión iniciada de verdad: login contra la BD, con BCrypt y JWT reales
        ResultActions phone = login("driver1", "clave-vieja-123").andExpect(status().isOk());
        String phoneAccess = field(phone, "accessToken");
        String phoneRefresh = field(phone, "refreshToken");
        String tabletRefresh = field(login("driver1", "clave-vieja-123").andExpect(status().isOk()), "refreshToken");
        assertThat(refreshTokenRepository.count()).isEqualTo(2);

        String change = "{\"currentPassword\": \"clave-vieja-123\", \"newPassword\": \"clave-nueva-456\"}";

        // Sin access token no se puede cambiar la contraseña
        mockMvc.perform(patch("/api/v1/auth/password").contentType(MediaType.APPLICATION_JSON).content(change))
                .andExpect(status().isUnauthorized());

        // Con la contraseña actual equivocada: 400 (no 401) y las sesiones siguen abiertas
        mockMvc.perform(patch("/api/v1/auth/password")
                        .header("Authorization", "Bearer " + phoneAccess)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\": \"no-es-esta-1\", \"newPassword\": \"clave-nueva-456\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("La contraseña actual no es correcta"));
        assertThat(refreshTokenRepository.count()).isEqualTo(2);

        // Con la actual correcta: 200 y un par de tokens nuevo para este dispositivo
        ResultActions changed = mockMvc.perform(patch("/api/v1/auth/password")
                        .header("Authorization", "Bearer " + phoneAccess)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(change))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty());
        String newAccess = field(changed, "accessToken");
        String newRefresh = field(changed, "refreshToken");

        // Todas las sesiones anteriores quedan cerradas: solo sobrevive el refresh token recién emitido
        assertThat(refreshTokenRepository.count()).isEqualTo(1);
        assertThatThrownBy(() -> refreshTokenService.rotateRefreshToken(phoneRefresh))
                .isInstanceOf(InvalidRefreshTokenException.class);
        assertThatThrownBy(() -> refreshTokenService.rotateRefreshToken(tabletRefresh))
                .isInstanceOf(InvalidRefreshTokenException.class);

        // Los tokens nuevos funcionan: el access token autentica y el refresh token renueva la sesión
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\": \"" + newRefresh + "\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/auth/logout-all").header("Authorization", "Bearer " + newAccess))
                .andExpect(status().isNoContent());

        // La contraseña vieja ya no sirve y la nueva sí
        login("driver1", "clave-vieja-123").andExpect(status().isUnauthorized());
        login("driver1", "clave-nueva-456").andExpect(status().isOk());
    }

    @Test
    void anAdminResetsADriversPasswordWithoutTheCurrentOneAndOnlyADriversOverHttp() throws Exception {
        User driver = userService.createDriver("driver1", "clave-olvidada-1");
        User otherDriver = userService.createDriver("driver2", "otra-clave-123");
        User admin = saveUser("admin1", User.Role.ADMIN);

        // El conductor tiene sesión en dos dispositivos; el otro conductor, en uno, y no debe verse afectado
        login("driver1", "clave-olvidada-1").andExpect(status().isOk());
        login("driver1", "clave-olvidada-1").andExpect(status().isOk());
        login("driver2", "otra-clave-123").andExpect(status().isOk());
        assertThat(refreshTokenRepository.count()).isEqualTo(3);

        String body = "{\"newPassword\": \"clave-nueva-456\"}";
        String path = "/api/v1/admin/users/" + driver.getId() + "/password";

        // Sin token: 401. Con el rol de conductor: 403. En ambos casos no cambia nada
        mockMvc.perform(patch(path).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(patch(path).with(user(otherDriver)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        assertThat(refreshTokenRepository.count()).isEqualTo(3);

        // El administrador la restablece: 204 y se cierran las sesiones de ESE conductor, no las del otro
        mockMvc.perform(patch(path).with(user(admin)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNoContent());
        assertThat(refreshTokenRepository.count()).isEqualTo(1);
        login("driver1", "clave-olvidada-1").andExpect(status().isUnauthorized());
        login("driver1", "clave-nueva-456").andExpect(status().isOk());
        login("driver2", "otra-clave-123").andExpect(status().isOk());

        // Un administrador no es un conductor: su contraseña se cambia con la actual (PATCH /api/v1/auth/password)
        mockMvc.perform(patch("/api/v1/admin/users/" + admin.getId() + "/password")
                        .with(user(admin)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Solo se puede restablecer la contraseña de un conductor"));

        // Un usuario que no existe
        mockMvc.perform(patch("/api/v1/admin/users/999999/password")
                        .with(user(admin)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound());
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

    // ------------------------------------------------------------------ cuadrantes

    @Test
    void theAdminBuildsTheScheduleOverHttpAndDriversReadIt() throws Exception {
        User admin = saveUser("admin1", User.Role.ADMIN);
        User driver1 = saveUser("driver1", User.Role.DRIVER);
        User driver2 = saveUser("driver2", User.Role.DRIVER);
        User driver3 = saveUser("driver3", User.Role.DRIVER);
        Vehicle van1 = saveVehicle("1111AAA", 1000);
        Vehicle van2 = saveVehicle("2222BBB", 2000);
        MediaType json = MediaType.APPLICATION_JSON;
        String earlyWaveBody = """
                {"name": "Ola 1", "startTime": "09:30"}
                """;

        // Solo un administrador crea olas
        mockMvc.perform(post("/api/v1/admin/waves").contentType(json).content(earlyWaveBody))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/admin/waves").with(user(driver1)).contentType(json).content(earlyWaveBody))
                .andExpect(status().isForbidden());
        long early = idOf(mockMvc.perform(post("/api/v1/admin/waves").with(user(admin)).contentType(json).content(earlyWaveBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Ola 1"))
                .andExpect(jsonPath("$.startTime", startsWith("09:30"))));
        long late = idOf(mockMvc.perform(post("/api/v1/admin/waves").with(user(admin)).contentType(json).content("""
                        {"name": "Ola 2", "startTime": "11:00"}
                        """))
                .andExpect(status().isCreated()));
        // El nombre es único sin distinguir mayúsculas
        mockMvc.perform(post("/api/v1/admin/waves").with(user(admin)).contentType(json).content("""
                        {"name": "OLA 1", "startTime": "10:00"}
                        """))
                .andExpect(status().isConflict());
        mockMvc.perform(get("/api/v1/admin/waves").with(user(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Ola 1"))
                .andExpect(jsonPath("$[1].name").value("Ola 2"));

        // El administrador asigna turnos: el conductor y la furgoneta quedan ocupados ese día
        String day = "2026-11-19";
        long shift1 = idOf(mockMvc.perform(post("/api/v1/admin/shifts").with(user(admin)).contentType(json)
                        .content(shiftBody(day, late, driver1, van1)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.waveName").value("Ola 2"))
                .andExpect(jsonPath("$.driverUsername").value("driver1"))
                .andExpect(jsonPath("$.vehicleLicensePlate").value("1111AAA")));
        long shift2 = idOf(mockMvc.perform(post("/api/v1/admin/shifts").with(user(admin)).contentType(json)
                        .content(shiftBody(day, early, driver2, van2)))
                .andExpect(status().isCreated()));
        mockMvc.perform(post("/api/v1/admin/shifts").with(user(admin)).contentType(json)
                        .content(shiftBody(day, early, driver1, van2)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Recurso duplicado"));
        mockMvc.perform(post("/api/v1/admin/shifts").with(user(admin)).contentType(json)
                        .content(shiftBody(day, early, driver3, van1)))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/api/v1/admin/shifts").with(user(admin)).contentType(json)
                        .content(shiftBody(day, early, admin, van1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("El usuario admin1 no es un conductor"));
        mockMvc.perform(post("/api/v1/admin/shifts").with(user(admin)).contentType(json)
                        .content(shiftBody(day, 999999L, driver3, van1)))
                .andExpect(status().isNotFound());

        // Modificar un turno: no puede chocar con otro, pero sí consigo mismo
        mockMvc.perform(patch("/api/v1/admin/shifts/{id}", shift2).with(user(admin)).contentType(json).content("""
                        {"vehicleId": %d}
                        """.formatted(van1.getId())))
                .andExpect(status().isConflict());
        mockMvc.perform(patch("/api/v1/admin/shifts/{id}", shift2).with(user(admin)).contentType(json).content("{}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(patch("/api/v1/admin/shifts/{id}", shift2).with(user(admin)).contentType(json).content("""
                        {"workDate": "2026-11-20"}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workDate").value("2026-11-20"))
                .andExpect(jsonPath("$.driverUsername").value("driver2"));

        // Los conductores leen el cuadrante completo y sus propios turnos
        mockMvc.perform(get("/api/v1/shifts").param("from", day).param("to", "2026-11-20").with(user(driver1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].workDate").value(day))
                .andExpect(jsonPath("$[0].driverUsername").value("driver1"))
                .andExpect(jsonPath("$[1].workDate").value("2026-11-20"))
                .andExpect(jsonPath("$[1].driverUsername").value("driver2"));
        mockMvc.perform(get("/api/v1/shifts/me").param("from", day).param("to", "2026-11-20").with(user(driver2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].driverUsername").value("driver2"))
                .andExpect(jsonPath("$[0].vehicleLicensePlate").value("2222BBB"));
        mockMvc.perform(get("/api/v1/shifts/me").param("from", day).param("to", "2026-11-20").with(user(admin)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/shifts").param("from", day).param("to", "2026-12-31").with(user(driver1)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/shifts").param("from", day))
                .andExpect(status().isUnauthorized());

        // Una ola con turnos no se puede borrar; sin ellos, sí
        mockMvc.perform(delete("/api/v1/admin/waves/{id}", late).with(user(admin)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Recurso en uso"));
        mockMvc.perform(delete("/api/v1/admin/shifts/{id}", shift1).with(user(driver1)))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/v1/admin/shifts/{id}", shift1).with(user(admin)))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/v1/admin/shifts/{id}", shift1).with(user(admin)))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/v1/admin/waves/{id}", late).with(user(admin)))
                .andExpect(status().isNoContent());
    }

    @Test
    void devicesAreRegisteredPerUserAndMoveToWhoeverUsesThePhoneNext() throws Exception {
        User ana = saveUser("ana", User.Role.DRIVER);
        User bea = saveUser("bea", User.Role.DRIVER);
        User admin = saveUser("admin1", User.Role.ADMIN);
        MediaType json = MediaType.APPLICATION_JSON;
        String registration = """
                {"token": "tok-movil-ana", "platform": "ANDROID"}
                """;

        mockMvc.perform(post("/api/v1/devices").contentType(json).content(registration))
                .andExpect(status().isUnauthorized());

        // El registro es idempotente: repetirlo no duplica el dispositivo
        mockMvc.perform(post("/api/v1/devices").with(user(ana)).contentType(json).content(registration))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/v1/devices").with(user(ana)).contentType(json).content(registration))
                .andExpect(status().isNoContent());
        assertThat(deviceTokenRepository.count()).isEqualTo(1);
        assertThat(deviceTokenRepository.countByUserId(ana.getId())).isEqualTo(1);

        // Bea inicia sesion en ese mismo movil: el token pasa a ella y Ana deja de recibir lo suyo
        mockMvc.perform(post("/api/v1/devices").with(user(bea)).contentType(json).content(registration))
                .andExpect(status().isNoContent());
        assertThat(deviceTokenRepository.count()).isEqualTo(1);
        assertThat(deviceTokenRepository.countByUserId(ana.getId())).isZero();
        assertThat(deviceTokenRepository.countByUserId(bea.getId())).isEqualTo(1);

        // Nadie da de baja el dispositivo de otro; su dueño si, y repetirlo no falla
        mockMvc.perform(delete("/api/v1/devices").param("token", "tok-movil-ana").with(user(ana)))
                .andExpect(status().isNoContent());
        assertThat(deviceTokenRepository.count()).isEqualTo(1);
        mockMvc.perform(delete("/api/v1/devices").param("token", "tok-movil-ana"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/v1/devices").param("token", "tok-movil-ana").with(user(bea)))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/v1/devices").param("token", "tok-movil-ana").with(user(bea)))
                .andExpect(status().isNoContent());
        assertThat(deviceTokenRepository.count()).isZero();

        // Los datos no validos se rechazan y los administradores tambien pueden registrar su dispositivo
        mockMvc.perform(post("/api/v1/devices").with(user(ana)).contentType(json).content("""
                        {"token": " ", "platform": "ANDROID"}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.token").value("El token del dispositivo es obligatorio"));
        mockMvc.perform(post("/api/v1/devices").with(user(admin)).contentType(json).content("""
                        {"token": "tok-web-admin", "platform": "WEB"}
                        """))
                .andExpect(status().isNoContent());
        assertThat(deviceTokenRepository.countByUserId(admin.getId())).isEqualTo(1);
    }

    @Test
    void aUserKeepsOnlyTheMostRecentlyRegisteredDevices() throws Exception {
        User ana = saveUser("ana", User.Role.DRIVER);
        int total = DeviceService.MAX_DEVICES_PER_USER + 2;

        for (int i = 1; i <= total; i++) {
            mockMvc.perform(post("/api/v1/devices").with(user(ana)).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"token\": \"tok-%d\", \"platform\": \"IOS\"}".formatted(i)))
                    .andExpect(status().isNoContent());
        }

        // se descartan los dos primeros: quedan los mas recientes
        List<String> kept = deviceTokenRepository.findAll().stream().map(device -> device.getToken()).toList();
        assertThat(kept).containsExactlyInAnyOrderElementsOf(
                IntStream.rangeClosed(3, total).mapToObj(i -> "tok-" + i).toList());
    }

    @Test
    void aReportIsLinkedToTheShiftOfTheDayWithoutEverBeingBlockedByIt() {
        User admin = saveUser("admin1", User.Role.ADMIN);
        User ana = saveUser("ana", User.Role.DRIVER);
        User bea = saveUser("bea", User.Role.DRIVER);
        User carlos = saveUser("carlos", User.Role.DRIVER);
        Vehicle van1 = saveVehicle("1111AAA", 1000);
        Vehicle van2 = saveVehicle("2222BBB", 2000);
        Vehicle van3 = saveVehicle("3333CCC", 3000);
        Vehicle van4 = saveVehicle("4444DDD", 4000);
        Wave wave = saveWave("Ola 1", LocalTime.of(9, 30));
        LocalDate today = LocalDate.now();
        ShiftAssignment anaShift = shiftService.createShift(
                new ShiftCreateRequest(today, wave.getId(), ana.getId(), van1.getId()), admin);
        ShiftAssignment beaShift = shiftService.createShift(
                new ShiftCreateRequest(today, wave.getId(), bea.getId(), van2.getId()), admin);
        // el turno de carlos es mañana: hoy no tiene
        shiftService.createShift(new ShiftCreateRequest(today.plusDays(1), wave.getId(), carlos.getId(), van4.getId()), admin);

        VehicleInspection asAssigned = report(van1, ana, 1500, null);
        VehicleInspection otherVan = report(van3, bea, 3500, null);
        VehicleInspection withoutShift = report(van4, carlos, 4500, null);

        // se lee de la base de datos: las columnas nuevas se guardan y Hibernate las acepta
        InspectionResponse asAssignedResponse = savedResponse(asAssigned);
        assertThat(asAssignedResponse.shiftId()).isEqualTo(anaShift.getId());
        assertThat(asAssignedResponse.assignedVehicleMatches()).isTrue();

        // otra furgoneta distinta de la asignada: el parte se acepta y la discrepancia queda anotada
        InspectionResponse otherVanResponse = savedResponse(otherVan);
        assertThat(otherVanResponse.shiftId()).isEqualTo(beaShift.getId());
        assertThat(otherVanResponse.assignedVehicleMatches()).isFalse();
        assertThat(otherVanResponse.status()).isEqualTo(VehicleInspection.InspectionStatus.APPROVED);

        InspectionResponse withoutShiftResponse = savedResponse(withoutShift);
        assertThat(withoutShiftResponse.shiftId()).isNull();
        assertThat(withoutShiftResponse.assignedVehicleMatches()).isNull();
        assertThat(withoutShiftResponse.status()).isEqualTo(VehicleInspection.InspectionStatus.APPROVED);

        // borrar el turno no falla por el enlace: el parte se conserva y pierde el enlace
        shiftService.deleteShift(anaShift.getId());
        InspectionResponse afterDelete = savedResponse(asAssigned);
        assertThat(afterDelete.id()).isEqualTo(asAssigned.getId());
        assertThat(afterDelete.shiftId()).isNull();
        assertThat(afterDelete.assignedVehicleMatches()).isNull();
    }

    @Test
    void aVehicleInMaintenanceOrDamagedCannotBeAssignedToAShiftOverHttp() throws Exception {
        User admin = saveUser("admin1", User.Role.ADMIN);
        User driver = saveUser("driver1", User.Role.DRIVER);
        Wave wave = saveWave("Ola 1", LocalTime.of(9, 30));
        Vehicle inService = saveVehicle("1111AAA", 1000);
        Vehicle inMaintenance = saveVehicle("2222BBB", 2000);
        Vehicle damaged = saveVehicle("3333CCC", 3000);
        vehicleService.changeVehicleStatus(inMaintenance.getId(), Vehicle.VehicleStatus.MAINTENANCE);
        vehicleService.changeVehicleStatus(damaged.getId(), Vehicle.VehicleStatus.DAMAGED);
        MediaType json = MediaType.APPLICATION_JSON;
        String day = "2026-11-19";

        // Al crear: 409 con el motivo, y no se guarda nada
        mockMvc.perform(post("/api/v1/admin/shifts").with(user(admin)).contentType(json)
                        .content(shiftBody(day, wave.getId(), driver, inMaintenance)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Estado no válido"))
                .andExpect(jsonPath("$.detail", startsWith("El vehículo 2222BBB está en mantenimiento")));
        mockMvc.perform(post("/api/v1/admin/shifts").with(user(admin)).contentType(json)
                        .content(shiftBody(day, wave.getId(), driver, damaged)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", startsWith("El vehículo 3333CCC está dañado")));
        assertThat(shiftAssignmentRepository.count()).isZero();

        // Al modificar: tampoco se puede cambiar a una furgoneta fuera de servicio, y el turno queda como estaba
        long shiftId = idOf(mockMvc.perform(post("/api/v1/admin/shifts").with(user(admin)).contentType(json)
                        .content(shiftBody(day, wave.getId(), driver, inService)))
                .andExpect(status().isCreated()));
        mockMvc.perform(patch("/api/v1/admin/shifts/{id}", shiftId).with(user(admin)).contentType(json)
                        .content("{\"vehicleId\": %d}".formatted(inMaintenance.getId())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Estado no válido"));
        mockMvc.perform(patch("/api/v1/admin/shifts/{id}", shiftId).with(user(admin)).contentType(json)
                        .content("{\"vehicleId\": %d}".formatted(damaged.getId())))
                .andExpect(status().isConflict());
        assertThat(shiftAssignmentRepository.findWithDetailsById(shiftId).orElseThrow().getVehicle().getId())
                .isEqualTo(inService.getId());

        // Si la furgoneta de un turno pasa a mantenimiento después, el turno se sigue pudiendo editar sin cambiarla
        vehicleService.changeVehicleStatus(inService.getId(), Vehicle.VehicleStatus.MAINTENANCE);
        mockMvc.perform(patch("/api/v1/admin/shifts/{id}", shiftId).with(user(admin)).contentType(json)
                        .content("{\"workDate\": \"2026-11-20\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workDate").value("2026-11-20"))
                .andExpect(jsonPath("$.vehicleLicensePlate").value("1111AAA"));

        // En cuanto un administrador la pone de nuevo en servicio, ya se puede asignar
        vehicleService.changeVehicleStatus(inMaintenance.getId(), Vehicle.VehicleStatus.AVAILABLE);
        mockMvc.perform(patch("/api/v1/admin/shifts/{id}", shiftId).with(user(admin)).contentType(json)
                        .content("{\"vehicleId\": %d}".formatted(inMaintenance.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.vehicleLicensePlate").value("2222BBB"));
    }

    @Test
    void theScheduleIsListedInOneStatementInReadingOrderAndTheDatabaseRejectsDoubleBookings() {
        User admin = saveUser("admin1", User.Role.ADMIN);
        User ana = saveUser("ana", User.Role.DRIVER);
        User bea = saveUser("bea", User.Role.DRIVER);
        User carlos = saveUser("carlos", User.Role.DRIVER);
        User dani = saveUser("dani", User.Role.DRIVER);
        Vehicle van1 = saveVehicle("1111AAA", 1000);
        Vehicle van2 = saveVehicle("2222BBB", 2000);
        Vehicle van3 = saveVehicle("3333CCC", 3000);
        Vehicle van4 = saveVehicle("4444DDD", 4000);
        Wave early = saveWave("Ola 1", LocalTime.of(9, 30));
        Wave late = saveWave("Ola 2", LocalTime.of(11, 0));
        LocalDate day = LocalDate.of(2026, 11, 19);
        shiftService.createShift(new ShiftCreateRequest(day, late.getId(), ana.getId(), van1.getId()), admin);
        shiftService.createShift(new ShiftCreateRequest(day, early.getId(), carlos.getId(), van2.getId()), admin);
        shiftService.createShift(new ShiftCreateRequest(day, early.getId(), bea.getId(), van3.getId()), admin);
        shiftService.createShift(new ShiftCreateRequest(day.plusDays(1), early.getId(), ana.getId(), van1.getId()), admin);

        Statistics statistics = statistics();
        statistics.clear();
        List<ShiftResponse> responses = ShiftResponse.fromAll(shiftService.getShifts(day, day.plusDays(1)));

        // Ola, conductor y furgoneta vienen en la misma consulta: sin LazyInitializationException ni una por turno
        assertThat(statistics.getPrepareStatementCount()).isLessThanOrEqualTo(1L);
        // Se lee en orden: día, hora de la ola y conductor
        assertThat(responses).extracting(ShiftResponse::driverUsername).containsExactly("bea", "carlos", "ana", "ana");
        assertThat(responses).extracting(ShiftResponse::waveName).containsExactly("Ola 1", "Ola 1", "Ola 2", "Ola 1");
        assertThat(responses).allSatisfy(r -> assertThat(r.vehicleLicensePlate()).isNotNull());
        assertThat(shiftService.getShiftsOfDriver(ana, day, day.plusDays(1))).hasSize(2);

        // Aunque el servicio se salte la comprobación (dos peticiones simultáneas), manda la base de datos
        assertThatThrownBy(() -> shiftAssignmentRepository.saveAndFlush(shift(day, early, ana, van4, admin)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> shiftAssignmentRepository.saveAndFlush(shift(day, early, dani, van1, admin)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(shiftAssignmentRepository.count()).isEqualTo(4);
    }

    @Test
    void aVehicleWithShiftsCannotBeDeleted() {
        User admin = saveUser("admin1", User.Role.ADMIN);
        User driver = saveUser("driver1", User.Role.DRIVER);
        Vehicle assigned = saveVehicle("1111AAA", 1000);
        Vehicle spare = saveVehicle("2222BBB", 2000);
        Wave wave = saveWave("Ola 1", LocalTime.of(9, 30));
        shiftService.createShift(
                new ShiftCreateRequest(LocalDate.of(2026, 11, 19), wave.getId(), driver.getId(), assigned.getId()), admin);

        assertThatThrownBy(() -> vehicleService.deleteVehicle(assigned.getId()))
                .isInstanceOf(ResourceInUseException.class)
                .hasMessageContaining("turnos");
        vehicleService.deleteVehicle(spare.getId());

        assertThat(vehicleRepository.findAll()).extracting(Vehicle::getLicensePlate).containsExactly("1111AAA");
    }

    // ------------------------------------------------------------------ tablón de avisos

    @Test
    void theAdminPublishesAnnouncementsAndUsersReadThemOverHttp() throws Exception {
        User admin = saveUser("admin1", User.Role.ADMIN);
        User driver1 = saveUser("driver1", User.Role.DRIVER);
        User driver2 = saveUser("driver2", User.Role.DRIVER);
        MediaType json = MediaType.APPLICATION_JSON;
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        String pinnedBody = """
                {"title": "Lockers", "body": "Recordad cerrar la puerta", "pinned": true}
                """;

        // Solo un administrador publica
        mockMvc.perform(post("/api/v1/admin/announcements").contentType(json).content(pinnedBody))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/admin/announcements").with(user(driver1)).contentType(json).content(pinnedBody))
                .andExpect(status().isForbidden());
        long pinned = idOf(mockMvc.perform(post("/api/v1/admin/announcements").with(user(admin)).contentType(json).content(pinnedBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.pinned").value(true))
                .andExpect(jsonPath("$.createdByUsername").value("admin1")));
        long older = idOf(mockMvc.perform(post("/api/v1/admin/announcements").with(user(admin)).contentType(json).content("""
                        {"title": "Entregas", "body": "Entregad antes de las 14:00"}
                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.pinned").value(false)));
        long newer = idOf(mockMvc.perform(post("/api/v1/admin/announcements").with(user(admin)).contentType(json).content("""
                        {"title": "Nueva ola", "body": "Hay una ola nueva a las 11:00"}
                        """))
                .andExpect(status().isCreated()));

        // El tablón: primero el fijado y luego los más recientes; nadie ha leído nada
        mockMvc.perform(get("/api/v1/announcements").with(user(driver1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.content[0].id").value(pinned))
                .andExpect(jsonPath("$.content[1].id").value(newer))
                .andExpect(jsonPath("$.content[2].id").value(older))
                .andExpect(jsonPath("$.content[0].read").value(false))
                .andExpect(jsonPath("$.content[1].read").value(false))
                .andExpect(jsonPath("$.content[2].read").value(false));
        mockMvc.perform(get("/api/v1/announcements"))
                .andExpect(status().isUnauthorized());

        // driver1 lee el aviso reciente; repetirlo no cambia nada y conserva la primera fecha
        mockMvc.perform(post("/api/v1/announcements/{id}/read", newer).with(user(driver1)))
                .andExpect(status().isNoContent());
        String sql = "select read_at from announcement_reads where announcement_id = ? and user_id = ?";
        LocalDateTime firstRead = jdbc.queryForObject(sql, LocalDateTime.class, newer, driver1.getId());
        mockMvc.perform(post("/api/v1/announcements/{id}/read", newer).with(user(driver1)))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject(sql, LocalDateTime.class, newer, driver1.getId())).isEqualTo(firstRead);
        assertThat(jdbc.queryForObject("select count(*) from announcement_reads", Long.class)).isEqualTo(1L);
        mockMvc.perform(post("/api/v1/announcements/{id}/read", 999999L).with(user(driver1)))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/announcements/{id}/read", newer))
                .andExpect(status().isUnauthorized());

        // La lectura es de cada usuario: driver1 ve el aviso como leído y driver2, no
        mockMvc.perform(get("/api/v1/announcements").with(user(driver1)))
                .andExpect(jsonPath("$.content[0].read").value(false))
                .andExpect(jsonPath("$.content[1].id").value(newer))
                .andExpect(jsonPath("$.content[1].read").value(true))
                .andExpect(jsonPath("$.content[2].read").value(false));
        mockMvc.perform(get("/api/v1/announcements").with(user(driver2)))
                .andExpect(jsonPath("$.content[1].id").value(newer))
                .andExpect(jsonPath("$.content[1].read").value(false));

        // El administrador ve quién lo ha leído; un conductor no
        mockMvc.perform(post("/api/v1/announcements/{id}/read", newer).with(user(driver2)))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/admin/announcements/{id}/reads", newer).with(user(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].username").value("driver1"))
                .andExpect(jsonPath("$[1].username").value("driver2"))
                .andExpect(jsonPath("$[0].readAt").exists());
        mockMvc.perform(get("/api/v1/admin/announcements/{id}/reads", newer).with(user(driver1)))
                .andExpect(status().isForbidden());

        // Fijar el aviso reciente lo pone el primero; un título vacío o ningún cambio se rechazan
        mockMvc.perform(patch("/api/v1/admin/announcements/{id}", newer).with(user(admin)).contentType(json).content("""
                        {"pinned": true}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pinned").value(true));
        mockMvc.perform(get("/api/v1/announcements").with(user(driver1)))
                .andExpect(jsonPath("$.content[0].id").value(newer))
                .andExpect(jsonPath("$.content[1].id").value(pinned))
                .andExpect(jsonPath("$.content[2].id").value(older));
        mockMvc.perform(patch("/api/v1/admin/announcements/{id}", newer).with(user(admin)).contentType(json).content("""
                        {"title": "   "}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("El título no puede estar vacío"));
        mockMvc.perform(patch("/api/v1/admin/announcements/{id}", newer).with(user(admin)).contentType(json).content("{}"))
                .andExpect(status().isBadRequest());

        // Paginación del tablón
        mockMvc.perform(get("/api/v1/announcements").param("page", "1").param("size", "2").with(user(driver1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.totalPages").value(2));

        // Al borrar el aviso se borran sus lecturas
        mockMvc.perform(delete("/api/v1/admin/announcements/{id}", newer).with(user(driver1)))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/v1/admin/announcements/{id}", newer).with(user(admin)))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("select count(*) from announcement_reads", Long.class)).isZero();
        mockMvc.perform(get("/api/v1/admin/announcements/{id}/reads", newer).with(user(admin)))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/v1/admin/announcements/{id}", newer).with(user(admin)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/announcements").with(user(driver1)))
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void theBoardIsBuiltOutsideTheTransactionInPinnedThenNewestOrderWithoutNPlusOne() {
        User admin = saveUser("admin1", User.Role.ADMIN);
        User driver = saveUser("driver1", User.Role.DRIVER);
        Announcement one = announcementService.createAnnouncement(new AnnouncementRequest("Uno", "Texto uno", false), admin);
        announcementService.createAnnouncement(new AnnouncementRequest("Dos", "Texto dos", true), admin);
        announcementService.createAnnouncement(new AnnouncementRequest("Tres", "Texto tres", false), admin);
        announcementService.createAnnouncement(new AnnouncementRequest("Cuatro", "Texto cuatro", false), admin);
        Announcement five = announcementService.createAnnouncement(new AnnouncementRequest("Cinco", "Texto cinco", true), admin);
        announcementService.markAsRead(one.getId(), driver);
        announcementService.markAsRead(five.getId(), driver);

        Statistics statistics = statistics();
        statistics.clear();
        Page<BoardAnnouncementResponse> board = announcementService.getBoard(driver, 0, 20);

        // La página, el total y las lecturas de toda la página en una sola consulta: nada de una por aviso, y el
        // autor viene cargado (aquí ya no hay sesión de Hibernate abierta)
        assertThat(statistics.getPrepareStatementCount()).isLessThanOrEqualTo(3L);
        assertThat(board.getContent()).extracting(BoardAnnouncementResponse::title)
                .containsExactly("Cinco", "Dos", "Cuatro", "Tres", "Uno");
        assertThat(board.getContent()).extracting(BoardAnnouncementResponse::read)
                .containsExactly(true, false, false, false, true);
        assertThat(board.getContent()).allSatisfy(a -> assertThat(a.createdByUsername()).isEqualTo("admin1"));
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

    /** El parte tal como queda en la base de datos, con los datos que necesita la respuesta. */
    private InspectionResponse savedResponse(VehicleInspection inspection) {
        return InspectionResponse.from(inspectionRepository.findWithDetailsById(inspection.getId()).orElseThrow());
    }

    private Wave saveWave(String name, LocalTime startTime) {
        Wave wave = new Wave();
        wave.setName(name);
        wave.setStartTime(startTime);
        return waveRepository.save(wave);
    }

    private static ShiftAssignment shift(LocalDate day, Wave wave, User driver, Vehicle van, User createdBy) {
        ShiftAssignment shift = new ShiftAssignment();
        shift.setWorkDate(day);
        shift.setWave(wave);
        shift.setDriver(driver);
        shift.setVehicle(van);
        shift.setCreatedBy(createdBy);
        return shift;
    }

    private static String shiftBody(String day, long waveId, User driver, Vehicle van) {
        return """
                {"workDate": "%s", "waveId": %d, "driverId": %d, "vehicleId": %d}
                """.formatted(day, waveId, driver.getId(), van.getId());
    }

    /** El id de la respuesta JSON de un alta. */
    private ResultActions login(String username, String password) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\": \"" + username + "\", \"password\": \"" + password + "\"}"));
    }

    private static String field(ResultActions result, String name) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$." + name);
    }

    private static long idOf(ResultActions result) throws Exception {
        String body = result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        Number id = JsonPath.read(body, "$.id");
        return id.longValue();
    }

    private static MockMultipartFile odometerPhoto() {
        return new MockMultipartFile("odometerPhoto", "odometer.jpg", "image/jpeg", JPEG);
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
