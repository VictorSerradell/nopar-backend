package com.company.nopar.service;

import com.company.nopar.dto.InspectionCreateRequest;
import com.company.nopar.dto.InspectionReviewRequest.Decision;
import com.company.nopar.entity.InspectionPhotoType;
import com.company.nopar.entity.ShiftAssignment;
import com.company.nopar.entity.User;
import com.company.nopar.entity.Vehicle;
import com.company.nopar.entity.VehicleInspection;
import com.company.nopar.entity.VehicleInspection.InspectionStatus;
import com.company.nopar.exception.InvalidFileException;
import com.company.nopar.exception.InvalidKilometersException;
import com.company.nopar.exception.InvalidStateException;
import com.company.nopar.exception.ResourceNotFoundException;
import com.company.nopar.repository.ShiftAssignmentRepository;
import com.company.nopar.repository.VehicleInspectionRepository;
import com.company.nopar.repository.VehicleRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InspectionServiceTest {

    @Mock
    private VehicleInspectionRepository inspectionRepository;

    @Mock
    private VehicleRepository vehicleRepository;

    @Mock
    private ShiftAssignmentRepository shiftRepository;

    @Mock
    private FileStorageService fileStorageService;

    @InjectMocks
    private InspectionService inspectionService;

    private final MockMultipartFile odometer = photo("odometerPhoto");
    private final MockMultipartFile beacon = photo("beaconPhoto");
    private final MockMultipartFile exterior = photo("exteriorPhoto");
    private final User driver = new User();

    @BeforeEach
    void startTransactionSynchronization() {
        // el servicio es @Transactional: aqui simulamos el ciclo de vida de la transaccion a mano
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.initSynchronization();
        }
    }

    @AfterEach
    void clearTransactionSynchronization() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    // ------------------------------------------------------------ alta del parte

    @Test
    void givenCleanChecklistAndNoDamages_whenCreateInspection_thenApprovedAndChecklistStored() {
        Vehicle vehicle = vehicle();
        when(vehicleRepository.findById(5L)).thenReturn(Optional.of(vehicle));
        when(fileStorageService.storeFile(odometer)).thenReturn("odo.jpg");
        when(inspectionRepository.save(any(VehicleInspection.class))).thenAnswer(call -> call.getArgument(0));

        // daños en blanco: cuenta como "sin daños"
        VehicleInspection result = inspectionService.createInspection(
                request(5L, true, true, true, "   "), odometer, null, exterior, driver);

        assertThat(result.getStatus()).isEqualTo(InspectionStatus.APPROVED);
        assertThat(result.getTiresOk()).isTrue();
        assertThat(result.getLightsOk()).isTrue();
        assertThat(result.getBeaconV16Present()).isTrue();
        assertThat(vehicle.getStatus()).isEqualTo(Vehicle.VehicleStatus.IN_USE);
        assertThat(vehicle.getCurrentKilometers()).isEqualTo(20000);
    }

    @Test
    void givenDamagesReported_whenCreateInspection_thenSubmittedForReview() {
        when(vehicleRepository.findById(5L)).thenReturn(Optional.of(vehicle()));
        when(fileStorageService.storeFile(odometer)).thenReturn("odo.jpg");
        when(inspectionRepository.save(any(VehicleInspection.class))).thenAnswer(call -> call.getArgument(0));

        VehicleInspection result = inspectionService.createInspection(
                request(5L, true, true, true, "Rayón en la puerta"), odometer, null, exterior, driver);

        assertThat(result.getStatus()).isEqualTo(InspectionStatus.SUBMITTED);
        assertThat(result.getDamagesReported()).isEqualTo("Rayón en la puerta");
    }

    @Test
    void givenAFailedCheck_whenCreateInspection_thenSubmittedForReview() {
        when(vehicleRepository.findById(5L)).thenReturn(Optional.of(vehicle()));
        when(fileStorageService.storeFile(odometer)).thenReturn("odo.jpg");
        when(inspectionRepository.save(any(VehicleInspection.class))).thenAnswer(call -> call.getArgument(0));

        VehicleInspection result = inspectionService.createInspection(
                request(5L, true, false, true, null), odometer, null, exterior, driver);

        assertThat(result.getStatus()).isEqualTo(InspectionStatus.SUBMITTED);
        assertThat(result.getLightsOk()).isFalse();
    }

    @Test
    void givenValidRequest_whenCreateInspection_thenStoresPhotosAndKeepsFilesOnCommit() {
        Vehicle vehicle = vehicle();
        when(vehicleRepository.findById(5L)).thenReturn(Optional.of(vehicle));
        when(fileStorageService.storeFile(odometer)).thenReturn("odo.jpg");
        when(fileStorageService.storeFile(beacon)).thenReturn("beacon.jpg");
        when(fileStorageService.storeFile(exterior)).thenReturn("exterior.jpg");
        when(inspectionRepository.save(any(VehicleInspection.class))).thenAnswer(call -> call.getArgument(0));

        VehicleInspection result = inspectionService.createInspection(request(5L), odometer, beacon, exterior, driver);

        assertThat(result.getOdometerPhotoPath()).isEqualTo("odo.jpg");
        assertThat(result.getBeaconPhotoPath()).isEqualTo("beacon.jpg");
        assertThat(result.getExteriorPhotosPath()).isEqualTo("exterior.jpg");
        assertThat(result.getDriver()).isSameAs(driver);
        assertThat(result.getKilometers()).isEqualTo(20000);
        verify(vehicleRepository).save(vehicle);

        finishTransaction(TransactionSynchronization.STATUS_COMMITTED);
        verify(fileStorageService, never()).delete(any());
    }

    // ------------------------------------------------------------ enlace informativo con el turno del día

    @Test
    void givenDriverWithShiftOfTheDayAndTheAssignedVan_whenCreateInspection_thenLinkedAndMatching() {
        driver.setId(7L);
        stubPersistableInspection();
        when(shiftRepository.findByWorkDateAndDriverId(LocalDate.now(), 7L))
                .thenReturn(Optional.of(shiftWithVan(40L, 5L)));

        VehicleInspection result = inspectionService.createInspection(
                request(5L, true, true, true, null), odometer, null, exterior, driver);

        assertThat(result.getShiftId()).isEqualTo(40L);
        assertThat(result.getMatchesAssignedVehicle()).isTrue();
        assertThat(result.getStatus()).isEqualTo(InspectionStatus.APPROVED);
    }

    @Test
    void givenDriverWithShiftOfTheDayAndAnotherVan_whenCreateInspection_thenLinkedNotMatchingAndNeverRejected() {
        driver.setId(7L);
        stubPersistableInspection();
        when(shiftRepository.findByWorkDateAndDriverId(LocalDate.now(), 7L))
                .thenReturn(Optional.of(shiftWithVan(40L, 9L)));

        VehicleInspection result = inspectionService.createInspection(
                request(5L, true, true, true, null), odometer, null, exterior, driver);

        // el parte se acepta con normalidad: la discrepancia solo queda anotada para quien lo revise
        assertThat(result.getShiftId()).isEqualTo(40L);
        assertThat(result.getMatchesAssignedVehicle()).isFalse();
        assertThat(result.getStatus()).isEqualTo(InspectionStatus.APPROVED);
        verify(inspectionRepository).save(result);
    }

    @Test
    void givenDriverWithoutShiftOfTheDay_whenCreateInspection_thenNoLinkAndNeverRejected() {
        driver.setId(7L);
        stubPersistableInspection();
        when(shiftRepository.findByWorkDateAndDriverId(LocalDate.now(), 7L)).thenReturn(Optional.empty());

        VehicleInspection result = inspectionService.createInspection(
                request(5L, true, true, true, null), odometer, null, exterior, driver);

        assertThat(result.getShiftId()).isNull();
        assertThat(result.getMatchesAssignedVehicle()).isNull();
        assertThat(result.getStatus()).isEqualTo(InspectionStatus.APPROVED);
    }

    @Test
    void givenKilometersBelowTheRegisteredOnes_whenCreateInspection_thenInvalidKilometersAndNothingStored() {
        when(vehicleRepository.findById(5L)).thenReturn(Optional.of(vehicle())); // 15000 km registrados
        InspectionCreateRequest rollback =
                new InspectionCreateRequest(5L, 14999, true, true, true, null, true, true, true);

        assertThatThrownBy(() -> inspectionService.createInspection(rollback, odometer, null, exterior, driver))
                .isInstanceOf(InvalidKilometersException.class)
                .hasMessageContaining("14999")
                .hasMessageContaining("15000");

        verifyNoInteractions(fileStorageService);
        verify(vehicleRepository, never()).save(any());
        verify(inspectionRepository, never()).save(any());
    }

    @Test
    void givenSameKilometersAsRegistered_whenCreateInspection_thenAccepted() {
        when(vehicleRepository.findById(5L)).thenReturn(Optional.of(vehicle()));
        when(fileStorageService.storeFile(odometer)).thenReturn("odo.jpg");
        when(inspectionRepository.save(any(VehicleInspection.class))).thenAnswer(call -> call.getArgument(0));
        InspectionCreateRequest sameKilometers =
                new InspectionCreateRequest(5L, 15000, true, true, true, null, true, true, true);

        VehicleInspection result = inspectionService.createInspection(sameKilometers, odometer, null, exterior, driver);

        assertThat(result.getKilometers()).isEqualTo(15000);
    }

    @Test
    void givenDamagedVehicle_whenCreateInspection_thenKeepsItsStatusButUpdatesKilometers() {
        Vehicle vehicle = vehicle();
        vehicle.setStatus(Vehicle.VehicleStatus.DAMAGED);
        when(vehicleRepository.findById(5L)).thenReturn(Optional.of(vehicle));
        when(fileStorageService.storeFile(odometer)).thenReturn("odo.jpg");
        when(inspectionRepository.save(any(VehicleInspection.class))).thenAnswer(call -> call.getArgument(0));

        inspectionService.createInspection(request(5L), odometer, null, exterior, driver);

        assertThat(vehicle.getStatus()).isEqualTo(Vehicle.VehicleStatus.DAMAGED);
        assertThat(vehicle.getCurrentKilometers()).isEqualTo(20000);
        verify(vehicleRepository).save(vehicle);
    }

    @Test
    void givenVehicleInMaintenance_whenCreateInspection_thenInvalidStateAndNothingStoredNorChanged() {
        Vehicle vehicle = vehicle();
        vehicle.setStatus(Vehicle.VehicleStatus.MAINTENANCE);
        when(vehicleRepository.findById(5L)).thenReturn(Optional.of(vehicle));

        assertThatThrownBy(() -> inspectionService.createInspection(request(5L), odometer, beacon, exterior, driver))
                .isInstanceOf(InvalidStateException.class)
                .hasMessageContaining("mantenimiento");

        assertThat(vehicle.getStatus()).isEqualTo(Vehicle.VehicleStatus.MAINTENANCE);
        assertThat(vehicle.getCurrentKilometers()).isEqualTo(15000);
        verifyNoInteractions(fileStorageService);
        verify(vehicleRepository, never()).save(any());
        verify(inspectionRepository, never()).save(any());
    }

    @Test
    void givenUnknownVehicle_whenCreateInspection_thenNotFoundAndNothingStored() {
        when(vehicleRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> inspectionService.createInspection(request(99L), odometer, beacon, exterior, driver))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Vehículo no encontrado con ID: 99");

        verifyNoInteractions(fileStorageService);
        verify(inspectionRepository, never()).save(any());
    }

    @Test
    void givenEmptyOdometerPhoto_whenCreateInspection_thenInvalidFileAndNothingStored() {
        when(vehicleRepository.findById(5L)).thenReturn(Optional.of(vehicle()));
        MockMultipartFile emptyOdometer = new MockMultipartFile("odometerPhoto", "odo.jpg", "image/jpeg", new byte[0]);

        assertThatThrownBy(() -> inspectionService.createInspection(request(5L), emptyOdometer, beacon, exterior, driver))
                .isInstanceOf(InvalidFileException.class)
                .hasMessageContaining("cuentakilómetros");

        verifyNoInteractions(fileStorageService);
        verify(inspectionRepository, never()).save(any());
    }

    @Test
    void givenNoExteriorPhoto_whenCreateInspection_thenInvalidFileAndNothingStored() {
        when(vehicleRepository.findById(5L)).thenReturn(Optional.of(vehicle()));

        assertThatThrownBy(() -> inspectionService.createInspection(request(5L), odometer, beacon, null, driver))
                .isInstanceOf(InvalidFileException.class)
                .hasMessageContaining("exterior");

        verifyNoInteractions(fileStorageService);
        verify(inspectionRepository, never()).save(any());
    }

    @Test
    void givenEmptyExteriorPhoto_whenCreateInspection_thenInvalidFileAndNothingStored() {
        when(vehicleRepository.findById(5L)).thenReturn(Optional.of(vehicle()));
        MockMultipartFile emptyExterior = new MockMultipartFile("exteriorPhoto", "ext.jpg", "image/jpeg", new byte[0]);

        assertThatThrownBy(() -> inspectionService.createInspection(request(5L), odometer, beacon, emptyExterior, driver))
                .isInstanceOf(InvalidFileException.class)
                .hasMessageContaining("exterior");

        verifyNoInteractions(fileStorageService);
        verify(inspectionRepository, never()).save(any());
    }

    @Test
    void givenSecondPhotoIsRejected_whenCreateInspection_thenFirstPhotoIsDeletedOnRollback() {
        when(vehicleRepository.findById(5L)).thenReturn(Optional.of(vehicle()));
        when(fileStorageService.storeFile(odometer)).thenReturn("odo.jpg");
        when(fileStorageService.storeFile(beacon)).thenThrow(new InvalidFileException("Formato de archivo no permitido"));

        assertThatThrownBy(() -> inspectionService.createInspection(request(5L), odometer, beacon, exterior, driver))
                .isInstanceOf(InvalidFileException.class);

        finishTransaction(TransactionSynchronization.STATUS_ROLLED_BACK);
        verify(fileStorageService).delete("odo.jpg");
        verify(inspectionRepository, never()).save(any());
    }

    // ------------------------------------------------------------ revisión por un administrador

    @Test
    void givenSubmittedInspection_whenApprove_thenApprovedWithReviewerAndDate() {
        VehicleInspection inspection = inspection(1L, InspectionStatus.SUBMITTED);
        Vehicle vehicle = vehicle();
        vehicle.setStatus(Vehicle.VehicleStatus.IN_USE);
        inspection.setVehicle(vehicle);
        User admin = new User();
        when(inspectionRepository.findWithDetailsById(1L)).thenReturn(Optional.of(inspection));
        when(inspectionRepository.save(any(VehicleInspection.class))).thenAnswer(call -> call.getArgument(0));
        LocalDateTime before = LocalDateTime.now().minusSeconds(1);

        VehicleInspection result = inspectionService.reviewInspection(1L, Decision.APPROVED, admin);

        assertThat(result.getStatus()).isEqualTo(InspectionStatus.APPROVED);
        assertThat(result.getReviewedBy()).isSameAs(admin);
        assertThat(result.getReviewedAt()).isAfter(before);
        // aprobar no toca el vehículo
        assertThat(vehicle.getStatus()).isEqualTo(Vehicle.VehicleStatus.IN_USE);
        verify(vehicleRepository, never()).save(any());
    }

    @Test
    void givenSubmittedInspection_whenReject_thenRejectedAndVehicleGoesToMaintenance() {
        VehicleInspection inspection = inspection(1L, InspectionStatus.SUBMITTED);
        Vehicle vehicle = vehicle();
        vehicle.setStatus(Vehicle.VehicleStatus.IN_USE);
        inspection.setVehicle(vehicle);
        when(inspectionRepository.findWithDetailsById(1L)).thenReturn(Optional.of(inspection));
        when(inspectionRepository.save(any(VehicleInspection.class))).thenAnswer(call -> call.getArgument(0));

        VehicleInspection result = inspectionService.reviewInspection(1L, Decision.REJECTED, new User());

        assertThat(result.getStatus()).isEqualTo(InspectionStatus.REJECTED);
        assertThat(vehicle.getStatus()).isEqualTo(Vehicle.VehicleStatus.MAINTENANCE);
        verify(vehicleRepository).save(vehicle);
    }

    @Test
    void givenAlreadyReviewedInspection_whenReview_thenInvalidStateAndNothingSaved() {
        VehicleInspection inspection = inspection(1L, InspectionStatus.APPROVED);
        when(inspectionRepository.findWithDetailsById(1L)).thenReturn(Optional.of(inspection));

        assertThatThrownBy(() -> inspectionService.reviewInspection(1L, Decision.REJECTED, new User()))
                .isInstanceOf(InvalidStateException.class)
                .hasMessageContaining("ya fue revisada");

        assertThat(inspection.getStatus()).isEqualTo(InspectionStatus.APPROVED);
        verify(inspectionRepository, never()).save(any());
        verifyNoInteractions(vehicleRepository);
    }

    @Test
    void givenUnknownInspection_whenReview_thenNotFound() {
        when(inspectionRepository.findWithDetailsById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> inspectionService.reviewInspection(99L, Decision.APPROVED, new User()))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Inspección no encontrada con ID: 99");
    }

    // ------------------------------------------------------------ lecturas paginadas

    @Test
    void givenPageAndSize_whenGetAllInspections_thenNewestFirstOrderIsFixedByTheServer() {
        Page<VehicleInspection> page = new PageImpl<>(List.of(inspection(1L, InspectionStatus.SUBMITTED)));
        when(inspectionRepository.findAll(PageRequest.of(2, 10, newestFirst()))).thenReturn(page);

        assertThat(inspectionService.getAllInspections(2, 10)).isSameAs(page);
    }

    @Test
    void givenVehicle_whenGetInspectionsByVehicle_thenQueriesThatVehicleNewestFirst() {
        Page<VehicleInspection> page = new PageImpl<>(List.of());
        when(inspectionRepository.findByVehicleId(5L, PageRequest.of(0, 20, newestFirst()))).thenReturn(page);

        assertThat(inspectionService.getInspectionsByVehicle(5L, 0, 20)).isSameAs(page);
    }

    @Test
    void givenDriver_whenGetInspectionsByDriver_thenQueriesThatDriverNewestFirst() {
        Page<VehicleInspection> page = new PageImpl<>(List.of());
        when(inspectionRepository.findByDriverId(7L, PageRequest.of(1, 5, newestFirst()))).thenReturn(page);

        assertThat(inspectionService.getInspectionsByDriver(7L, 1, 5)).isSameAs(page);
    }

    @Test
    void givenStatus_whenGetInspectionsByStatus_thenQueriesThatStatusNewestFirst() {
        Page<VehicleInspection> page = new PageImpl<>(List.of());
        when(inspectionRepository.findByStatus(InspectionStatus.SUBMITTED, PageRequest.of(0, 50, newestFirst())))
                .thenReturn(page);

        assertThat(inspectionService.getInspectionsByStatus(InspectionStatus.SUBMITTED, 0, 50)).isSameAs(page);
    }

    // ------------------------------------------------------------ fotos del parte

    @Test
    void givenTheAuthor_whenGetPhoto_thenLoadsTheStoredFile() {
        User author = userWithRole(7L, User.Role.DRIVER);
        when(inspectionRepository.findWithDetailsById(1L)).thenReturn(Optional.of(inspectionWithOdometer(1L, author)));
        FileStorageService.StoredFile stored = storedJpeg();
        when(fileStorageService.load("odo.jpg")).thenReturn(stored);

        assertThat(inspectionService.getPhoto(1L, InspectionPhotoType.ODOMETER, author)).isSameAs(stored);
    }

    @Test
    void givenAnAdmin_whenGetPhotoOfAnotherDriver_thenLoadsTheStoredFile() {
        User author = userWithRole(7L, User.Role.DRIVER);
        User admin = userWithRole(1L, User.Role.ADMIN);
        when(inspectionRepository.findWithDetailsById(1L)).thenReturn(Optional.of(inspectionWithOdometer(1L, author)));
        FileStorageService.StoredFile stored = storedJpeg();
        when(fileStorageService.load("odo.jpg")).thenReturn(stored);

        assertThat(inspectionService.getPhoto(1L, InspectionPhotoType.ODOMETER, admin)).isSameAs(stored);
    }

    @Test
    void givenAnotherDriver_whenGetPhoto_thenAccessDeniedAndTheFileIsNotRead() {
        User author = userWithRole(7L, User.Role.DRIVER);
        User other = userWithRole(8L, User.Role.DRIVER);
        when(inspectionRepository.findWithDetailsById(1L)).thenReturn(Optional.of(inspectionWithOdometer(1L, author)));

        assertThatThrownBy(() -> inspectionService.getPhoto(1L, InspectionPhotoType.ODOMETER, other))
                .isInstanceOf(AccessDeniedException.class);

        verifyNoInteractions(fileStorageService);
    }

    @Test
    void givenUnknownInspection_whenGetPhoto_thenNotFound() {
        when(inspectionRepository.findWithDetailsById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> inspectionService.getPhoto(99L, InspectionPhotoType.ODOMETER, userWithRole(1L, User.Role.ADMIN)))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("99");

        verifyNoInteractions(fileStorageService);
    }

    @Test
    void givenInspectionWithoutThatPhoto_whenGetPhoto_thenNotFound() {
        User author = userWithRole(7L, User.Role.DRIVER);
        when(inspectionRepository.findWithDetailsById(1L)).thenReturn(Optional.of(inspectionWithOdometer(1L, author)));

        assertThatThrownBy(() -> inspectionService.getPhoto(1L, InspectionPhotoType.BEACON, author))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("beacon");

        verifyNoInteractions(fileStorageService);
    }

    // ------------------------------------------------------------ utilidades

    private static User userWithRole(Long id, User.Role role) {
        User user = new User();
        user.setId(id);
        user.setRole(role);
        return user;
    }

    private static VehicleInspection inspectionWithOdometer(Long id, User driver) {
        VehicleInspection inspection = inspection(id, InspectionStatus.APPROVED);
        inspection.setDriver(driver);
        inspection.setOdometerPhotoPath("odo.jpg");
        return inspection;
    }

    private static FileStorageService.StoredFile storedJpeg() {
        return new FileStorageService.StoredFile(new ByteArrayResource(new byte[]{1, 2, 3}), MediaType.IMAGE_JPEG);
    }

    private static Sort newestFirst() {
        return Sort.by(Sort.Direction.DESC, "inspectionDate").and(Sort.by(Sort.Direction.DESC, "id"));
    }

    private static void finishTransaction(int status) {
        TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCompletion(status));
    }

    private static InspectionCreateRequest request(Long vehicleId) {
        return request(vehicleId, true, true, true, "Rayón en la puerta");
    }

    private static InspectionCreateRequest request(
            Long vehicleId, boolean tiresOk, boolean lightsOk, boolean beaconV16Present, String damagesReported) {
        return new InspectionCreateRequest(
                vehicleId, 20000, tiresOk, lightsOk, beaconV16Present, damagesReported, true, true, true);
    }

    /** Lo mínimo para que createInspection llegue hasta el final con el vehículo 5 y la foto del cuentakilómetros. */
    private void stubPersistableInspection() {
        when(vehicleRepository.findById(5L)).thenReturn(Optional.of(vehicle()));
        when(fileStorageService.storeFile(odometer)).thenReturn("odo.jpg");
        when(inspectionRepository.save(any(VehicleInspection.class))).thenAnswer(call -> call.getArgument(0));
    }

    private static ShiftAssignment shiftWithVan(Long shiftId, Long vehicleId) {
        Vehicle assigned = new Vehicle();
        assigned.setId(vehicleId);
        ShiftAssignment shift = new ShiftAssignment();
        shift.setId(shiftId);
        shift.setVehicle(assigned);
        return shift;
    }

    private static VehicleInspection inspection(Long id, InspectionStatus status) {
        VehicleInspection inspection = new VehicleInspection();
        inspection.setId(id);
        inspection.setStatus(status);
        return inspection;
    }

    private static Vehicle vehicle() {
        Vehicle vehicle = new Vehicle();
        vehicle.setId(5L);
        vehicle.setLicensePlate("1234-ABC");
        vehicle.setCurrentKilometers(15000);
        return vehicle;
    }

    private static MockMultipartFile photo(String name) {
        return new MockMultipartFile(name, name + ".jpg", "image/jpeg", new byte[]{1, 2, 3});
    }
}
