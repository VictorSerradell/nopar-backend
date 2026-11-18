package com.company.nopar.dto;

import com.company.nopar.entity.Vehicle;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VehicleRequestTest {

    @Test
    void givenRequest_whenToEntity_thenServerDecidesIdAndStatus() {
        Vehicle vehicle = new VehicleRequest("1234-ABC", "Ford Transit", null, 15000).toEntity();

        assertThat(vehicle.getId()).isNull();
        assertThat(vehicle.getStatus()).isEqualTo(Vehicle.VehicleStatus.AVAILABLE);
        assertThat(vehicle.getHasBeacon()).isFalse();
        assertThat(vehicle.getLicensePlate()).isEqualTo("1234-ABC");
        assertThat(vehicle.getModel()).isEqualTo("Ford Transit");
        assertThat(vehicle.getCurrentKilometers()).isEqualTo(15000);
    }

    @Test
    void givenBeaconTrue_whenToEntity_thenKeepsIt() {
        Vehicle vehicle = new VehicleRequest("1234-ABC", "Ford Transit", true, 0).toEntity();

        assertThat(vehicle.getHasBeacon()).isTrue();
    }

    @Test
    void givenSurroundingWhitespace_whenToEntity_thenStripsIt() {
        Vehicle vehicle = new VehicleRequest("  1234-ABC ", " Ford Transit  ", false, 10).toEntity();

        assertThat(vehicle.getLicensePlate()).isEqualTo("1234-ABC");
        assertThat(vehicle.getModel()).isEqualTo("Ford Transit");
    }

    @Test
    void request_doesNotDeclareServerControlledComponents() {
        List<String> components = Arrays.stream(VehicleRequest.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();

        assertThat(components).doesNotContain("id", "status");
    }
}
