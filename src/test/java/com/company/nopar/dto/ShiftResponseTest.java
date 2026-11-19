package com.company.nopar.dto;

import com.company.nopar.entity.ShiftAssignment;
import com.company.nopar.entity.User;
import com.company.nopar.entity.Vehicle;
import com.company.nopar.entity.Wave;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ShiftResponseTest {

    @Test
    void givenShift_whenFrom_thenCopiesTheWaveTheDriverAndTheVanWithoutExposingEntities() {
        ShiftResponse response = ShiftResponse.from(shift(4L, 2L, "driver1"));

        assertThat(response.id()).isEqualTo(4L);
        assertThat(response.workDate()).isEqualTo(LocalDate.of(2026, 11, 19));
        assertThat(response.waveId()).isEqualTo(1L);
        assertThat(response.waveName()).isEqualTo("Ola 1");
        assertThat(response.waveStartTime()).isEqualTo(LocalTime.of(9, 30));
        assertThat(response.driverId()).isEqualTo(2L);
        assertThat(response.driverUsername()).isEqualTo("driver1");
        assertThat(response.vehicleId()).isEqualTo(3L);
        assertThat(response.vehicleLicensePlate()).isEqualTo("1111AAA");
    }

    @Test
    void givenShiftWithoutRelations_whenFrom_thenTheirFieldsAreNull() {
        ShiftAssignment shift = new ShiftAssignment();
        shift.setId(4L);
        shift.setWorkDate(LocalDate.of(2026, 11, 19));

        ShiftResponse response = ShiftResponse.from(shift);

        assertThat(response.id()).isEqualTo(4L);
        assertThat(response.waveId()).isNull();
        assertThat(response.waveName()).isNull();
        assertThat(response.waveStartTime()).isNull();
        assertThat(response.driverId()).isNull();
        assertThat(response.driverUsername()).isNull();
        assertThat(response.vehicleId()).isNull();
        assertThat(response.vehicleLicensePlate()).isNull();
    }

    @Test
    void givenSeveralShifts_whenFromAll_thenMapsEachOneInOrder() {
        List<ShiftResponse> responses = ShiftResponse.fromAll(List.of(shift(1L, 2L, "ana"), shift(2L, 5L, "bea")));

        assertThat(responses).extracting(ShiftResponse::driverUsername).containsExactly("ana", "bea");
    }

    private static ShiftAssignment shift(Long id, Long driverId, String driverUsername) {
        Wave wave = new Wave();
        wave.setId(1L);
        wave.setName("Ola 1");
        wave.setStartTime(LocalTime.of(9, 30));
        User driver = new User();
        driver.setId(driverId);
        driver.setUsername(driverUsername);
        Vehicle van = new Vehicle();
        van.setId(3L);
        van.setLicensePlate("1111AAA");

        ShiftAssignment shift = new ShiftAssignment();
        shift.setId(id);
        shift.setWorkDate(LocalDate.of(2026, 11, 19));
        shift.setWave(wave);
        shift.setDriver(driver);
        shift.setVehicle(van);
        return shift;
    }
}
