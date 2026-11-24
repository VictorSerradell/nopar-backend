package com.company.nopar.service;

import com.company.nopar.dto.ShiftCreateRequest;
import com.company.nopar.dto.ShiftUpdateRequest;
import com.company.nopar.dto.WaveRequest;
import com.company.nopar.entity.ShiftAssignment;
import com.company.nopar.entity.User;
import com.company.nopar.entity.Vehicle;
import com.company.nopar.entity.Wave;
import com.company.nopar.exception.DuplicateResourceException;
import com.company.nopar.exception.InvalidRequestException;
import com.company.nopar.exception.InvalidStateException;
import com.company.nopar.exception.ResourceInUseException;
import com.company.nopar.exception.ResourceNotFoundException;
import com.company.nopar.repository.ShiftAssignmentRepository;
import com.company.nopar.repository.UserRepository;
import com.company.nopar.repository.VehicleRepository;
import com.company.nopar.repository.WaveRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Cuadrantes: las olas (franjas de salida) y qué conductor sale en cada una, con qué furgoneta, cada día.
 * Un conductor y una furgoneta solo pueden estar en un turno al día, y una furgoneta en mantenimiento o dañada
 * no se puede asignar a ningún turno (409).
 */
@Service
@RequiredArgsConstructor
public class ShiftService {

    /** Máximo de días (contando el primero y el último) que se pueden pedir de una vez. */
    public static final int MAX_RANGE_DAYS = 31;

    private static final String DRIVER_OR_VEHICLE_BUSY = "El conductor o la furgoneta ya tienen un turno ese día";

    private final WaveRepository waveRepository;
    private final ShiftAssignmentRepository shiftRepository;
    private final UserRepository userRepository;
    private final VehicleRepository vehicleRepository;

    // ---------------------------------------------------------------- olas

    @Transactional
    public Wave createWave(WaveRequest request) {
        String name = request.name().strip();
        if (waveRepository.existsByNameIgnoreCase(name)) {
            throw new DuplicateResourceException("Ya existe una ola con el nombre: " + name);
        }
        Wave wave = new Wave();
        wave.setName(name);
        wave.setStartTime(request.startTime());
        try {
            // Flush inmediato: si dos peticiones crean la misma ola a la vez, la segunda choca con la restricción
            // única de la base de datos y se responde 409 igual que con la comprobación de arriba
            return waveRepository.saveAndFlush(wave);
        } catch (DataIntegrityViolationException e) {
            throw new DuplicateResourceException("Ya existe una ola con el nombre: " + name);
        }
    }

    @Transactional(readOnly = true)
    public List<Wave> getWaves() {
        return waveRepository.findAllByOrderByStartTimeAscNameAsc();
    }

    @Transactional
    public void deleteWave(Long id) {
        if (!waveRepository.existsById(id)) {
            throw new ResourceNotFoundException("Ola no encontrada con ID: " + id);
        }
        if (shiftRepository.existsByWaveId(id)) {
            throw new ResourceInUseException("No se puede eliminar la ola " + id + " porque tiene turnos asignados");
        }
        waveRepository.deleteById(id);
    }

    // ---------------------------------------------------------------- turnos

    @Transactional
    public ShiftAssignment createShift(ShiftCreateRequest request, User actor) {
        Wave wave = findWave(request.waveId());
        User driver = findDriver(request.driverId());
        Vehicle vehicle = findVehicle(request.vehicleId());
        ensureAssignable(vehicle);
        ensureFree(request.workDate(), driver.getId(), vehicle.getId(), null);

        ShiftAssignment shift = new ShiftAssignment();
        shift.setWorkDate(request.workDate());
        shift.setWave(wave);
        shift.setDriver(driver);
        shift.setVehicle(vehicle);
        shift.setCreatedBy(actor);
        return saveOrConflict(shift);
    }

