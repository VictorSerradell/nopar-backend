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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Llamadas a una ola ("Ola 1, subid"). Un administrador o un encargado da la orden; se avisa por push a los
 * conductores que hoy tienen turno en esa ola y cada uno confirma con "Enterado". Quien llama ve en todo momento
 * quién ha confirmado y quién falta.
 *
 * Los destinatarios se fijan al llamar: un conductor que entra en la ola después no recibió el aviso. Se puede llamar
 * a la misma ola varias veces el mismo día (cada llamada tiene sus propias confirmaciones), y la última llamada de una
 * ola se puede repetir solo para los que faltan (recall): quienes no confirmaron y quienes entraron en la ola después.
 * La repetición es una llamada completa que arrastra las confirmaciones ya hechas, para que siga siendo «N de M
 * enterados» y quien ya confirmó no vuelva a figurar como pendiente.
 *
 * "Enterado" solo se puede pulsar mientras la orden está vigente: desde que se da hasta que pasa la ventana de
 * confirmación (application.wave-calls.acknowledge-window-minutes, 60 por defecto). Pasada, el conductor ya no puede
 * confirmar y la llamada queda con quién llegó a tiempo y quién no.
 */
@Service
@RequiredArgsConstructor
public class WaveCallService {

    /** Lo que se ordena si quien llama no escribe otra cosa. */
    public static final String DEFAULT_MESSAGE = "Subid";

    /** Cuánto dura la orden si no se configura otra cosa. */
    static final Duration DEFAULT_ACKNOWLEDGE_WINDOW = Duration.ofHours(1);

    private final WaveRepository waveRepository;
    private final ShiftAssignmentRepository shiftRepository;
    private final WaveCallRepository callRepository;
    private final WaveCallRecipientRepository recipientRepository;
    private final ApplicationEventPublisher eventPublisher;

    private Duration acknowledgeWindow = DEFAULT_ACKNOWLEDGE_WINDOW;

    /** Spring lo rellena desde application.yaml; los tests lo cambian directamente. */
    @Value("${application.wave-calls.acknowledge-window-minutes:60}")
    void setAcknowledgeWindowMinutes(long minutes) {
        if (minutes <= 0) {
            throw new IllegalArgumentException(
                    "application.wave-calls.acknowledge-window-minutes debe ser mayor que 0 y es " + minutes);
        }
        this.acknowledgeWindow = Duration.ofMinutes(minutes);
    }

    /**
     * Llama a la ola para hoy y avisa por push a sus conductores cuando se confirma la transacción. Si hoy nadie tiene
     * turno en esa ola no hay a quién llamar: 409.
     */
    @Transactional
    public WaveCall callWave(Long waveId, String message, User caller) {
        Wave wave = waveRepository.findById(waveId)
                .orElseThrow(() -> new ResourceNotFoundException("Ola no encontrada con ID: " + waveId));
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        LocalDate today = now.toLocalDate();
        List<ShiftAssignment> shifts = shiftRepository.findByWorkDateAndWaveId(today, waveId);
        if (shifts.isEmpty()) {
            throw new InvalidStateException("Hoy no hay ningún conductor con turno en la ola " + wave.getName());
        }

        String text = message == null || message.isBlank() ? DEFAULT_MESSAGE : message.strip();
        return createCall(wave, today, text, caller, now, shifts, Map.of());
    }

    /**
     * Repite la orden solo para los que faltan: los conductores que hoy tienen turno en la ola y no han confirmado esta
     * llamada, tanto quienes no contestaron como quienes entraron en la ola después de llamarla. Va con el mismo
     * mensaje y una ventana nueva, y avisa por push solo a esos conductores.
     *
     * La llamada nueva incluye a todos los que hoy tienen turno en la ola, y a los que ya habían confirmado la original
     * se les copia su confirmación (sin avisarles otra vez): así la nueva llamada cuenta «N de M enterados» completa.
     *
     * Solo vale para la última llamada de la ola de hoy (la anterior ya la sustituyó otra). Si la llamada original
     * seguía vigente se cierra en el acto: así los conductores no se quedan con dos órdenes iguales pendientes.
     */
    @Transactional
    public WaveCall recall(Long callId, User caller) {
        WaveCall original = callRepository.findWithDetailsById(callId)
                .orElseThrow(() -> new ResourceNotFoundException("Llamada no encontrada con ID: " + callId));
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        LocalDate today = now.toLocalDate();
        Wave wave = original.getWave();
        if (!original.getWorkDate().equals(today)) {
            throw new InvalidStateException("Solo se puede repetir una llamada de hoy");
        }
        callRepository.findFirstByWaveIdAndWorkDateOrderByCalledAtDescIdDesc(wave.getId(), today)
                .filter(latest -> !latest.getId().equals(original.getId()))
                .ifPresent(latest -> {
                    throw new InvalidStateException(
                            "Hay una llamada más reciente a la ola " + wave.getName() + ": repite esa");
                });

        List<ShiftAssignment> roster = shiftRepository.findByWorkDateAndWaveId(today, wave.getId());
        if (missingShifts(original, roster).isEmpty()) {
            throw new InvalidStateException("En la ola " + wave.getName() + " no falta nadie por confirmar");
        }

        if (original.isOpenAt(now)) {
            original.setExpiresAt(now);
        }
        return createCall(wave, today, original.getMessage(), caller, now, roster, confirmationsOf(original));
    }

