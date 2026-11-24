package com.company.nopar.service;

import com.company.nopar.entity.ShiftAssignment;
import com.company.nopar.entity.User;
import com.company.nopar.entity.Wave;
import com.company.nopar.entity.WaveCall;
import com.company.nopar.entity.WaveCallRecipient;
import com.company.nopar.exception.InvalidStateException;
import com.company.nopar.exception.ResourceNotFoundException;
import com.company.nopar.repository.ShiftAssignmentRepository;
import com.company.nopar.repository.WaveCallRecipientRepository;
import com.company.nopar.repository.WaveCallRepository;
import com.company.nopar.repository.WaveRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Llamadas a una ola ("Ola 1, subid"). Un administrador o un encargado da la orden; se avisa por push a los
 * conductores que hoy tienen turno en esa ola y cada uno confirma con "Enterado". Quien llama ve en todo momento
 * quién ha confirmado y quién falta.
 *
 * Los destinatarios se fijan al llamar: un conductor que entra en la ola después no recibió el aviso, y se le puede
 * volver a llamar con otra llamada. Se puede llamar a la misma ola varias veces el mismo día (cada llamada tiene sus
 * propias confirmaciones).
 */
@Service
@RequiredArgsConstructor
public class WaveCallService {

    /** Lo que se ordena si quien llama no escribe otra cosa. */
    public static final String DEFAULT_MESSAGE = "Subid";

    private final WaveRepository waveRepository;
    private final ShiftAssignmentRepository shiftRepository;
    private final WaveCallRepository callRepository;
    private final WaveCallRecipientRepository recipientRepository;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * Llama a la ola para hoy y avisa por push a sus conductores cuando se confirma la transacción. Si hoy nadie tiene
     * turno en esa ola no hay a quién llamar: 409.
     */
    @Transactional
    public WaveCall callWave(Long waveId, String message, User caller) {
        Wave wave = waveRepository.findById(waveId)
                .orElseThrow(() -> new ResourceNotFoundException("Ola no encontrada con ID: " + waveId));
        LocalDate today = LocalDate.now();
        List<ShiftAssignment> shifts = shiftRepository.findByWorkDateAndWaveId(today, waveId);
        if (shifts.isEmpty()) {
            throw new InvalidStateException("Hoy no hay ningún conductor con turno en la ola " + wave.getName());
        }

        WaveCall call = new WaveCall();
        call.setWave(wave);
        call.setWorkDate(today);
        call.setMessage(message == null || message.isBlank() ? DEFAULT_MESSAGE : message.strip());
        call.setCalledBy(caller);
        for (ShiftAssignment shift : shifts) {
            WaveCallRecipient recipient = new WaveCallRecipient();
            recipient.setWaveCall(call);
            recipient.setDriver(shift.getDriver());
            recipient.setVehicleLicensePlate(shift.getVehicle().getLicensePlate());
            call.getRecipients().add(recipient);
        }
        WaveCall saved = callRepository.saveAndFlush(call);

        eventPublisher.publishEvent(new WaveCalledEvent(saved.getId(), wave.getId(), wave.getName(), today,
                saved.getMessage(), shifts.stream().map(shift -> shift.getDriver().getId()).toList()));
        return saved;
    }

    /** Una llamada con quién ha confirmado y quién falta. */
    @Transactional(readOnly = true)
    public WaveCall getCall(Long id) {
        return callRepository.findWithDetailsById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Llamada no encontrada con ID: " + id));
    }

    /** Las llamadas de un día, la más reciente primero. */
    @Transactional(readOnly = true)
    public List<WaveCall> getCalls(LocalDate date) {
        return callRepository.findWithDetailsByWorkDate(date);
    }

    /** Las llamadas de un día que recibió un conductor, con si ha confirmado cada una. */
    @Transactional(readOnly = true)
    public List<WaveCallRecipient> getCallsOfDriver(User driver, LocalDate date) {
        return recipientRepository.findOfDriverOnDate(driver.getId(), date);
    }

    /**
     * El conductor confirma que se ha enterado. Solo pueden hacerlo los conductores a los que se llamó (403 al resto,
     * igual que con un @PreAuthorize). Es idempotente: confirmar otra vez devuelve la confirmación de la primera vez.
     */
    @Transactional
    public WaveCallRecipient acknowledge(Long callId, User driver) {
        if (!callRepository.existsById(callId)) {
            throw new ResourceNotFoundException("Llamada no encontrada con ID: " + callId);
        }
        WaveCallRecipient recipient = recipientRepository.findOfDriver(callId, driver.getId())
                // AccessDeniedException llega a Spring Security, que responde 403 igual que con @PreAuthorize
                .orElseThrow(() -> new AccessDeniedException("Esta llamada no es para ti"));
        if (recipient.getAcknowledgedAt() == null) {
            // A microsegundos, la precisión de la columna: lo que se responde ahora es lo mismo que se leerá luego
            recipient.setAcknowledgedAt(LocalDateTime.now().truncatedTo(ChronoUnit.MICROS));
        }
        return recipient;
    }
}
