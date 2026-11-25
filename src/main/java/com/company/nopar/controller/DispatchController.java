package com.company.nopar.controller;

import com.company.nopar.dto.ShiftResponse;
import com.company.nopar.dto.TodayScheduleResponse;
import com.company.nopar.dto.TodayShiftRequest;
import com.company.nopar.dto.TodayShiftUpdateRequest;
import com.company.nopar.dto.WaveCallRequest;
import com.company.nopar.dto.WaveCallResponse;
import com.company.nopar.dto.WaveResponse;
import com.company.nopar.entity.User;
import com.company.nopar.entity.WaveCall;
import com.company.nopar.service.ShiftService;
import com.company.nopar.service.TodayScheduleService;
import com.company.nopar.service.WaveCallService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Dar la orden de subir a una ola, ver quién la ha confirmado y montar los turnos de hoy. Para administradores y
 * encargados: SecurityConfig reserva /api/v1/dispatch a esos dos roles.
 */
@Tag(name = "Llamadas de ola", description = "Dar la orden de subir a una ola (administradores y encargados) y confirmarla con «Enterado» (conductores)")
@RestController
@RequestMapping("/api/v1/dispatch")
@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
@RequiredArgsConstructor
public class DispatchController {

    private final WaveCallService waveCallService;
    private final ShiftService shiftService;
    private final TodayScheduleService todayScheduleService;

    @Operation(summary = "Lista las olas", description = "Ordenadas por hora de salida: para saber a cuál llamar.")
    @GetMapping("/waves")
    public ResponseEntity<List<WaveResponse>> getWaves() {
        return ResponseEntity.ok(WaveResponse.fromAll(shiftService.getWaves()));
    }

    @Operation(summary = "Llama a una ola",
            description = "Da la orden (por defecto «Subid», o el texto del cuerpo) a los conductores que hoy tienen turno en la ola y les avisa por push; cada uno confirma con «Enterado». Se puede llamar a la misma ola varias veces el mismo día.")
    @ApiResponse(responseCode = "400", description = "El mensaje supera los 140 caracteres")
    @ApiResponse(responseCode = "404", description = "La ola no existe")
    @ApiResponse(responseCode = "409", description = "Hoy nadie tiene turno en esa ola")
    @PostMapping("/waves/{waveId}/calls")
    public ResponseEntity<WaveCallResponse> callWave(
            @PathVariable Long waveId,
            @Valid @RequestBody(required = false) WaveCallRequest request,
            @AuthenticationPrincipal User currentUser // quien llama sale del token JWT
    ) {
        String message = request != null ? request.message() : null;
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(respond(waveCallService.callWave(waveId, message, currentUser)));
    }

    @Operation(summary = "Vuelve a llamar a los que faltan",
            description = "Repite la orden (mismo mensaje, ventana nueva) solo a los conductores que hoy tienen turno en la ola y no han confirmado esta llamada: quienes no contestaron y quienes entraron en la ola después. Les avisa por push. Solo vale para la última llamada de la ola de hoy; si la llamada seguía vigente, se cierra.")
    @ApiResponse(responseCode = "404", description = "La llamada no existe")
    @ApiResponse(responseCode = "409", description = "No es de hoy, hay otra llamada más reciente a esa ola, o no falta nadie por confirmar")
    @PostMapping("/wave-calls/{id}/recall")
    public ResponseEntity<WaveCallResponse> recall(@PathVariable Long id, @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.status(HttpStatus.CREATED).body(respond(waveCallService.recall(id, currentUser)));
    }

    @Operation(summary = "Llamadas de un día",
            description = "Con a quién se llamó y quién ha confirmado, la más reciente primero. Sin fecha, las de hoy.")
    @ApiResponse(responseCode = "400", description = "El formato de la fecha no es yyyy-MM-dd")
    @GetMapping("/wave-calls")
    public ResponseEntity<List<WaveCallResponse>> getCalls(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        LocalDate day = date != null ? date : LocalDate.now();
        return ResponseEntity.ok(WaveCallResponse.fromAll(
                waveCallService.getCalls(day), waveCallService.getRecallableCounts(day)));
    }