    /**
     * Cuántos conductores llamaría «volver a llamar» ahora, por llamada, de las llamadas de [day] que se pueden
     * repetir. Solo cuenta la última llamada de cada ola y solo si el día es hoy; el resto no aparece en el mapa.
     */
    @Transactional(readOnly = true)
    public Map<Long, Integer> getRecallableCounts(LocalDate day) {
        if (!day.equals(LocalDate.now())) {
            return Map.of();
        }
        // la más reciente primero: la primera llamada que se ve de cada ola es la última de esa ola
        List<WaveCall> calls = callRepository.findWithDetailsByWorkDate(day);
        if (calls.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<ShiftAssignment>> rosterByWave = new HashMap<>();
        for (ShiftAssignment shift : shiftRepository.findInRange(day, day)) {
            rosterByWave.computeIfAbsent(shift.getWave().getId(), id -> new ArrayList<>()).add(shift);
        }
        Map<Long, Integer> counts = new HashMap<>();
        Set<Long> seenWaves = new HashSet<>();
        for (WaveCall call : calls) {
            Long waveId = call.getWave().getId();
            if (!seenWaves.add(waveId)) {
                continue;
            }
            int missing = missingShifts(call, rosterByWave.getOrDefault(waveId, List.of())).size();
            if (missing > 0) {
                counts.put(call.getId(), missing);
            }
        }
        return counts;
    }

    /** Los turnos de la ola cuyo conductor no ha confirmado esta llamada (o ni siquiera la recibió). */
    private static List<ShiftAssignment> missingShifts(WaveCall call, List<ShiftAssignment> roster) {
        Set<Long> confirmed = confirmationsOf(call).keySet();
        return roster.stream().filter(shift -> !confirmed.contains(shift.getDriver().getId())).toList();
    }

    /** Quién ha confirmado la llamada (id del conductor) y cuándo. */
    private static Map<Long, LocalDateTime> confirmationsOf(WaveCall call) {
        Map<Long, LocalDateTime> confirmed = new HashMap<>();
        for (WaveCallRecipient recipient : call.getRecipients()) {
            if (recipient.isAcknowledged()) {
                confirmed.put(recipient.getDriver().getId(), recipient.getAcknowledgedAt());
            }
        }
        return confirmed;
    }

    /**
     * Guarda la llamada con un destinatario por turno y, al confirmarse la transacción, avisa por push a los que aún no
     * han confirmado. [confirmed] trae, por id de conductor, la confirmación que ya tenían (al repetir una llamada);
     * esos destinatarios nacen confirmados y no reciben otro aviso.
     */
    private WaveCall createCall(Wave wave, LocalDate today, String message, User caller, LocalDateTime now,
                                List<ShiftAssignment> shifts, Map<Long, LocalDateTime> confirmed) {
        WaveCall call = new WaveCall();
        call.setWave(wave);
        call.setWorkDate(today);
        call.setMessage(message);
        call.setCalledBy(caller);
        call.setCalledAt(now);
        call.setExpiresAt(now.plus(acknowledgeWindow));
        for (ShiftAssignment shift : shifts) {
            WaveCallRecipient recipient = new WaveCallRecipient();
            recipient.setWaveCall(call);
            recipient.setDriver(shift.getDriver());
            recipient.setVehicleLicensePlate(shift.getVehicle().getLicensePlate());
            recipient.setAcknowledgedAt(confirmed.get(shift.getDriver().getId()));
            call.getRecipients().add(recipient);
        }
        WaveCall saved = callRepository.saveAndFlush(call);

        List<Long> toNotify = shifts.stream()
                .map(shift -> shift.getDriver().getId())
                .filter(driverId -> !confirmed.containsKey(driverId))
                .toList();
        eventPublisher.publishEvent(new WaveCalledEvent(saved.getId(), wave.getId(), wave.getName(), today,
                saved.getMessage(), toNotify));
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
     * igual que con un @PreAuthorize) y mientras la orden siga vigente (409 si ya caducó). Es idempotente: confirmar
     * otra vez devuelve la confirmación de la primera vez, aunque la orden haya caducado entretanto.
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
            LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
            WaveCall call = recipient.getWaveCall();
            if (!call.isOpenAt(now)) {
                throw new InvalidStateException("La orden de " + call.getWave().getName()
                        + " ya caducó: ya no se puede confirmar");
            }
            recipient.setAcknowledgedAt(now);
        }
        return recipient;
    }
}