    /**
     * Solo cambia los campos enviados. El resultado también tiene que respetar un turno por conductor y furgoneta al día.
     *
     * El estado de la furgoneta solo se comprueba si se cambia de furgoneta: un turno que ya tenía una furgoneta
     * que después pasó a mantenimiento se puede seguir editando (otra ola, otro día) mientras no se vuelva a elegirla.
     */
    @Transactional
    public ShiftAssignment updateShift(Long id, ShiftUpdateRequest request) {
        if (request.isEmpty()) {
            throw new InvalidRequestException("No se ha indicado ningún cambio");
        }
        ShiftAssignment shift = shiftRepository.findWithDetailsById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Turno no encontrado con ID: " + id));

        // Primero se calcula el resultado y se comprueba, y solo después se toca la entidad: con ella ya modificada,
        // las consultas de ensureFree provocarían un flush automático del UPDATE y la restricción única de la base
        // de datos saltaría (500) antes de poder responder el 409
        LocalDate workDate = request.workDate() != null ? request.workDate() : shift.getWorkDate();
        Wave wave = request.waveId() != null ? findWave(request.waveId()) : shift.getWave();
        User driver = request.driverId() != null ? findDriver(request.driverId()) : shift.getDriver();
        Vehicle vehicle = request.vehicleId() != null ? findVehicle(request.vehicleId()) : shift.getVehicle();
        if (!vehicle.getId().equals(shift.getVehicle().getId())) {
            ensureAssignable(vehicle);
        }
        ensureFree(workDate, driver.getId(), vehicle.getId(), id);

        shift.setWorkDate(workDate);
        shift.setWave(wave);
        shift.setDriver(driver);
        shift.setVehicle(vehicle);
        return saveOrConflict(shift);
    }

    @Transactional
    public void deleteShift(Long id) {
        if (!shiftRepository.existsById(id)) {
            throw new ResourceNotFoundException("Turno no encontrado con ID: " + id);
        }
        shiftRepository.deleteById(id);
    }

    /** El cuadrante completo de un rango de fechas: todos los turnos, como la tabla de olas que se comparte hoy. */
    @Transactional(readOnly = true)
    public List<ShiftAssignment> getShifts(LocalDate from, LocalDate to) {
        validateRange(from, to);
        return shiftRepository.findInRange(from, to);
    }

    /** Los turnos de un conductor en un rango de fechas. */
    @Transactional(readOnly = true)
    public List<ShiftAssignment> getShiftsOfDriver(User driver, LocalDate from, LocalDate to) {
        validateRange(from, to);
        return shiftRepository.findByDriverInRange(driver.getId(), from, to);
    }

    // ---------------------------------------------------------------- auxiliares

    private Wave findWave(Long id) {
        return waveRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Ola no encontrada con ID: " + id));
    }

    private Vehicle findVehicle(Long id) {
        return vehicleRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Vehículo no encontrado con ID: " + id));
    }

    /** El usuario tiene que existir y ser conductor: un administrador no sale a repartir. */
    private User findDriver(Long id) {
        User driver = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Usuario no encontrado con ID: " + id));
        if (driver.getRole() != User.Role.DRIVER) {
            throw new InvalidRequestException("El usuario " + driver.getUsername() + " no es un conductor");
        }
        return driver;
    }

    /** Una furgoneta en mantenimiento o dañada no sale a la ruta: hasta que un administrador la ponga en servicio no se le asignan turnos. */
    private void ensureAssignable(Vehicle vehicle) {
        String reason = switch (vehicle.getStatus()) {
            case MAINTENANCE -> "está en mantenimiento";
            case DAMAGED -> "está dañado";
            case AVAILABLE, IN_USE -> null;
        };
        if (reason != null) {
            throw new InvalidStateException("El vehículo " + vehicle.getLicensePlate() + " " + reason
                    + " y no se puede asignar a un turno hasta que un administrador cambie su estado");
        }
    }

    /** ignoredShiftId: al editar un turno, él mismo no cuenta como conflicto. */
    private void ensureFree(LocalDate workDate, Long driverId, Long vehicleId, Long ignoredShiftId) {
        boolean busy = ignoredShiftId == null
                ? shiftRepository.existsByWorkDateAndDriverId(workDate, driverId)
                    || shiftRepository.existsByWorkDateAndVehicleId(workDate, vehicleId)
                : shiftRepository.existsByWorkDateAndDriverIdAndIdNot(workDate, driverId, ignoredShiftId)
                    || shiftRepository.existsByWorkDateAndVehicleIdAndIdNot(workDate, vehicleId, ignoredShiftId);
        if (busy) {
            throw new DuplicateResourceException(DRIVER_OR_VEHICLE_BUSY);
        }
    }

    /** Las comprobaciones previas no cubren dos peticiones simultáneas: ahí decide la restricción única de la BD. */
    private ShiftAssignment saveOrConflict(ShiftAssignment shift) {
        try {
            return shiftRepository.saveAndFlush(shift);
        } catch (DataIntegrityViolationException e) {
            throw new DuplicateResourceException(DRIVER_OR_VEHICLE_BUSY);
        }
    }

    private void validateRange(LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            throw new InvalidRequestException("La fecha final no puede ser anterior a la inicial");
        }
        if (ChronoUnit.DAYS.between(from, to) >= MAX_RANGE_DAYS) {
            throw new InvalidRequestException("El rango no puede superar los " + MAX_RANGE_DAYS + " días");
        }
    }
}
