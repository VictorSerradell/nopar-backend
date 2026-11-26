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
import com.company.nopar.entity.DeviceToken;
import com.company.nopar.entity.RefreshToken;
import com.company.nopar.entity.ShiftAssignment;
import com.company.nopar.entity.User;
import com.company.nopar.entity.Vehicle;
import com.company.nopar.entity.VehicleInspection;
import com.company.nopar.entity.VehicleReturn;
import com.company.nopar.entity.Wave;
import com.company.nopar.entity.WaveMessage;
import com.company.nopar.exception.InvalidKilometersException;
import com.company.nopar.exception.InvalidRefreshTokenException;
import com.company.nopar.exception.InvalidStateException;
import com.company.nopar.exception.ResourceInUseException;
import com.company.nopar.push.PushSender;
import com.company.nopar.repository.AnnouncementReadRepository;
import com.company.nopar.repository.AnnouncementRepository;
import com.company.nopar.repository.DeviceTokenRepository;
import com.company.nopar.repository.RefreshTokenRepository;
import com.company.nopar.repository.ShiftAssignmentRepository;
import com.company.nopar.repository.UserRepository;
import com.company.nopar.repository.VehicleInspectionRepository;
import com.company.nopar.repository.VehicleRepository;
import com.company.nopar.repository.VehicleReturnRepository;
import com.company.nopar.repository.WaveCallRepository;
import com.company.nopar.repository.WaveMessageRepository;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
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
 * - que Flyway construye el esquema completo (de V1 a V12) sobre una base de datos vacía y que Hibernate
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
        // Ni unas credenciales de Firebase del .env local: el envio push se sustituye por un doble en estos tests
        "application.push.fcm.enabled=false",
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
    private WaveCallRepository waveCallRepository;
    @Autowired
    private WaveMessageRepository waveMessageRepository;
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
    private DeviceService deviceService;
    // El envio real a Firebase no se prueba aqui: se sustituye por un doble para ver a quien se le enviaria
    @MockitoBean
    private PushSender pushSender;
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
        // Los mensajes del chat apuntan a olas y usuarios
        waveMessageRepository.deleteAllInBatch();
        // Las llamadas de ola apuntan a olas y usuarios; sus destinatarios se borran con ellas (on delete cascade)
        waveCallRepository.deleteAllInBatch();
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

        assertThat(applied).containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12");
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
                "idx_announcement_reads_user",
                "idx_wave_calls_work_date",
                "idx_wave_call_recipients_driver",
                "idx_wave_messages_chat",
                "idx_wave_messages_date");
        assertThat(uniques).contains(
                "uk_waves_name",
                "uk_shift_assignments_driver_day",
                "uk_shift_assignments_vehicle_day",
                "uk_announcement_reads_announcement_user",
                "uk_device_tokens_token",
                "uk_wave_call_recipients_call_driver",
                "uq_wave_chat_reads_user_wave");
    }

    @Test
    void aChatMessageCannotBeEmptyInTheDatabase() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        User ana = saveUser("ana", User.Role.DRIVER);
        Wave wave = saveWave("Ola 1", LocalTime.of(9, 30));
        String insert = "insert into wave_messages (wave_id, work_date, sender_id, body, photo_file, sent_at) "
                + "values (?, current_date, ?, ?, ?, now())";

        assertThatThrownBy(() -> jdbc.update(insert, wave.getId(), ana.getId(), null, null))
                .isInstanceOf(DataIntegrityViolationException.class);
        // Con texto, con foto o con las dos cosas sí vale
        assertThat(jdbc.update(insert, wave.getId(), ana.getId(), "hola", null)).isEqualTo(1);
        assertThat(jdbc.update(insert, wave.getId(), ana.getId(), null, "abc.jpg")).isEqualTo(1);
        assertThat(jdbc.update(insert, wave.getId(), ana.getId(), "hola", "abc.jpg")).isEqualTo(1);
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

        assertThatThrownBy(() -> inspectionService.createInspection(rollback, odometerPhoto(), null, exteriorPhoto(), driver))
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
        String exteriorUrl = "/api/v1/inspections/" + report.getId() + "/photos/exterior";

        // El listado trae la URL de las fotos que existen (las mismas que atiende el controlador) y nada para la baliza
        mockMvc.perform(get("/api/v1/inspections/driver/me").with(user(author)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].odometerPhotoUrl").value(odometerUrl))
                .andExpect(jsonPath("$.content[0].beaconPhotoUrl").doesNotExist())
                .andExpect(jsonPath("$.content[0].exteriorPhotoUrl").value(exteriorUrl));

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
                // ...y lista los usuarios para elegir a quién
                .andExpect(jsonPath("$.paths['/api/v1/admin/users'].get").exists())
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
                // publicar un aviso en silencio (sin notificación push) es opcional
                .andExpect(jsonPath("$.components.schemas.AnnouncementRequest.properties.notifyDrivers").exists())
                .andExpect(jsonPath("$.components.schemas.PageResponseBoardAnnouncementResponse.properties.content.items").exists())
                // dispositivos para las notificaciones push: se registran con POST y se dan de baja con el token en la query
                .andExpect(jsonPath("$.paths['/api/v1/devices'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/devices'].delete.parameters[?(@.name == 'token')]").isNotEmpty())
                .andExpect(jsonPath("$.components.schemas.DeviceRegistrationRequest.properties.token").exists())
                .andExpect(jsonPath("$.components.schemas.DeviceRegistrationRequest.properties.platform").exists())
                // llamadas de ola: el encargado o el administrador llaman, el conductor confirma con "Enterado"
                .andExpect(jsonPath("$.paths['/api/v1/dispatch/waves/{waveId}/calls'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/dispatch/wave-calls'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/dispatch/wave-calls/{id}'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/wave-calls/me'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/wave-calls/{id}/acknowledge'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/admin/users/managers'].post").exists())
                .andExpect(jsonPath("$.components.schemas.WaveCallResponse.properties.recipients").exists())
                // repetir la llamada solo a los que faltan, y la planificación de hoy del encargado
                .andExpect(jsonPath("$.components.schemas.WaveCallResponse.properties.canRecall").exists())
                .andExpect(jsonPath("$.paths['/api/v1/dispatch/wave-calls/{id}/recall'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/dispatch/schedule'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/dispatch/shifts'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/dispatch/shifts/{id}'].patch").exists())
                .andExpect(jsonPath("$.paths['/api/v1/dispatch/shifts/{id}'].delete").exists())
                // chat de cada ola: leer desde un mensaje, escribir texto (JSON) o foto (multipart) y descargar la foto
                .andExpect(jsonPath("$.paths['/api/v1/waves/{waveId}/chat/messages'].get.parameters[?(@.name == 'after')]").isNotEmpty())
                .andExpect(jsonPath("$.paths['/api/v1/waves/{waveId}/chat/messages'].post.requestBody.content['application/json']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/waves/{waveId}/chat/photos'].post.requestBody.content['multipart/form-data']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/waves/{waveId}/chat/messages/{messageId}/photo'].get.responses['200'].content['image/jpeg']").exists())
                .andExpect(jsonPath("$.components.schemas.WaveMessageResponse.properties.photoUrl").exists())
                .andExpect(jsonPath("$.components.schemas.WaveMessageResponse.properties.senderRole").exists())
                // marcar el chat como leido y contar lo que queda sin leer
                .andExpect(jsonPath("$.paths['/api/v1/waves/{waveId}/chat/read'].post.responses['204']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/chat/unread'].get").exists())
                .andExpect(jsonPath("$.components.schemas.ChatUnreadResponse.properties.unread").exists())
                .andExpect(jsonPath("$.components.schemas.MyWaveCallResponse.properties.acknowledged").exists())
                .andExpect(jsonPath("$.components.schemas.MyWaveCallResponse.properties.canAcknowledge").exists())
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
        User driver = userService.createDriver("driver1", "clave-vieja-123");
        User other = userService.createDriver("driver2", "otra-clave-123");
        deviceService.registerDevice(driver, "tok-driver1-movil", DeviceToken.Platform.ANDROID);
        deviceService.registerDevice(driver, "tok-driver1-tablet", DeviceToken.Platform.ANDROID);
        deviceService.registerDevice(other, "tok-driver2", DeviceToken.Platform.IOS);

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
        assertThat(deviceTokenRepository.countByUserId(driver.getId())).isEqualTo(2);

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

        // Y los dispositivos push del usuario se dan de baja (la app vuelve a registrar el suyo); los de otro, no
        assertThat(deviceTokenRepository.countByUserId(driver.getId())).isZero();
        assertThat(deviceTokenRepository.countByUserId(other.getId())).isEqualTo(1);
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
    void anAdminResetsADriversOrAManagersPasswordWithoutTheCurrentOneAndNeverAnAdminsOverHttp() throws Exception {
        User driver = userService.createDriver("driver1", "clave-olvidada-1");
        User otherDriver = userService.createDriver("driver2", "otra-clave-123");
        User admin = saveUser("admin1", User.Role.ADMIN);

        // El conductor tiene sesión en dos dispositivos; el otro conductor, en uno, y no debe verse afectado
        login("driver1", "clave-olvidada-1").andExpect(status().isOk());
        login("driver1", "clave-olvidada-1").andExpect(status().isOk());
        login("driver2", "otra-clave-123").andExpect(status().isOk());
        assertThat(refreshTokenRepository.count()).isEqualTo(3);
        deviceService.registerDevice(driver, "tok-driver1-movil", DeviceToken.Platform.ANDROID);
        deviceService.registerDevice(driver, "tok-driver1-web", DeviceToken.Platform.WEB);
        deviceService.registerDevice(otherDriver, "tok-driver2", DeviceToken.Platform.IOS);

        String body = "{\"newPassword\": \"clave-nueva-456\"}";
        String path = "/api/v1/admin/users/" + driver.getId() + "/password";

        // Sin token: 401. Con el rol de conductor: 403. En ambos casos no cambia nada
        mockMvc.perform(patch(path).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(patch(path).with(user(otherDriver)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        assertThat(refreshTokenRepository.count()).isEqualTo(3);
        assertThat(deviceTokenRepository.count()).isEqualTo(3);

        // El administrador la restablece: 204 y se cierran las sesiones de ESE conductor, no las del otro
        mockMvc.perform(patch(path).with(user(admin)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNoContent());
        assertThat(refreshTokenRepository.count()).isEqualTo(1);
        // ...y sus dispositivos push, no los del otro conductor
        assertThat(deviceTokenRepository.countByUserId(driver.getId())).isZero();
        assertThat(deviceTokenRepository.countByUserId(otherDriver.getId())).isEqualTo(1);
        login("driver1", "clave-olvidada-1").andExpect(status().isUnauthorized());
        login("driver1", "clave-nueva-456").andExpect(status().isOk());
        login("driver2", "otra-clave-123").andExpect(status().isOk());

        // Un encargado que olvidó la suya: se restablece igual y se cierran sus sesiones
        User manager = userService.createManager("encargado1", "clave-olvidada-2");
        login("encargado1", "clave-olvidada-2").andExpect(status().isOk());
        mockMvc.perform(patch("/api/v1/admin/users/" + manager.getId() + "/password")
                        .with(user(admin)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNoContent());
        login("encargado1", "clave-olvidada-2").andExpect(status().isUnauthorized());
        login("encargado1", "clave-nueva-456").andExpect(status().isOk());
        // ...pero ni un encargado ni un conductor pueden restablecer la de otro
        mockMvc.perform(patch(path).with(user(manager)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());

        // Un administrador no es ni conductor ni encargado: su contraseña se cambia con la actual (PATCH /api/v1/auth/password)
        mockMvc.perform(patch("/api/v1/admin/users/" + admin.getId() + "/password")
                        .with(user(admin)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Solo se puede restablecer la contraseña de un conductor o de un encargado"));

        // Un usuario que no existe
        mockMvc.perform(patch("/api/v1/admin/users/999999/password")
                        .with(user(admin)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound());
    }

    @Test
    void onlyAnAdminListsTheUsersByNameWithoutPasswordsOverHttp() throws Exception {
        User admin = saveUser("admin1", User.Role.ADMIN);
        User zeta = userService.createDriver("Zeta", "clave-segura-123");
        User manager = userService.createManager("encargado1", "clave-segura-123");
        userService.createDriver("ana", "clave-segura-123");

        mockMvc.perform(get("/api/v1/admin/users"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/admin/users").with(user(zeta)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/admin/users").with(user(manager)))
                .andExpect(status().isForbidden());

        // Por nombre sin distinguir mayúsculas: "Zeta" va después de "ana", no antes
        mockMvc.perform(get("/api/v1/admin/users").with(user(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[0].username").value("admin1"))
                .andExpect(jsonPath("$[0].role").value("ADMIN"))
                .andExpect(jsonPath("$[1].username").value("ana"))
                .andExpect(jsonPath("$[1].role").value("DRIVER"))
                .andExpect(jsonPath("$[2].username").value("encargado1"))
                .andExpect(jsonPath("$[2].role").value("MANAGER"))
                .andExpect(jsonPath("$[3].username").value("Zeta"))
                .andExpect(jsonPath("$..password").isEmpty());
    }

    @Test
    void aManagerBuildsTodaysShiftsAndRecallsOnlyWhoIsMissingOverHttp() throws Exception {
        User admin = saveUser("admin1", User.Role.ADMIN);
        User manager = userService.createManager("encargado1", "clave-segura-123");
        User ana = saveUser("ana", User.Role.DRIVER);
        User bea = saveUser("bea", User.Role.DRIVER);
        User cris = saveUser("cris", User.Role.DRIVER);
        Wave wave1 = saveWave("Ola 1", LocalTime.of(9, 30));
        Wave wave2 = saveWave("Ola 2", LocalTime.of(10, 30));
        Vehicle van1 = saveVehicle("1111AAA", 1000);
        Vehicle van2 = saveVehicle("2222BBB", 2000);
        Vehicle van3 = saveVehicle("3333CCC", 3000);
        Vehicle workshop = saveVehicle("4444DDD", 4000);
        workshop.setStatus(Vehicle.VehicleStatus.MAINTENANCE);
        vehicleRepository.save(workshop);
        MediaType json = MediaType.APPLICATION_JSON;
        String shiftsUrl = "/api/v1/dispatch/shifts";
        String callsUrl = "/api/v1/dispatch/wave-calls";

        // Un conductor no ve ni toca la planificación
        mockMvc.perform(get("/api/v1/dispatch/schedule").with(user(ana))).andExpect(status().isForbidden());

        // Al empezar el día no hay turnos: todos los conductores y las furgonetas en servicio están libres
        mockMvc.perform(get("/api/v1/dispatch/schedule").with(user(manager)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.date").value(LocalDate.now().toString()))
                .andExpect(jsonPath("$.waves.length()").value(2))
                .andExpect(jsonPath("$.waves[0].name").value("Ola 1"))
                .andExpect(jsonPath("$.waves[0].shifts.length()").value(0))
                .andExpect(jsonPath("$.freeDrivers.length()").value(3))
                .andExpect(jsonPath("$.freeDrivers[0].username").value("ana"))
                // la furgoneta en mantenimiento no se puede asignar y no sale entre las libres
                .andExpect(jsonPath("$.freeVehicles.length()").value(3))
                .andExpect(jsonPath("$..password").isEmpty());

        // El encargado pone a Ana y a Bea en la Ola 1
        long anaShift = idOf(mockMvc.perform(post(shiftsUrl).with(user(manager)).contentType(json)
                        .content(shiftBody(wave1, ana, van1)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.workDate").value(LocalDate.now().toString()))
                .andExpect(jsonPath("$.waveName").value("Ola 1"))
                .andExpect(jsonPath("$.driverUsername").value("ana"))
                .andExpect(jsonPath("$.vehicleLicensePlate").value("1111AAA")));
        long beaShift = idOf(mockMvc.perform(post(shiftsUrl).with(user(manager)).contentType(json)
                        .content(shiftBody(wave1, bea, van2)))
                .andExpect(status().isCreated()));

        // Las mismas reglas que el administrador: sin repetir conductor ni furgoneta, ni furgonetas en el taller
        mockMvc.perform(post(shiftsUrl).with(user(manager)).contentType(json).content(shiftBody(wave2, ana, van3)))
                .andExpect(status().isConflict());
        mockMvc.perform(post(shiftsUrl).with(user(manager)).contentType(json).content(shiftBody(wave2, cris, van1)))
                .andExpect(status().isConflict());
        mockMvc.perform(post(shiftsUrl).with(user(manager)).contentType(json).content(shiftBody(wave2, cris, workshop)))
                .andExpect(status().isConflict());
        mockMvc.perform(post(shiftsUrl).with(user(manager)).contentType(json).content("{\"waveId\": " + wave2.getId() + "}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(shiftsUrl).with(user(ana)).contentType(json).content(shiftBody(wave2, cris, van3)))
                .andExpect(status().isForbidden());

        // La planificación refleja los turnos y quita de las libres a quien ya tiene uno
        mockMvc.perform(get("/api/v1/dispatch/schedule").with(user(manager)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.waves[0].shifts.length()").value(2))
                .andExpect(jsonPath("$.waves[0].shifts[0].driverUsername").value("ana"))
                .andExpect(jsonPath("$.waves[0].shifts[1].driverUsername").value("bea"))
                .andExpect(jsonPath("$.freeDrivers.length()").value(1))
                .andExpect(jsonPath("$.freeDrivers[0].username").value("cris"))
                .andExpect(jsonPath("$.freeVehicles.length()").value(1))
                .andExpect(jsonPath("$.freeVehicles[0].licensePlate").value("3333CCC"));

        // El encargado llama a la Ola 1 (Ana y Bea) y Cris entra después en la ola
        long callId = idOf(mockMvc.perform(post("/api/v1/dispatch/waves/{id}/calls", wave1.getId()).with(user(manager)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.recipientCount").value(2)));
        mockMvc.perform(post(shiftsUrl).with(user(manager)).contentType(json).content(shiftBody(wave1, cris, van3)))
                .andExpect(status().isCreated());

        // Faltan Ana y Bea (no han confirmado) y Cris (no recibió la llamada): «volver a llamar» llamaría a 3
        mockMvc.perform(get(callsUrl).with(user(manager)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(callId))
                .andExpect(jsonPath("$[0].recallCount").value(3))
                .andExpect(jsonPath("$[0].canRecall").value(true));

        // Ana confirma: faltan Bea y Cris
        mockMvc.perform(post("/api/v1/wave-calls/{id}/acknowledge", callId).with(user(ana)))
                .andExpect(status().isOk());
        mockMvc.perform(get(callsUrl).with(user(manager)))
                .andExpect(jsonPath("$[0].recallCount").value(2));

        // Un conductor no puede repetir la llamada; el encargado sí, y la nueva arrastra la confirmación de Ana
        mockMvc.perform(post(callsUrl + "/{id}/recall", callId).with(user(ana))).andExpect(status().isForbidden());
        long recallId = idOf(mockMvc.perform(post(callsUrl + "/{id}/recall", callId).with(user(manager)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value("Subid"))
                .andExpect(jsonPath("$.open").value(true))
                .andExpect(jsonPath("$.recipientCount").value(3))
                .andExpect(jsonPath("$.acknowledgedCount").value(1))
                .andExpect(jsonPath("$.recallCount").value(2))
                .andExpect(jsonPath("$.recipients[0].driverUsername").value("ana"))
                .andExpect(jsonPath("$.recipients[0].acknowledged").value(true))
                .andExpect(jsonPath("$.recipients[1].driverUsername").value("bea"))
                .andExpect(jsonPath("$.recipients[1].acknowledged").value(false))
                .andExpect(jsonPath("$.recipients[2].driverUsername").value("cris"))
                .andExpect(jsonPath("$.recipients[2].acknowledged").value(false)));

        // La original, que seguía vigente, queda cerrada y ya no se puede repetir (la última es la nueva)
        mockMvc.perform(get(callsUrl + "/{id}", callId).with(user(manager)))
                .andExpect(jsonPath("$.open").value(false))
                .andExpect(jsonPath("$.canRecall").value(false));
        mockMvc.perform(post(callsUrl + "/{id}/recall", callId).with(user(manager))).andExpect(status().isConflict());
        // Bea ya no puede confirmar la llamada cerrada, pero sí la nueva
        mockMvc.perform(post("/api/v1/wave-calls/{id}/acknowledge", callId).with(user(bea)))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/api/v1/wave-calls/{id}/acknowledge", recallId).with(user(bea))).andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/wave-calls/{id}/acknowledge", recallId).with(user(cris))).andExpect(status().isOk());

        // Ya nadie falta: no hay nada que repetir
        mockMvc.perform(get(callsUrl).with(user(manager)))
                .andExpect(jsonPath("$[0].id").value(recallId))
                .andExpect(jsonPath("$[0].recallCount").value(0))
                .andExpect(jsonPath("$[0].canRecall").value(false));
        mockMvc.perform(post(callsUrl + "/{id}/recall", recallId).with(user(manager))).andExpect(status().isConflict());

        // Mover y quitar turnos de hoy
        mockMvc.perform(patch(shiftsUrl + "/{id}", beaShift).with(user(manager)).contentType(json)
                        .content("{\"waveId\": " + wave2.getId() + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.waveName").value("Ola 2"));
        mockMvc.perform(patch(shiftsUrl + "/{id}", beaShift).with(user(manager)).contentType(json).content("{}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(delete(shiftsUrl + "/{id}", anaShift).with(user(manager))).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/dispatch/schedule").with(user(manager)))
                .andExpect(jsonPath("$.freeDrivers[0].username").value("ana"));
        mockMvc.perform(delete(shiftsUrl + "/{id}", 999999).with(user(manager))).andExpect(status().isNotFound());

        // Los turnos de otros días son del administrador: el encargado no los toca
        ShiftAssignment tomorrow = shiftAssignmentRepository.save(shift(LocalDate.now().plusDays(1), wave1, ana, van1, admin));
        mockMvc.perform(patch(shiftsUrl + "/{id}", tomorrow.getId()).with(user(manager)).contentType(json)
                        .content("{\"waveId\": " + wave2.getId() + "}"))
                .andExpect(status().isConflict());
        mockMvc.perform(delete(shiftsUrl + "/{id}", tomorrow.getId()).with(user(manager)))
                .andExpect(status().isConflict());
        assertThat(shiftAssignmentRepository.existsById(tomorrow.getId())).isTrue();
    }

    private static String shiftBody(Wave wave, User driver, Vehicle van) {
        return "{\"waveId\": " + wave.getId() + ", \"driverId\": " + driver.getId() + ", \"vehicleId\": " + van.getId() + "}";
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

        // Los encargados tambien: reciben los mensajes del chat de las olas
        User manager = saveUser("encargado1", User.Role.MANAGER);
        mockMvc.perform(post("/api/v1/devices").with(user(manager)).contentType(json).content("""
                        {"token": "tok-encargado", "platform": "ANDROID"}
                        """))
                .andExpect(status().isNoContent());
        assertThat(deviceTokenRepository.countByUserId(manager.getId())).isEqualTo(1);
        mockMvc.perform(delete("/api/v1/devices").param("token", "tok-encargado").with(user(manager)))
                .andExpect(status().isNoContent());
        assertThat(deviceTokenRepository.countByUserId(manager.getId())).isZero();
    }

    @Test
    void publishingAnAnnouncementNotifiesTheDriversInTheBackgroundAndDropsTheUnregisteredDevices() throws Exception {
        User admin = saveUser("admin1", User.Role.ADMIN);
        User ana = saveUser("ana", User.Role.DRIVER);
        User bea = saveUser("bea", User.Role.DRIVER);
        deviceService.registerDevice(ana, "tok-ana-movil", DeviceToken.Platform.ANDROID);
        deviceService.registerDevice(ana, "tok-ana-web", DeviceToken.Platform.WEB);
        deviceService.registerDevice(bea, "tok-bea", DeviceToken.Platform.IOS);
        deviceService.registerDevice(admin, "tok-admin", DeviceToken.Platform.WEB);
        MediaType json = MediaType.APPLICATION_JSON;
        // Firebase da por no registrado el movil de Ana (desinstalo la app)
        when(pushSender.send(argThat(notification -> "Lockers".equals(notification.title())), anyCollection()))
                .thenReturn(List.of("tok-ana-movil"));

        long id = idOf(mockMvc.perform(post("/api/v1/admin/announcements").with(user(admin)).contentType(json).content("""
                        {"title": "Lockers", "body": "Recordad cerrar la puerta", "pinned": true}
                        """))
                .andExpect(status().isCreated()));

        // Se envia en segundo plano, con el id del aviso para abrirlo, a los dispositivos de los conductores (no al del administrador)
        verify(pushSender, timeout(5000)).send(
                argThat(notification -> "Recordad cerrar la puerta".equals(notification.body())
                        && notification.data().equals(Map.of("type", "announcement", "announcementId", String.valueOf(id)))),
                argThat(tokens -> new HashSet<>(tokens).equals(Set.of("tok-ana-movil", "tok-ana-web", "tok-bea"))));
        // y el token que Firebase da por no registrado se borra
        eventually(() -> deviceTokenRepository.findAll().stream()
                .noneMatch(device -> "tok-ana-movil".equals(device.getToken())));
        assertThat(deviceTokenRepository.findAll()).extracting(DeviceToken::getToken)
                .containsExactlyInAnyOrder("tok-ana-web", "tok-bea", "tok-admin");

        // En silencio: el aviso se publica pero nadie recibe la notificacion
        mockMvc.perform(post("/api/v1/admin/announcements").with(user(admin)).contentType(json).content("""
                        {"title": "Silencioso", "body": "Sin notificacion", "notifyDrivers": false}
                        """))
                .andExpect(status().isCreated());
        verify(pushSender, after(500).never()).send(
                argThat(notification -> "Silencioso".equals(notification.title())), anyCollection());

        // Corregir un aviso no vuelve a notificar
        mockMvc.perform(patch("/api/v1/admin/announcements/{id}", id).with(user(admin)).contentType(json).content("""
                        {"title": "Lockers corregido"}
                        """))
                .andExpect(status().isOk());
        verify(pushSender, after(500).never()).send(
                argThat(notification -> "Lockers corregido".equals(notification.title())), anyCollection());
    }

    @Test
    void changingAShiftNotifiesTheDriversInvolvedAndNotifyDriverFalseKeepsItSilent() throws Exception {
        User admin = saveUser("admin1", User.Role.ADMIN);
        User ana = saveUser("ana", User.Role.DRIVER);
        User bea = saveUser("bea", User.Role.DRIVER);
        deviceService.registerDevice(ana, "tok-ana-movil", DeviceToken.Platform.ANDROID);
        deviceService.registerDevice(ana, "tok-ana-web", DeviceToken.Platform.WEB);
        deviceService.registerDevice(bea, "tok-bea", DeviceToken.Platform.IOS);
        Wave wave = saveWave("Ola 1", LocalTime.of(9, 30));
        Vehicle van1 = saveVehicle("1111AAA", 1000);
        Vehicle van2 = saveVehicle("2222BBB", 2000);
        MediaType json = MediaType.APPLICATION_JSON;
        Set<String> anaTokens = Set.of("tok-ana-movil", "tok-ana-web");

        // Asignar: lo recibe Ana, en todos sus dispositivos y no en los de Bea
        long shiftId = idOf(mockMvc.perform(post("/api/v1/admin/shifts").with(user(admin)).contentType(json)
                        .content(shiftBody("2026-11-25", wave.getId(), ana, van1)))
                .andExpect(status().isCreated()));
        verify(pushSender, timeout(5000)).send(
                argThat(notification -> "Nuevo turno".equals(notification.title())
                        && notification.data().equals(Map.of("type", "shift", "change", "assigned",
                                "workDate", "2026-11-25", "shiftId", String.valueOf(shiftId)))),
                argThat(tokens -> new HashSet<>(tokens).equals(anaTokens)));

        // Cambiarle la furgoneta: Ana recibe el turno modificado
        mockMvc.perform(patch("/api/v1/admin/shifts/{id}", shiftId).with(user(admin)).contentType(json)
                        .content("{\"vehicleId\": %d}".formatted(van2.getId())))
                .andExpect(status().isOk());
        verify(pushSender, timeout(5000)).send(
                argThat(notification -> "Turno modificado".equals(notification.title())
                        && notification.body().endsWith("furgoneta 2222BBB")),
                argThat(tokens -> new HashSet<>(tokens).equals(anaTokens)));

        // Pasarlo a Bea: Ana recibe la cancelacion y Bea la asignacion
        mockMvc.perform(patch("/api/v1/admin/shifts/{id}", shiftId).with(user(admin)).contentType(json)
                        .content("{\"driverId\": %d}".formatted(bea.getId())))
                .andExpect(status().isOk());
        verify(pushSender, timeout(5000)).send(
                argThat(notification -> "Turno cancelado".equals(notification.title())),
                argThat(tokens -> new HashSet<>(tokens).equals(anaTokens)));
        verify(pushSender, timeout(5000)).send(
                argThat(notification -> "Nuevo turno".equals(notification.title())),
                argThat(tokens -> new HashSet<>(tokens).equals(Set.of("tok-bea"))));

        // Eliminarlo: Bea recibe la cancelacion
        mockMvc.perform(delete("/api/v1/admin/shifts/{id}", shiftId).with(user(admin)))
                .andExpect(status().isNoContent());
        verify(pushSender, timeout(5000)).send(
                argThat(notification -> "Turno cancelado".equals(notification.title())),
                argThat(tokens -> new HashSet<>(tokens).equals(Set.of("tok-bea"))));

        // En silencio (notifyDriver=false): el turno se crea, se cambia y se elimina y nadie recibe nada
        long silentId = idOf(mockMvc.perform(post("/api/v1/admin/shifts").param("notifyDriver", "false")
                        .with(user(admin)).contentType(json)
                        .content(shiftBody("2026-11-26", wave.getId(), ana, van1)))
                .andExpect(status().isCreated()));
        mockMvc.perform(patch("/api/v1/admin/shifts/{id}", silentId).param("notifyDriver", "false")
                        .with(user(admin)).contentType(json).content("{\"workDate\": \"2026-11-27\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/v1/admin/shifts/{id}", silentId).param("notifyDriver", "false").with(user(admin)))
                .andExpect(status().isNoContent());
        verify(pushSender, after(500).never()).send(
                argThat(notification -> Set.of("2026-11-26", "2026-11-27").contains(notification.data().get("workDate"))),
                anyCollection());
    }

    @Test
    void theWaveChatIsOnlyForItsDriversTodayAndTheStaffAndItNotifiesTheOtherMembers() throws Exception {
        User admin = saveUser("admin1", User.Role.ADMIN);
        User manager = saveUser("encargado1", User.Role.MANAGER);
        User ana = saveUser("ana", User.Role.DRIVER);
        User bea = saveUser("bea", User.Role.DRIVER);
        User cris = saveUser("cris", User.Role.DRIVER);
        User dani = saveUser("dani", User.Role.DRIVER);
        for (User member : List.of(admin, manager, ana, bea, cris, dani)) {
            deviceService.registerDevice(member, "tok-" + member.getUsername(), DeviceToken.Platform.ANDROID);
        }
        Wave wave1 = saveWave("Ola 1", LocalTime.of(9, 30));
        Wave wave2 = saveWave("Ola 2", LocalTime.of(10, 30));
        LocalDate today = LocalDate.now();
        shiftAssignmentRepository.save(shift(today, wave1, ana, saveVehicle("1111AAA", 1000), admin));
        shiftAssignmentRepository.save(shift(today, wave1, bea, saveVehicle("2222BBB", 2000), admin));
        shiftAssignmentRepository.save(shift(today, wave2, cris, saveVehicle("3333CCC", 3000), admin));
        // Dani sale en la Ola 1, pero mañana: hoy no es del chat
        shiftAssignmentRepository.save(shift(today.plusDays(1), wave1, dani, saveVehicle("4444DDD", 4000), admin));
        MediaType json = MediaType.APPLICATION_JSON;
        String messages = "/api/v1/waves/{id}/chat/messages";
        String photos = "/api/v1/waves/{id}/chat/photos";
        long id1 = wave1.getId();
        long id2 = wave2.getId();

        // Quien hoy no trabaja en la ola no entra: ni lee ni escribe. Sin token, tampoco. Una ola que no existe, 404
        mockMvc.perform(get(messages, id1).with(user(cris))).andExpect(status().isForbidden());
        mockMvc.perform(get(messages, id1).with(user(dani))).andExpect(status().isForbidden());
        mockMvc.perform(post(messages, id1).with(user(cris)).contentType(json).content("{\"text\": \"hola\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(messages, id1)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(messages, id1 + 1000).with(user(ana))).andExpect(status().isNotFound());
        mockMvc.perform(get(messages, id1).with(user(ana)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        // Ana escribe (con espacios de más): queda recortado y firmado por ella
        long first = idOf(mockMvc.perform(post(messages, id1).with(user(ana)).contentType(json)
                        .content("{\"text\": \"  Llego en 5 minutos  \"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.text").value("Llego en 5 minutos"))
                .andExpect(jsonPath("$.senderUsername").value("ana"))
                .andExpect(jsonPath("$.senderRole").value("DRIVER"))
                .andExpect(jsonPath("$.waveId").value(id1))
                .andExpect(jsonPath("$.photoUrl").doesNotExist()));

        // Se avisa a los demás de la ola y al personal (Bea, el encargado y el administrador), no a ella ni a otras olas
        verify(pushSender, timeout(5000)).send(
                argThat(notification -> "Ola 1".equals(notification.title())
                        && "ana: Llego en 5 minutos".equals(notification.body())
                        && notification.data().equals(Map.of("type", "chat", "waveId", String.valueOf(id1),
                                "waveName", "Ola 1", "workDate", today.toString(), "messageId", String.valueOf(first)))),
                argThat(tokens -> new HashSet<>(tokens).equals(Set.of("tok-bea", "tok-encargado1", "tok-admin1"))));

        // Bea responde con una foto y un texto; el encargado, solo con una foto
        ResultActions photoMessage = mockMvc.perform(multipart(photos, id1)
                        .file(new MockMultipartFile("photo", "golpe.jpg", "image/jpeg", JPEG))
                        .file(new MockMultipartFile("data", "", MediaType.APPLICATION_JSON_VALUE,
                                "{\"text\": \"Mirad este golpe\"}".getBytes(StandardCharsets.UTF_8)))
                        .with(user(bea)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.text").value("Mirad este golpe"));
        long withPhoto = idOf(photoMessage);
        // La respuesta dice dónde descargar la foto: la misma ruta que atiende el controlador
        assertThat(field(photoMessage, "photoUrl"))
                .isEqualTo("/api/v1/waves/" + id1 + "/chat/messages/" + withPhoto + "/photo");
        long onlyPhoto = idOf(mockMvc.perform(multipart(photos, id1)
                        .file(new MockMultipartFile("photo", "otra.jpg", "image/jpeg", JPEG))
                        .with(user(manager)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.text").doesNotExist())
                .andExpect(jsonPath("$.photoUrl").exists()));
        verify(pushSender, timeout(5000)).send(
                argThat(notification -> "encargado1: 📷 Foto".equals(notification.body())),
                argThat(tokens -> new HashSet<>(tokens).equals(Set.of("tok-ana", "tok-bea", "tok-admin1"))));

        // Mensajes vacíos, demasiado largos o con un archivo que no es una imagen: 400 y no queda nada guardado
        mockMvc.perform(post(messages, id1).with(user(ana)).contentType(json).content("{\"text\": \"   \"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(messages, id1).with(user(ana)).contentType(json)
                        .content("{\"text\": \"" + "a".repeat(1001) + "\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(multipart(photos, id1)
                        .file(new MockMultipartFile("photo", "x.jpg", "image/jpeg", new byte[]{1, 2, 3}))
                        .with(user(ana)))
                .andExpect(status().isBadRequest());

        // Un mensaje de ayer en la misma ola no aparece en el chat de hoy
        WaveMessage yesterday = new WaveMessage();
        yesterday.setWaveId(id1);
        yesterday.setWorkDate(today.minusDays(1));
        yesterday.setSender(ana);
        yesterday.setBody("de ayer");
        waveMessageRepository.save(yesterday);

        // Ana lee el chat entero en orden de llegada, con el autor de cada mensaje sin consultas perezosas
        mockMvc.perform(get(messages, id1).with(user(ana)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].id").value(first))
                .andExpect(jsonPath("$[0].senderUsername").value("ana"))
                .andExpect(jsonPath("$[1].id").value(withPhoto))
                .andExpect(jsonPath("$[1].senderUsername").value("bea"))
                .andExpect(jsonPath("$[2].id").value(onlyPhoto))
                .andExpect(jsonPath("$[2].senderRole").value("MANAGER"));
        // ...y la app se pone al día pidiendo solo lo posterior al último que tiene
        mockMvc.perform(get(messages, id1).param("after", String.valueOf(withPhoto)).with(user(bea)))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(onlyPhoto));
        mockMvc.perform(get(messages, id1).param("after", String.valueOf(onlyPhoto)).with(user(bea)))
                .andExpect(jsonPath("$.length()").value(0));
        // Al abrir el chat con un límite llegan los últimos mensajes, no los primeros
        mockMvc.perform(get(messages, id1).param("limit", "2").with(user(ana)))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(withPhoto))
                .andExpect(jsonPath("$[1].id").value(onlyPhoto));

        // Cada ola tiene su chat: el de la Ola 2 está vacío para Cris, y el personal entra a cualquiera
        mockMvc.perform(get(messages, id2).with(user(cris)))
                .andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(get(messages, id2).with(user(ana))).andExpect(status().isForbidden());
        mockMvc.perform(get(messages, id1).with(user(manager))).andExpect(jsonPath("$.length()").value(3));
        mockMvc.perform(get(messages, id2).with(user(admin))).andExpect(jsonPath("$.length()").value(0));

        // La foto la ven los de la ola y el personal, con su tipo y caché privada; los demás no
        String photoUrl = "/api/v1/waves/{id}/chat/messages/{messageId}/photo";
        for (User allowed : List.of(ana, bea, manager, admin)) {
            mockMvc.perform(get(photoUrl, id1, withPhoto).with(user(allowed)))
                    .andExpect(status().isOk())
                    .andExpect(content().contentType(MediaType.IMAGE_JPEG))
                    .andExpect(content().bytes(JPEG))
                    .andExpect(header().string("Cache-Control", "max-age=3600, private"));
        }
        mockMvc.perform(get(photoUrl, id1, withPhoto).with(user(cris))).andExpect(status().isForbidden());
        mockMvc.perform(get(photoUrl, id1, withPhoto).with(user(dani))).andExpect(status().isForbidden());
        mockMvc.perform(get(photoUrl, id1, withPhoto)).andExpect(status().isUnauthorized());
        // Un mensaje sin foto, un mensaje de otra ola y uno que no existe: 404
        mockMvc.perform(get(photoUrl, id1, first).with(user(ana))).andExpect(status().isNotFound());
        mockMvc.perform(get(photoUrl, id2, withPhoto).with(user(cris))).andExpect(status().isNotFound());
        mockMvc.perform(get(photoUrl, id1, onlyPhoto + 1000).with(user(ana))).andExpect(status().isNotFound());

        // Nadie de otras olas recibió ningún aviso
        verify(pushSender, after(500).never()).send(
                any(), argThat(tokens -> tokens.contains("tok-cris") || tokens.contains("tok-dani")));
    }

    @Test
    void theUnreadMessagesCountOnlyTheOthersOfTodayAfterTheReadMarkerAndTheMarkerOnlyMovesForward() throws Exception {
        User admin = saveUser("admin1", User.Role.ADMIN);
        User manager = saveUser("encargado1", User.Role.MANAGER);
        User ana = saveUser("ana", User.Role.DRIVER);
        User bea = saveUser("bea", User.Role.DRIVER);
        User cris = saveUser("cris", User.Role.DRIVER);
        User dani = saveUser("dani", User.Role.DRIVER);
        Wave wave1 = saveWave("Ola 1", LocalTime.of(9, 30));
        Wave wave2 = saveWave("Ola 2", LocalTime.of(10, 30));
        Wave wave3 = saveWave("Ola 3", LocalTime.of(11, 30));
        LocalDate today = LocalDate.now();
        shiftAssignmentRepository.save(shift(today, wave1, ana, saveVehicle("1111AAA", 1000), admin));
        shiftAssignmentRepository.save(shift(today, wave1, bea, saveVehicle("2222BBB", 2000), admin));
        shiftAssignmentRepository.save(shift(today, wave2, cris, saveVehicle("3333CCC", 3000), admin));
        // Dani sale en la Ola 1, pero mañana: hoy no tiene chat
        shiftAssignmentRepository.save(shift(today.plusDays(1), wave1, dani, saveVehicle("4444DDD", 4000), admin));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        long id1 = wave1.getId();
        long id2 = wave2.getId();
        long id3 = wave3.getId();
        String read = "/api/v1/waves/{id}/chat/read";

        // Sin mensajes no hay nada sin leer, para nadie; sin token, 401
        for (User member : List.of(admin, manager, ana, bea, cris, dani)) {
            expectUnread(member);
        }
        mockMvc.perform(get("/api/v1/chat/unread")).andExpect(status().isUnauthorized());

        // Un mensaje de ayer no cuenta. Hoy: dos de Bea y uno del encargado en la Ola 1, y uno de Cris en la Ola 2
        saveChatMessage(wave1, today.minusDays(1), bea, "de ayer");
        long first = saveChatMessage(wave1, today, bea, "uno");
        long second = saveChatMessage(wave1, today, bea, "dos");
        long third = saveChatMessage(wave1, today, manager, "tres");
        saveChatMessage(wave2, today, cris, "cuatro");

        // Cada uno cuenta los de otros (los suyos no). Un conductor solo ve su ola; el personal, todas; Dani, nada
        expectUnread(ana, id1, 3);
        expectUnread(bea, id1, 1);
        expectUnread(cris);
        expectUnread(dani);
        expectUnread(manager, id1, 2, id2, 1);
        expectUnread(admin, id1, 3, id2, 1);

        // Ana ha leído hasta el segundo: solo le queda el del encargado
        markChatRead(ana, id1, second);
        expectUnread(ana, id1, 1);
        // Marcar uno anterior no hace retroceder el marcador
        markChatRead(ana, id1, first);
        expectUnread(ana, id1, 1);
        // Un id que aún no existe se recorta al último mensaje real: los futuros seguirán contando
        markChatRead(ana, id1, third + 1000);
        expectUnread(ana);
        assertThat(jdbc.queryForObject("select last_read_id from wave_chat_reads where user_id = ? and wave_id = ?",
                Long.class, ana.getId(), id1)).isEqualTo(third);
        // Tres marcas, una sola fila (la restricción única hace de upsert)
        assertThat(jdbc.queryForObject("select count(*) from wave_chat_reads where user_id = ? and wave_id = ?",
                Long.class, ana.getId(), id1)).isEqualTo(1L);

        // Llega otro mensaje: vuelve a contar, y a los demás también
        long fourth = saveChatMessage(wave1, today, bea, "cinco");
        expectUnread(ana, id1, 1);
        expectUnread(manager, id1, 3, id2, 1);
        expectUnread(admin, id1, 4, id2, 1);

        // El encargado lo lee todo en la Ola 1: le queda la Ola 2. Lo del administrador no cambia
        markChatRead(manager, id1, fourth);
        expectUnread(manager, id2, 1);
        expectUnread(admin, id1, 4, id2, 1);

        // Marcar una ola que hoy no tiene mensajes no deja ningún marcador
        markChatRead(manager, id3, 50);
        assertThat(jdbc.queryForObject("select count(*) from wave_chat_reads where user_id = ? and wave_id = ?",
                Long.class, manager.getId(), id3)).isEqualTo(0L);

        // Sin turno en la ola, sin token, con una ola que no existe o con un cuerpo inválido: no se marca nada
        MediaType json = MediaType.APPLICATION_JSON;
        mockMvc.perform(post(read, id1).with(user(cris)).contentType(json).content("{\"lastReadId\": 1}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(read, id1).with(user(dani)).contentType(json).content("{\"lastReadId\": 1}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(read, id1).contentType(json).content("{\"lastReadId\": 1}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(read, id1 + 1000).with(user(ana)).contentType(json).content("{\"lastReadId\": 1}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post(read, id1).with(user(ana)).contentType(json).content("{}"))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("select count(*) from wave_chat_reads where user_id in (?, ?)",
                Long.class, cris.getId(), dani.getId())).isEqualTo(0L);
    }

    @Test
    void callingAWaveNotifiesOnlyItsDriversTodayAndTheyConfirmWithEnterado() throws Exception {
        User admin = saveUser("admin1", User.Role.ADMIN);
        User manager = saveUser("encargado1", User.Role.MANAGER);
        User ana = saveUser("ana", User.Role.DRIVER);
        User bea = saveUser("bea", User.Role.DRIVER);
        User cris = saveUser("cris", User.Role.DRIVER);
        User dani = saveUser("dani", User.Role.DRIVER);
        deviceService.registerDevice(ana, "tok-ana", DeviceToken.Platform.ANDROID);
        deviceService.registerDevice(bea, "tok-bea", DeviceToken.Platform.ANDROID);
        deviceService.registerDevice(cris, "tok-cris", DeviceToken.Platform.ANDROID);
        deviceService.registerDevice(dani, "tok-dani", DeviceToken.Platform.ANDROID);
        Wave wave1 = saveWave("Ola 1", LocalTime.of(9, 30));
        Wave wave2 = saveWave("Ola 2", LocalTime.of(10, 30));
        Wave emptyWave = saveWave("Ola 3", LocalTime.of(11, 30));
        Vehicle van1 = saveVehicle("1111AAA", 1000);
        Vehicle van2 = saveVehicle("2222BBB", 2000);
        Vehicle van3 = saveVehicle("3333CCC", 3000);
        LocalDate today = LocalDate.now();
        shiftAssignmentRepository.save(shift(today, wave1, ana, van1, admin));
        shiftAssignmentRepository.save(shift(today, wave1, bea, van2, admin));
        shiftAssignmentRepository.save(shift(today, wave2, cris, van3, admin));
        // Dani sale en la misma ola, pero mañana: hoy no se le llama
        shiftAssignmentRepository.save(shift(today.plusDays(1), wave1, dani, van1, admin));
        MediaType json = MediaType.APPLICATION_JSON;
        String callUrl = "/api/v1/dispatch/waves/{id}/calls";

        // Un conductor no da la orden, y una ola sin nadie hoy no se puede llamar
        mockMvc.perform(post(callUrl, wave1.getId()).with(user(ana)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(callUrl, emptyWave.getId()).with(user(manager)))
                .andExpect(status().isConflict());

        // El encargado llama a la Ola 1 sin texto: "Subid"
        ResultActions created = mockMvc.perform(post(callUrl, wave1.getId()).with(user(manager)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.waveName").value("Ola 1"))
                .andExpect(jsonPath("$.message").value("Subid"))
                .andExpect(jsonPath("$.calledByUsername").value("encargado1"))
                .andExpect(jsonPath("$.open").value(true))
                .andExpect(jsonPath("$.expiresAt").exists())
                .andExpect(jsonPath("$.recipientCount").value(2))
                .andExpect(jsonPath("$.acknowledgedCount").value(0))
                .andExpect(jsonPath("$.recipients[0].driverUsername").value("ana"))
                .andExpect(jsonPath("$.recipients[0].vehicleLicensePlate").value("1111AAA"))
                .andExpect(jsonPath("$.recipients[1].driverUsername").value("bea"))
                .andExpect(jsonPath("$.recipients[1].vehicleLicensePlate").value("2222BBB"));
        long callId = idOf(created);

        // Se avisa en segundo plano solo a los dispositivos de Ana y Bea (ni Cris, de otra ola, ni Dani, de otro dia)
        verify(pushSender, timeout(5000)).send(
                argThat(notification -> "Ola 1: Subid".equals(notification.title())
                        && notification.data().equals(Map.of("type", "wave_call", "waveCallId", String.valueOf(callId),
                                "waveId", String.valueOf(wave1.getId()), "workDate", today.toString()))),
                argThat(tokens -> new HashSet<>(tokens).equals(Set.of("tok-ana", "tok-bea"))));
        verify(pushSender, after(500).never()).send(
                any(), argThat(tokens -> tokens.contains("tok-cris") || tokens.contains("tok-dani")));

        // Ana ve su llamada pendiente, con su furgoneta; Cris no tiene ninguna
        mockMvc.perform(get("/api/v1/wave-calls/me").with(user(ana)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(callId))
                .andExpect(jsonPath("$[0].vehicleLicensePlate").value("1111AAA"))
                .andExpect(jsonPath("$[0].acknowledged").value(false))
                .andExpect(jsonPath("$[0].canAcknowledge").value(true));
        mockMvc.perform(get("/api/v1/wave-calls/me").with(user(cris)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        // Cris no era destinataria: no puede confirmar. Ana si, y confirmar otra vez no cambia la hora
        mockMvc.perform(post("/api/v1/wave-calls/{id}/acknowledge", callId).with(user(cris)))
                .andExpect(status().isForbidden());
        String firstAck = field(mockMvc.perform(post("/api/v1/wave-calls/{id}/acknowledge", callId).with(user(ana)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acknowledged").value(true))
                .andExpect(jsonPath("$.canAcknowledge").value(false)), "acknowledgedAt");
        mockMvc.perform(post("/api/v1/wave-calls/{id}/acknowledge", callId).with(user(ana)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acknowledgedAt").value(firstAck));
        mockMvc.perform(post("/api/v1/wave-calls/{id}/acknowledge", callId + 1000).with(user(ana)))
                .andExpect(status().isNotFound());

        // El encargado y el administrador ven quien ha confirmado y quien falta
        for (User caller : List.of(manager, admin)) {
            mockMvc.perform(get("/api/v1/dispatch/wave-calls/{id}", callId).with(user(caller)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.recipientCount").value(2))
                    .andExpect(jsonPath("$.acknowledgedCount").value(1))
                    .andExpect(jsonPath("$.recipients[0].driverUsername").value("ana"))
                    .andExpect(jsonPath("$.recipients[0].acknowledged").value(true))
                    .andExpect(jsonPath("$.recipients[0].acknowledgedAt").value(firstAck))
                    .andExpect(jsonPath("$.recipients[1].driverUsername").value("bea"))
                    .andExpect(jsonPath("$.recipients[1].acknowledged").value(false));
        }

        // Pasada la ventana la orden caduca: Bea ya no puede confirmar y su llamada deja de ofrecer «Enterado»;
        // Ana, que confirmó a tiempo, sigue constando con su hora
        new JdbcTemplate(dataSource).update("update wave_calls set expires_at = ? where id = ?",
                Timestamp.valueOf(LocalDateTime.now().minusMinutes(1)), callId);
        mockMvc.perform(post("/api/v1/wave-calls/{id}/acknowledge", callId).with(user(bea)))
                .andExpect(status().isConflict());
        mockMvc.perform(get("/api/v1/wave-calls/me").with(user(bea)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].acknowledged").value(false))
                .andExpect(jsonPath("$[0].canAcknowledge").value(false));
        mockMvc.perform(post("/api/v1/wave-calls/{id}/acknowledge", callId).with(user(ana)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acknowledgedAt").value(firstAck));
        mockMvc.perform(get("/api/v1/dispatch/wave-calls/{id}", callId).with(user(manager)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.open").value(false))
                .andExpect(jsonPath("$.acknowledgedCount").value(1));

        // Una segunda llamada el mismo dia, con otro texto: es otra llamada, sin confirmar, y sale la primera
        mockMvc.perform(post(callUrl, wave1.getId()).with(user(admin)).contentType(json)
                        .content("{\"message\": \"Esperad en el muelle\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value("Esperad en el muelle"));
        mockMvc.perform(get("/api/v1/wave-calls/me").with(user(ana)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].message").value("Esperad en el muelle"))
                .andExpect(jsonPath("$[0].acknowledged").value(false))
                .andExpect(jsonPath("$[1].id").value(callId))
                .andExpect(jsonPath("$[1].acknowledged").value(true));
        mockMvc.perform(get("/api/v1/dispatch/wave-calls").with(user(manager)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].message").value("Esperad en el muelle"));

        // Solo un administrador crea encargados, y el nuevo puede iniciar la llamada pero no administrar
        mockMvc.perform(post("/api/v1/admin/users/managers").with(user(manager)).contentType(json)
                        .content("{\"username\": \"encargado2\", \"password\": \"password-segura-123\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/admin/users/managers").with(user(admin)).contentType(json)
                        .content("{\"username\": \"encargado2\", \"password\": \"password-segura-123\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("MANAGER"));
        mockMvc.perform(get("/api/v1/admin/waves").with(user(manager)))
                .andExpect(status().isForbidden());
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
        // Sin avisar a nadie (false): el push se envía en segundo plano y sus consultas contarían en las estadísticas
        shiftService.createShift(new ShiftCreateRequest(day, late.getId(), ana.getId(), van1.getId()), admin, false);
        shiftService.createShift(new ShiftCreateRequest(day, early.getId(), carlos.getId(), van2.getId()), admin, false);
        shiftService.createShift(new ShiftCreateRequest(day, early.getId(), bea.getId(), van3.getId()), admin, false);
        shiftService.createShift(new ShiftCreateRequest(day.plusDays(1), early.getId(), ana.getId(), van1.getId()), admin, false);

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
        // En silencio (notifyDrivers = false): el push va en segundo plano y sus consultas contarían en las estadísticas
        Announcement one = announcementService.createAnnouncement(new AnnouncementRequest("Uno", "Texto uno", false, false), admin);
        announcementService.createAnnouncement(new AnnouncementRequest("Dos", "Texto dos", true, false), admin);
        announcementService.createAnnouncement(new AnnouncementRequest("Tres", "Texto tres", false, false), admin);
        announcementService.createAnnouncement(new AnnouncementRequest("Cuatro", "Texto cuatro", false, false), admin);
        Announcement five = announcementService.createAnnouncement(new AnnouncementRequest("Cinco", "Texto cinco", true, false), admin);
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
        return inspectionService.createInspection(request, odometerPhoto(), null, exteriorPhoto(), driver);
    }

    /** Espera (hasta 5 segundos) a que algo que ocurre en segundo plano se cumpla. */
    private static void eventually(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("La condición no se cumplió en 5 segundos");
            }
            Thread.sleep(50);
        }
    }

    /** El parte tal como queda en la base de datos, con los datos que necesita la respuesta. */
    private InspectionResponse savedResponse(VehicleInspection inspection) {
        return InspectionResponse.from(inspectionRepository.findWithDetailsById(inspection.getId()).orElseThrow());
    }

    private long saveChatMessage(Wave wave, LocalDate day, User sender, String body) {
        WaveMessage message = new WaveMessage();
        message.setWaveId(wave.getId());
        message.setWorkDate(day);
        message.setSender(sender);
        message.setBody(body);
        return waveMessageRepository.save(message).getId();
    }

    /** Comprueba los mensajes sin leer que ve un usuario: pares (ola, cantidad) por orden de ola; sin pares, ninguno. */
    private void expectUnread(User who, long... waveAndCount) throws Exception {
        ResultActions result = mockMvc.perform(get("/api/v1/chat/unread").with(user(who)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(waveAndCount.length / 2));
        for (int i = 0; i < waveAndCount.length; i += 2) {
            result.andExpect(jsonPath("$[" + (i / 2) + "].waveId").value(waveAndCount[i]))
                    .andExpect(jsonPath("$[" + (i / 2) + "].unread").value(waveAndCount[i + 1]));
        }
    }

    private void markChatRead(User who, long waveId, long lastReadId) throws Exception {
        mockMvc.perform(post("/api/v1/waves/{id}/chat/read", waveId).with(user(who))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"lastReadId\": " + lastReadId + "}"))
                .andExpect(status().isNoContent());
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

    private static MockMultipartFile exteriorPhoto() {
        return new MockMultipartFile("exteriorPhoto", "exterior.jpg", "image/jpeg", JPEG);
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
