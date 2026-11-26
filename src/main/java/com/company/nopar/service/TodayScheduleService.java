package com.company.nopar.service;

import com.company.nopar.dto.ShiftCreateRequest;
import com.company.nopar.dto.ShiftUpdateRequest;
import com.company.nopar.entity.ShiftAssignment;
import com.company.nopar.entity.User;
import com.company.nopar.entity.Vehicle;
import com.company.nopar.entity.Wave;
import com.company.nopar.exception.InvalidStateException;
import com.company.nopar.exception.ResourceNotFoundException;
import com.company.nopar.repository.ShiftAssignmentRepository;
import com.company.nopar.repository.UserRepository;
import com.company.nopar.repository.VehicleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * La planificación del día de hoy para quien dirige la salida (un encargado o un administrador): ver quién sale en
 * cada ola y añadir, mover o quitar conductores. Es la misma planificación que monta el administrador con
 * /api/v1/admin/shifts y con las mismas reglas (un turno al día por conductor y por furgoneta, nada de furgonetas en
 * mantenimiento o dañadas, aviso por push al conductor), pero limitada a hoy: los otros días siguen siendo cosa del
 * administrador.
 */
@Service
@RequiredArgsConstructor
public class TodayScheduleService {

    private final ShiftService shiftService;
    private final ShiftAssignmentRepository shiftRepository;
    private final UserRepository userRepository;
    private final VehicleRepository vehicleRepository;

    /** Lo de hoy: las olas, sus turnos y los conductores y furgonetas que aún se pueden añadir. */
    public record TodaySchedule(
            LocalDate date,
            List<Wave> waves,
            List<ShiftAssignment> shifts,
            List<User> freeDrivers,
            List<Vehicle> freeVehicles
    ) {
    }

    @Transactional(readOnly = true)
    public TodaySchedule getToday() {
        LocalDate today = LocalDate.now();
        List<Wave> waves = shiftService.getWaves();
        List<ShiftAssignment> shifts = shiftRepository.findInRange(today, today);

        Set<Long> busyDrivers = new HashSet<>();
        Set<Long> busyVehicles = new HashSet<>();
        for (ShiftAssignment shift : shifts) {
            busyDrivers.add(shift.getDriver().getId());
            busyVehicles.add(shift.getVehicle().getId());
        }
        List<User> freeDrivers = userRepository
                .findByRole(User.Role.DRIVER, Sort.by(Sort.Order.asc("username").ignoreCase())).stream()
                .filter(User::isEnabled)
                .filter(driver -> !busyDrivers.contains(driver.getId()))
                .toList();
        List<Vehicle> freeVehicles = vehicleRepository.findAll(Sort.by("licensePlate")).stream()
                .filter(vehicle -> !busyVehicles.contains(vehicle.getId()))
                .filter(vehicle -> vehicle.getStatus() != Vehicle.VehicleStatus.MAINTENANCE
                        && vehicle.getStatus() != Vehicle.VehicleStatus.DAMAGED)
                .toList();
        return new TodaySchedule(today, waves, shifts, freeDrivers, freeVehicles);
    }

    /** Pone al conductor en la ola de hoy con esa furgoneta y le avisa por push. */
    @Transactional
    public ShiftAssignment addShift(Long waveId, Long driverId, Long vehicleId, User actor) {
        return shiftService.createShift(new ShiftCreateRequest(LocalDate.now(), waveId, driverId, vehicleId), actor, true);
    }

    /**
     * Pasa un turno de hoy a otra ola y/o le cambia la furgoneta; el conductor recibe el aviso del cambio. Sin ningún
     * cambio es un 400.
     */
    @Transactional
    public ShiftAssignment moveShift(Long id, Long waveId, Long vehicleId) {
        ensureToday(id);
        return shiftService.updateShift(id, new ShiftUpdateRequest(null, waveId, null, vehicleId), true);
    }

    /** Quita el turno de hoy y avisa al conductor de que ya no sale. */
    @Transactional
    public void removeShift(Long id) {
        ensureToday(id);
        shiftService.deleteShift(id, true);
    }

    /** Un encargado solo toca los turnos de hoy: los de otros días (ya pasados o por venir) son del administrador. */
    private void ensureToday(Long id) {
        if (!shiftRepository.existsById(id)) {
            throw new ResourceNotFoundException("Turno no encontrado con ID: " + id);
        }
        if (!shiftRepository.existsByIdAndWorkDate(id, LocalDate.now())) {
            throw new InvalidStateException("Solo se pueden cambiar los turnos de hoy");
        }
    }
}
