package com.company.nopar.service;

import com.company.nopar.dto.InspectionCreateRequest;
import com.company.nopar.dto.InspectionReviewRequest;
import com.company.nopar.entity.User;
import com.company.nopar.entity.Vehicle;
import com.company.nopar.entity.VehicleInspection;
import com.company.nopar.exception.InvalidFileException;
import com.company.nopar.exception.InvalidKilometersException;
import com.company.nopar.exception.InvalidStateException;
import com.company.nopar.exception.ResourceNotFoundException;
import com.company.nopar.repository.VehicleInspectionRepository;
import com.company.nopar.repository.VehicleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class InspectionService {

    // Orden fijo (lo más reciente primero; el id desempata): el cliente no elige por qué columna ordenar
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "inspectionDate")
            .and(Sort.by(Sort.Direction.DESC, "id"));

    private final VehicleInspectionRepository inspectionRepository;
    private final VehicleRepository vehicleRepository;
    private final FileStorageService fileStorageService;

    /**
     * Parte matutino completo: kilómetros, checklist, fotos y aceptaciones.
     *
     * Estado inicial: APPROVED si todo el checklist es correcto y no se reporta ningún daño;
     * en cualquier otro caso queda en SUBMITTED, pendiente de que un administrador lo revise.
     */
    @Transactional
    public VehicleInspection createInspection(
            InspectionCreateRequest request,
            MultipartFile odometerPhoto,
            MultipartFile beaconPhoto,
            MultipartFile exteriorPhoto,
            User driver
    ) {
        // 1. Verificar que el vehículo existe y que su cuentakilómetros no retrocede: un valor menor que el
        //    registrado es un error al teclear (o un intento de manipularlo). Si el dato guardado fuera el
        //    erróneo, un administrador lo corrige con PATCH /api/v1/fleet/vehicles/{id}/kilometers
        Vehicle vehicle = findVehicle(request.vehicleId());
        if (request.kilometers() < vehicle.getCurrentKilometers()) {
            throw InvalidKilometersException.lowerThanRegistered(request.kilometers(), vehicle.getCurrentKilometers());
        }

        // 2. La foto del cuentakilómetros es obligatoria (un fichero vacío cuenta como no enviado)
        if (odometerPhoto == null || odometerPhoto.isEmpty()) {
            throw new InvalidFileException("La foto del cuentakilómetros es obligatoria");
        }

        // 3. Guardar los adjuntos. Si algo falla después, o la transacción se revierte, se borran del disco
        List<String> storedFiles = new ArrayList<>();
        deleteStoredFilesUnlessCommitted(storedFiles);
        String odometerPath = store(odometerPhoto, storedFiles);
        String beaconPath = store(beaconPhoto, storedFiles);
        String exteriorPath = store(exteriorPhoto, storedFiles);

        // 4. Actualizar los kilómetros actuales del vehículo y marcarlo en uso. Un vehículo en MAINTENANCE
        //    o DAMAGED conserva su estado: ese cambio lo decide un administrador, no el parte de un conductor
        vehicle.setCurrentKilometers(request.kilometers());
        if (vehicle.getStatus() == Vehicle.VehicleStatus.AVAILABLE) {
            vehicle.setStatus(Vehicle.VehicleStatus.IN_USE);
        }
        vehicleRepository.save(vehicle);

        // 5. Crear y guardar la inspección (parte matutino)
        VehicleInspection inspection = new VehicleInspection();
        inspection.setVehicle(vehicle);
        inspection.setDriver(driver);
        inspection.setKilometers(request.kilometers());
        inspection.setTiresOk(request.tiresOk());
        inspection.setLightsOk(request.lightsOk());
        inspection.setBeaconV16Present(request.beaconV16Present());
        inspection.setOdometerPhotoPath(odometerPath);
        inspection.setBeaconPhotoPath(beaconPath);
        inspection.setExteriorPhotosPath(exteriorPath);
        inspection.setDamagesReported(request.damagesReported());
        inspection.setAcceptedExclusiveUse(request.acceptedExclusiveUse());
        inspection.setAcceptedLiability(request.acceptedLiability());
        inspection.setAcceptedCleanliness(request.acceptedCleanliness());
        inspection.setStatus(initialStatus(request));

        return inspectionRepository.save(inspection);
    }

    /**
     * Un administrador aprueba o rechaza una inspección pendiente (SUBMITTED).
     * Una inspección ya decidida no puede volver a revisarse.
     * Si se rechaza, el vehículo pasa a MAINTENANCE; si se aprueba, su estado no cambia.
     */
    @Transactional
    public VehicleInspection reviewInspection(Long inspectionId, InspectionReviewRequest.Decision decision, User reviewer) {
        VehicleInspection inspection = inspectionRepository.findWithDetailsById(inspectionId)
                .orElseThrow(() -> new ResourceNotFoundException("Inspección no encontrada con ID: " + inspectionId));

        if (inspection.getStatus() != VehicleInspection.InspectionStatus.SUBMITTED) {
            throw new InvalidStateException(
                    "La inspección " + inspectionId + " ya fue revisada (estado: " + inspection.getStatus() + ")");
        }

        boolean approved = decision == InspectionReviewRequest.Decision.APPROVED;
        inspection.setStatus(approved
                ? VehicleInspection.InspectionStatus.APPROVED
                : VehicleInspection.InspectionStatus.REJECTED);
        inspection.setReviewedBy(reviewer);
        inspection.setReviewedAt(LocalDateTime.now());

        if (!approved) {
            // Un parte rechazado deja el vehículo fuera de servicio hasta que se revise
            Vehicle vehicle = inspection.getVehicle();
            vehicle.setStatus(Vehicle.VehicleStatus.MAINTENANCE);
            vehicleRepository.save(vehicle);
        }

        return inspectionRepository.save(inspection);
    }

    @Transactional(readOnly = true)
    public Page<VehicleInspection> getAllInspections(int page, int size) {
        return inspectionRepository.findAll(newestFirst(page, size));
    }

    @Transactional(readOnly = true)
    public Page<VehicleInspection> getInspectionsByVehicle(Long vehicleId, int page, int size) {
        return inspectionRepository.findByVehicleId(vehicleId, newestFirst(page, size));
    }

    @Transactional(readOnly = true)
    public Page<VehicleInspection> getInspectionsByDriver(Long driverId, int page, int size) {
        return inspectionRepository.findByDriverId(driverId, newestFirst(page, size));
    }

    @Transactional(readOnly = true)
    public Page<VehicleInspection> getInspectionsByStatus(VehicleInspection.InspectionStatus status, int page, int size) {
        return inspectionRepository.findByStatus(status, newestFirst(page, size));
    }

    private static PageRequest newestFirst(int page, int size) {
        return PageRequest.of(page, size, NEWEST_FIRST);
    }

    private static VehicleInspection.InspectionStatus initialStatus(InspectionCreateRequest request) {
        boolean checklistPassed = Boolean.TRUE.equals(request.tiresOk())
                && Boolean.TRUE.equals(request.lightsOk())
                && Boolean.TRUE.equals(request.beaconV16Present());
        boolean noDamagesReported = request.damagesReported() == null || request.damagesReported().isBlank();

        return checklistPassed && noDamagesReported
                ? VehicleInspection.InspectionStatus.APPROVED
                : VehicleInspection.InspectionStatus.SUBMITTED;
    }

    private Vehicle findVehicle(Long vehicleId) {
        return vehicleRepository.findById(vehicleId)
                .orElseThrow(() -> new ResourceNotFoundException("Vehículo no encontrado con ID: " + vehicleId));
    }

    private String store(MultipartFile file, List<String> storedFiles) {
        String storedName = fileStorageService.storeFile(file);
        if (storedName != null) {
            storedFiles.add(storedName);
        }
        return storedName;
    }

    /** Los ficheros no forman parte de la transacción de BD: si esta no termina en commit, se borran a mano. */
    private void deleteStoredFilesUnlessCommitted(List<String> storedFiles) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    storedFiles.forEach(fileStorageService::delete);
                }
            }
        });
    }
}
