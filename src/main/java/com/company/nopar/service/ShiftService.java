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
import org.springframework.context.ApplicationEventPublisher;
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
 *
 * Al asignar, cambiar o quitar un turno se avisa por push al conductor afectado (al anterior también, si el turno
 * pasa a otro), salvo que el administrador pida hacerlo en silencio.
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
    private final ApplicationEventPublisher eventPublisher;

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

    /** Asigna el turno y avisa por push al conductor. */
    @Transactional
    public ShiftAssignment createShift(ShiftCreateRequest request, User actor) {
        return createShift(request, actor, true);
    }

    /** Con notifyDriver = false el turno se asigna sin avisar al conductor (por ejemplo, al montar la semana entera). */
    @Transactional
    public ShiftAssignment createShift(ShiftCreateRequest request, User actor, boolean notifyDriver) {
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
        ShiftAssignment saved = saveOrConflict(shift);
        if (notifyDriver) {
            eventPublisher.publishEvent(ShiftChangedEvent.of(ShiftChangedEvent.Change.ASSIGNED, saved));
        }
        return saved;
    }

    /**
     * Solo cambia los campos enviados. El resultado también tiene que respetar un turno por conductor y furgoneta al día.
     *
     * El estado de la furgoneta solo se comprueba si se cambia de furgoneta: un turno que ya tenía una furgoneta
     * que después pasó a mantenimiento se puede seguir editando (otra ola, otro día) mientras no se vuelva a elegirla.
     */
    @Transactional
    public ShiftAssignment updateShift(Long id, ShiftUpdateRequest request) {
        return updateShift(id, request, true);
    }

    /** Con notifyDriver = false el cambio se guarda sin avisar a nadie. */
    @Transactional
    public ShiftAssignment updateShift(Long id, ShiftUpdateRequest request, boolean notifyDriver) {
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

        // Qué avisar, calculado antes de tocar la entidad: si cambia el conductor, el anterior recibe la cancelación
        // (con los datos que tenía el turno) y el nuevo la asignación; si el conductor es el mismo y cambia el día, la
        // ola o la furgoneta, recibe la modificación; si no cambia nada, nadie recibe nada
        boolean sameDriver = driver.getId().equals(shift.getDriver().getId());
        boolean sameSlot = workDate.equals(shift.getWorkDate())
                && wave.getId().equals(shift.getWave().getId())
                && vehicle.getId().equals(shift.getVehicle().getId());
        ShiftChangedEvent cancelledForPreviousDriver = notifyDriver && !sameDriver
                ? ShiftChangedEvent.of(ShiftChangedEvent.Change.CANCELLED, shift)
                : null;

        shift.setWorkDate(workDate);
        shift.setWave(wave);
        shift.setDriver(driver);
        shift.setVehicle(vehicle);
        ShiftAssignment saved = saveOrConflict(shift);

        if (cancelledForPreviousDriver != null) {
            eventPublisher.publishEvent(cancelledForPreviousDriver);
            eventPublisher.publishEvent(ShiftChangedEvent.of(ShiftChangedEvent.Change.ASSIGNED, saved));
        } else if (notifyDriver && !sameSlot) {
            eventPublisher.publishEvent(ShiftChangedEvent.of(ShiftChangedEvent.Change.UPDATED, saved));
        }
        return saved;
    }

    /** Elimina el turno y avisa por push al conductor de que ya no sale. */
    @Transactional
    public void deleteShift(Long id) {
        deleteShift(id, true);
    }

    /** Con notifyDriver = false el turno se elimina sin avisar al conductor. */
    @Transactional
    public void deleteShift(Long id, boolean notifyDriver) {
        // Se carga con sus relaciones: el aviso necesita el día, la ola y la furgoneta que tenía
        ShiftAssignment shift = shiftRepository.findWithDetailsById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Turno no encontrado con ID: " + id));
        ShiftChangedEvent cancelled = ShiftChangedEvent.of(ShiftChangedEvent.Change.CANCELLED, shift);
        shiftRepository.delete(shift);
        if (notifyDriver) {
            eventPublisher.publishEvent(cancelled);
        }
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
