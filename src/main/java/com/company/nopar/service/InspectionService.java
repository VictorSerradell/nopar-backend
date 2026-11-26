package com.company.nopar.service;

import com.company.nopar.dto.InspectionCreateRequest;
import com.company.nopar.dto.InspectionReviewRequest;
import com.company.nopar.entity.InspectionPhotoType;
import com.company.nopar.entity.User;
import com.company.nopar.entity.Vehicle;
import com.company.nopar.entity.VehicleInspection;
import com.company.nopar.exception.InvalidFileException;
import com.company.nopar.exception.InvalidKilometersException;
import com.company.nopar.exception.InvalidStateException;
import com.company.nopar.exception.ResourceNotFoundException;
import com.company.nopar.repository.ShiftAssignmentRepository;
import com.company.nopar.repository.VehicleInspectionRepository;
import com.company.nopar.repository.VehicleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class InspectionService {

    // Orden fijo (lo más reciente primero; el id desempata): el cliente no elige por qué columna ordenar
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "inspectionDate")
            .and(Sort.by(Sort.Direction.DESC, "id"));

    private final VehicleInspectionRepository inspectionRepository;
    private final VehicleRepository vehicleRepository;
    private final ShiftAssignmentRepository shiftRepository;
    private final FileStorageService fileStorageService;

    /**
     * Parte matutino completo: kilómetros, checklist, fotos y aceptaciones.
     *
     * Estado inicial: APPROVED si todo el checklist es correcto y no se reporta ningún daño;
     * en cualquier otro caso queda en SUBMITTED, pendiente de que un administrador lo revise.
     *
     * Un vehículo en MAINTENANCE no admite partes (409): hasta que un administrador lo ponga de nuevo
     * en servicio no puede salir a la ruta.
     *
     * Si el conductor tiene turno ese día, el parte lo enlaza y anota si la furgoneta es la asignada. Es solo
     * informativo: un parte de otra furgoneta, o de un conductor sin turno, se acepta igual.
     */
    @Transactional
    public VehicleInspection createInspection(
            InspectionCreateRequest request,
            MultipartFile odometerPhoto,
            MultipartFile beaconPhoto,
            MultipartFile exteriorPhoto,
            User driver
    ) {
        // 1. Verificar que el vehículo existe y está en servicio (en MAINTENANCE no se admiten partes) y que su
        //    cuentakilómetros no retrocede: un valor menor que el registrado es un error al teclear (o un intento
        //    de manipularlo). Si el dato guardado fuera el erróneo, un administrador lo corrige con
        //    PATCH /api/v1/fleet/vehicles/{id}/kilometers
        Vehicle vehicle = findVehicle(request.vehicleId());
        if (vehicle.getStatus() == Vehicle.VehicleStatus.MAINTENANCE) {
            throw new InvalidStateException("El vehículo " + vehicle.getLicensePlate()
                    + " está en mantenimiento y no admite partes hasta que un administrador cambie su estado");
        }
        if (request.kilometers() < vehicle.getCurrentKilometers()) {
            throw InvalidKilometersException.lowerThanRegistered(request.kilometers(), vehicle.getCurrentKilometers());
        }

        // 2. Las fotos del cuentakilómetros y del exterior son obligatorias (un fichero vacío cuenta como no enviado)
        if (odometerPhoto == null || odometerPhoto.isEmpty()) {
            throw new InvalidFileException("La foto del cuentakilómetros es obligatoria");
        }
        if (exteriorPhoto == null || exteriorPhoto.isEmpty()) {
            throw new InvalidFileException("La foto del exterior de la furgoneta es obligatoria");
        }

        // 3. Guardar los adjuntos. Si algo falla después, o la transacción se revierte, se borran del disco
        List<String> storedFiles = new ArrayList<>();
        deleteStoredFilesUnlessCommitted(storedFiles);
        String odometerPath = store(odometerPhoto, storedFiles);
        String beaconPath = store(beaconPhoto, storedFiles);
        String exteriorPath = store(exteriorPhoto, storedFiles);

        // 4. Actualizar los kilómetros actuales del vehículo y marcarlo en uso. Un vehículo DAMAGED conserva
        //    su estado: ese cambio lo decide un administrador, no el parte de un conductor
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
        linkToShiftOfTheDay(inspection, driver, vehicle);

        return inspectionRepository.save(inspection);
    }

    /**
     * Enlace informativo con el cuadrante: si el conductor tenía turno el día del parte, guarda cuál era y si la
     * furgoneta del parte es la asignada en él. No bloquea nada; sin turno el parte queda sin enlace.
     */
    private void linkToShiftOfTheDay(VehicleInspection inspection, User driver, Vehicle vehicle) {
        shiftRepository.findByWorkDateAndDriverId(inspection.getInspectionDate().toLocalDate(), driver.getId())
                .ifPresent(shift -> {
                    inspection.setShiftId(shift.getId());
                    inspection.setMatchesAssignedVehicle(shift.getVehicle().getId().equals(vehicle.getId()));
                });
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

    /**
     * Una de las fotos de un parte. La ve un administrador o el conductor que hizo el parte; cualquier otro
     * usuario recibe 403 (AccessDeniedException llega a Spring Security, igual que con @PreAuthorize).
     * 404 si el parte no existe o no tiene esa foto, o si el fichero ya no está en el disco.
     */
    @Transactional(readOnly = true)
    public FileStorageService.StoredFile getPhoto(Long inspectionId, InspectionPhotoType type, User actor) {
        VehicleInspection inspection = inspectionRepository.findWithDetailsById(inspectionId)
                .orElseThrow(() -> new ResourceNotFoundException("Inspección no encontrada con ID: " + inspectionId));

        requireAuthorOrAdmin(inspection, actor);

        String storedName = type.storedNameOf(inspection);
        if (storedName == null) {
            throw new ResourceNotFoundException(
                    "La inspección " + inspectionId + " no tiene foto de tipo " + type.slug());
        }
        return fileStorageService.load(storedName);
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

    private static void requireAuthorOrAdmin(VehicleInspection inspection, User actor) {
        if (actor.getRole() == User.Role.ADMIN) {
            return;
        }
        User author = inspection.getDriver();
        if (author == null || !Objects.equals(author.getId(), actor.getId())) {
            throw new AccessDeniedException(
                    "Solo el conductor que hizo el parte, o un administrador, puede ver sus fotos");
        }
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

    /**
     * Retención de las fotos: quita las de los partes anteriores a {@code cutoff} y borra sus ficheros del disco. El
     * parte (kilómetros, checklist, daños anotados, revisión) se conserva; solo desaparecen las imágenes, y la API
     * devuelve null en sus *PhotoUrl y 404 si se piden.
     *
     * Los ficheros se borran cuando la transacción termina en commit: si fallara, las filas conservarían sus fotos y
     * no quedarían referencias rotas. Devuelve cuántos partes se han quedado sin fotos.
     */
    @Transactional
    public int deletePhotosBefore(LocalDateTime cutoff) {
        List<String> files = new ArrayList<>();
        files.addAll(inspectionRepository.findOdometerPhotoFilesBefore(cutoff));
        files.addAll(inspectionRepository.findBeaconPhotoFilesBefore(cutoff));
        files.addAll(inspectionRepository.findExteriorPhotoFilesBefore(cutoff));
        int cleared = inspectionRepository.clearPhotosBefore(cutoff);
        deleteFilesAfterCommit(files);
        return cleared;
    }

    private void deleteFilesAfterCommit(List<String> files) {
        if (files.isEmpty()) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    files.forEach(fileStorageService::delete);
                }
            });
        } else {
            files.forEach(fileStorageService::delete);
        }
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