    @Operation(summary = "Una llamada", description = "A quién se llamó y quién ha confirmado.")
    @ApiResponse(responseCode = "404", description = "La llamada no existe")
    @GetMapping("/wave-calls/{id}")
    public ResponseEntity<WaveCallResponse> getCall(@PathVariable Long id) {
        return ResponseEntity.ok(respond(waveCallService.getCall(id)));
    }

    // ---------------------------------------------------------------- turnos de hoy

    @Operation(summary = "La planificación de hoy",
            description = "Cada ola con los conductores que salen hoy en ella y, aparte, los conductores y las furgonetas que aún no tienen turno hoy (y se pueden asignar).")
    @GetMapping("/schedule")
    public ResponseEntity<TodayScheduleResponse> getSchedule() {
        var schedule = todayScheduleService.getToday();
        return ResponseEntity.ok(TodayScheduleResponse.from(
                schedule.date(), schedule.waves(), schedule.shifts(), schedule.freeDrivers(), schedule.freeVehicles()));
    }

    @Operation(summary = "Pone a un conductor en una ola de hoy",
            description = "Conductor y furgoneta de una ola hoy. Las mismas reglas que el administrador: el usuario tiene que ser DRIVER, ni él ni la furgoneta pueden tener otro turno hoy, y la furgoneta no puede estar en MAINTENANCE ni DAMAGED. Avisa por push al conductor. Si la ola ya se llamó hoy, el nuevo conductor no recibió esa llamada: se le llama con «volver a llamar a los que faltan».")
    @ApiResponse(responseCode = "400", description = "Datos no válidos, o el usuario no es un conductor")
    @ApiResponse(responseCode = "404", description = "La ola, el conductor o el vehículo no existen")
    @ApiResponse(responseCode = "409", description = "El conductor o la furgoneta ya tienen un turno hoy, o la furgoneta está en mantenimiento o dañada")
    @PostMapping("/shifts")
    public ResponseEntity<ShiftResponse> addShift(
            @Valid @RequestBody TodayShiftRequest request,
            @AuthenticationPrincipal User currentUser
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ShiftResponse.from(
                todayScheduleService.addShift(request.waveId(), request.driverId(), request.vehicleId(), currentUser)));
    }

    @Operation(summary = "Mueve un turno de hoy",
            description = "Lo pasa a otra ola y/o le cambia la furgoneta (solo cambian los campos enviados). Avisa por push al conductor. Solo turnos de hoy: los de otros días son del administrador.")
    @ApiResponse(responseCode = "400", description = "No se envió ningún cambio")
    @ApiResponse(responseCode = "404", description = "El turno, la ola o el vehículo no existen")
    @ApiResponse(responseCode = "409", description = "No es un turno de hoy, la furgoneta ya tiene turno hoy o está en mantenimiento o dañada")
    @PatchMapping("/shifts/{id}")
    public ResponseEntity<ShiftResponse> moveShift(@PathVariable Long id, @RequestBody TodayShiftUpdateRequest request) {
        return ResponseEntity.ok(ShiftResponse.from(
                todayScheduleService.moveShift(id, request.waveId(), request.vehicleId())));
    }

    @Operation(summary = "Quita un turno de hoy",
            description = "Avisa por push al conductor de que ya no sale. Solo turnos de hoy.")
    @ApiResponse(responseCode = "204", description = "Turno eliminado")
    @ApiResponse(responseCode = "404", description = "El turno no existe")
    @ApiResponse(responseCode = "409", description = "No es un turno de hoy")
    @DeleteMapping("/shifts/{id}")
    public ResponseEntity<Void> removeShift(@PathVariable Long id) {
        todayScheduleService.removeShift(id);
        return ResponseEntity.noContent().build();
    }

    /** La llamada con a cuántos llamaría «volver a llamar» ahora (0 si no se puede repetir). */
    private WaveCallResponse respond(WaveCall call) {
        int recallCount = waveCallService.getRecallableCounts(call.getWorkDate()).getOrDefault(call.getId(), 0);
        return WaveCallResponse.from(call, LocalDateTime.now(), recallCount);
    }
}
