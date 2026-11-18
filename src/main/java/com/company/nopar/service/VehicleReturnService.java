package com.company.nopar.service;

import com.company.nopar.dto.VehicleReturnRequest;
import com.company.nopar.entity.User;
import com.company.nopar.entity.Vehicle;
import com.company.nopar.entity.VehicleReturn;
import com.company.nopar.exception.InvalidKilometersException;
import com.company.nopar.exception.InvalidStateException;
import com.company.nopar.exception.ResourceNotFoundException;
import com.company.nopar.repository.VehicleInspectionRepository;
import com.company.nopar.repository.VehicleRepository;
import com.company.nopar.repository.VehicleReturnRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

@Service
@RequiredArgsConstructor
public class VehicleReturnService {

    private final VehicleRepository vehicleRepository;
    private final VehicleInspectionRepository inspectionRepository;
    private final VehicleReturnRepository returnRepository;

    /**
     * Devolución del vehículo al acabar el turno: IN_USE pasa a AVAILABLE, o a MAINTENANCE si se reportan daños
     * (igual que cuando se rechaza un parte: queda fuera de servicio hasta que un administrador lo revise).
     *
     * Puede devolverlo quien hizo el último parte del vehículo, o un administrador (por si alguien olvidó hacerlo).
     * Los kilómetros finales no pueden ser menores que los registrados.
     */
    @Transactional
    public VehicleReturn returnVehicle(Long vehicleId, VehicleReturnRequest request, User actor) {
        // Fila bloqueada: dos devoluciones simultáneas (p. ej. un doble toque en el móvil) no pueden ganar las dos
        Vehicle vehicle = vehicleRepository.findByIdForUpdate(vehicleId)
                .orElseThrow(() -> new ResourceNotFoundException("Vehículo no encontrado con ID: " + vehicleId));

        requireHolderOrAdmin(vehicleId, actor);

        if (vehicle.getStatus() != Vehicle.VehicleStatus.IN_USE) {
            throw new InvalidStateException(
                    "El vehículo " + vehicleId + " no está en uso (estado: " + vehicle.getStatus() + ")");
        }
        if (request.kilometers() < vehicle.getCurrentKilometers()) {
            throw InvalidKilometersException.lowerThanRegistered(request.kilometers(), vehicle.getCurrentKilometers());
        }

        String damages = request.damagesReported() == null || request.damagesReported().isBlank()
                ? null
                : request.damagesReported().strip();

        vehicle.setCurrentKilometers(request.kilometers());
        vehicle.setStatus(damages == null ? Vehicle.VehicleStatus.AVAILABLE : Vehicle.VehicleStatus.MAINTENANCE);
        vehicleRepository.save(vehicle);

        VehicleReturn vehicleReturn = new VehicleReturn();
        vehicleReturn.setVehicle(vehicle);
        vehicleReturn.setReturnedBy(actor);
        vehicleReturn.setKilometers(request.kilometers());
        vehicleReturn.setDamagesReported(damages);
        return returnRepository.save(vehicleReturn);
    }

    private void requireHolderOrAdmin(Long vehicleId, User actor) {
        if (actor.getRole() == User.Role.ADMIN) {
            return;
        }
        boolean isHolder = inspectionRepository.findFirstByVehicleIdOrderByInspectionDateDescIdDesc(vehicleId)
                .map(lastReport -> Objects.equals(lastReport.getDriver().getId(), actor.getId()))
                .orElse(false);
        if (!isHolder) {
            // AccessDeniedException llega a Spring Security, que responde 403 igual que con @PreAuthorize
            throw new AccessDeniedException(
                    "Solo quien hizo el último parte del vehículo, o un administrador, puede devolverlo");
        }
    }
}
