package com.company.nopar.service;

import com.company.nopar.dto.InspectionCreateRequest;
import com.company.nopar.dto.InspectionReviewRequest.Decision;
import com.company.nopar.entity.User;
import com.company.nopar.entity.Vehicle;
import com.company.nopar.entity.VehicleInspection;
import com.company.nopar.entity.VehicleInspection.InspectionStatus;
import com.company.nopar.exception.InvalidFileException;
import com.company.nopar.exception.InvalidKilometersException;
import com.company.nopar.exception.InvalidStateException;
import com.company.nopar.exception.ResourceNotFoundException;
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
import org.springframework.data.domain.Sort;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
                request(5L, true, true, true, "   "), odometer, null, null, driver);

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
                request(5L, true, true, true, "Rayón en la puerta"), odometer, null, null, driver);

        assertThat(result.getStatus()).isEqualTo(InspectionStatus.SUBMITTED);
        assertThat(result.getDamagesReported()).isEqualTo("Rayón en la puerta");
    }

    @Test
    void givenAFailedCheck_whenCreateInspection_thenSubmittedForReview() {
        when(vehicleRepository.findById(5L)).thenReturn(Optional.of(vehicle()));
        when(fileStorageService.storeFile(odometer)).thenReturn("odo.jpg");
        when(inspectionRepository.save(any(VehicleInspection.class))).thenAnswer(call -> call.getArgument(0));

        VehicleInspection result = inspectionService.createInspection(
                request(5L, true, false, true, null), odometer, null, null, driver);

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

    @Test
    void givenKilometersBelowTheRegisteredOnes_whenCreateInspection_thenInvalidKilometersAndNothingStored() {
        when(vehicleRepository.findById(5L)).thenReturn(Optional.of(vehicle())); // 15000 km registrados
        InspectionCreateRequest rollback =
                new InspectionCreateRequest(5L, 14999, true, true, true, null, true, true, true);

        assertThatThrownBy(() -> inspectionService.createInspection(rollback, odometer, null, null, driver))
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

        VehicleInspection result = inspectionService.createInspection(sameKilometers, odometer, null, null, driver);

        assertThat(result.getKilometers()).isEqualTo(15000);
    }

    @Test
    void givenDamagedVehicle_whenCreateInspection_thenKeepsItsStatusButUpdatesKilometers() {
        Vehicle vehicle = vehicle();
        vehicle.setStatus(Vehicle.VehicleStatus.DAMAGED);
        when(vehicleRepository.findById(5L)).thenReturn(Optional.of(vehicle));
        when(fileStorageService.storeFile(odometer)).thenReturn("odo.jpg");
        when(inspectionRepository.save(any(VehicleInspection.class))).thenAnswer(call -> call.getArgument(0));

        inspectionService.createInspection(request(5L), odometer, null, null, driver);

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

    // ------------------------------------------------------------ utilidades

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
