package com.company.nopar.dto;

import com.company.nopar.entity.Vehicle;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VehicleResponseTest {

    @Test
    void givenVehicle_whenFrom_thenCopiesAllPublicData() {
        VehicleResponse response = VehicleResponse.from(vehicle(3L, "1234-ABC"));

        assertThat(response.id()).isEqualTo(3L);
        assertThat(response.licensePlate()).isEqualTo("1234-ABC");
        assertThat(response.model()).isEqualTo("Ford Transit");
        assertThat(response.hasBeacon()).isTrue();
        assertThat(response.currentKilometers()).isEqualTo(15000);
        assertThat(response.status()).isEqualTo(Vehicle.VehicleStatus.IN_USE);
    }

    @Test
    void givenSeveralVehicles_whenFromAll_thenMapsEachOne() {
        List<VehicleResponse> responses =
                VehicleResponse.fromAll(List.of(vehicle(1L, "1111-AAA"), vehicle(2L, "2222-BBB")));

        assertThat(responses).extracting(VehicleResponse::licensePlate)
                .containsExactly("1111-AAA", "2222-BBB");
    }

    private static Vehicle vehicle(Long id, String licensePlate) {
        Vehicle vehicle = new Vehicle();
        vehicle.setId(id);
        vehicle.setLicensePlate(licensePlate);
        vehicle.setModel("Ford Transit");
        vehicle.setHasBeacon(true);
        vehicle.setCurrentKilometers(15000);
        vehicle.setStatus(Vehicle.VehicleStatus.IN_USE);
        return vehicle;
    }
}
