package com.company.nopar.dto;

import com.company.nopar.entity.User;
import com.company.nopar.entity.Vehicle;
import com.company.nopar.entity.VehicleInspection;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InspectionResponseTest {

    @Test
    void givenInspection_whenFrom_thenCopiesOnlyPublicData() {
        VehicleInspection inspection = fullInspection();

        InspectionResponse response = InspectionResponse.from(inspection);

        assertThat(response.id()).isEqualTo(1L);
        assertThat(response.vehicleId()).isEqualTo(5L);
        assertThat(response.vehicleLicensePlate()).isEqualTo("1234-ABC");
        assertThat(response.driverId()).isEqualTo(7L);
        assertThat(response.driverUsername()).isEqualTo("conductor1");
        assertThat(response.kilometers()).isEqualTo(15000);
        assertThat(response.tiresOk()).isTrue();
        assertThat(response.status()).isEqualTo(VehicleInspection.InspectionStatus.APPROVED);
        assertThat(response.odometerPhotoUrl()).isEqualTo("/api/v1/inspections/1/photos/odometer");
        assertThat(response.beaconPhotoUrl()).isNull();
        assertThat(response.exteriorPhotoUrl()).isNull();
        // ni el hash de la contraseña ni la ruta de las fotos en el servidor salen en la respuesta
        assertThat(response.toString())
                .doesNotContain("hash-que-no-debe-salir")
                .doesNotContain("/srv/uploads");
    }

    @Test
    void givenInspectionWithoutVehicleNorDriver_whenFrom_thenDoesNotFail() {
        VehicleInspection inspection = new VehicleInspection();
        inspection.setId(1L);
        inspection.setStatus(VehicleInspection.InspectionStatus.SUBMITTED);

        InspectionResponse response = InspectionResponse.from(inspection);

        assertThat(response.id()).isEqualTo(1L);
        assertThat(response.vehicleId()).isNull();
        assertThat(response.driverId()).isNull();
        assertThat(response.driverUsername()).isNull();
        assertThat(response.status()).isEqualTo(VehicleInspection.InspectionStatus.SUBMITTED);
    }

    @Test
    void givenSeveralInspections_whenFromAll_thenMapsEachOne() {
        List<InspectionResponse> responses = InspectionResponse.fromAll(List.of(fullInspection(), new VehicleInspection()));

        assertThat(responses).hasSize(2);
        assertThat(responses.get(0).driverUsername()).isEqualTo("conductor1");
    }

    @Test
    void givenReviewedInspection_whenFrom_thenIncludesReviewerUsernameAndDate() {
        VehicleInspection inspection = fullInspection();
        User admin = new User();
        admin.setId(1L);
        admin.setUsername("admin");
        admin.setPassword("$2a$10$otro-hash-que-no-debe-salir");
        admin.setRole(User.Role.ADMIN);
        LocalDateTime reviewedAt = LocalDateTime.of(2026, 11, 18, 9, 30);
        inspection.setReviewedBy(admin);
        inspection.setReviewedAt(reviewedAt);

        InspectionResponse response = InspectionResponse.from(inspection);

        assertThat(response.reviewedByUsername()).isEqualTo("admin");
        assertThat(response.reviewedAt()).isEqualTo(reviewedAt);
        assertThat(response.toString()).doesNotContain("otro-hash-que-no-debe-salir");
    }

    @Test
    void givenInspectionWithPhotos_whenFrom_thenExposesOnlyTheApiUrlsNeverTheStoredNames() {
        VehicleInspection inspection = fullInspection();
        inspection.setOdometerPhotoPath("3f2c9a-odometro.jpg");
        inspection.setBeaconPhotoPath("7b1d44-baliza.png");
        inspection.setExteriorPhotosPath("c9e0aa-exterior.jpg");

        InspectionResponse response = InspectionResponse.from(inspection);

        assertThat(response.odometerPhotoUrl()).isEqualTo("/api/v1/inspections/1/photos/odometer");
        assertThat(response.beaconPhotoUrl()).isEqualTo("/api/v1/inspections/1/photos/beacon");
        assertThat(response.exteriorPhotoUrl()).isEqualTo("/api/v1/inspections/1/photos/exterior");
        assertThat(response.toString())
                .doesNotContain("odometro.jpg")
                .doesNotContain("baliza.png")
                .doesNotContain("exterior.jpg");
    }

    @Test
    void givenInspectionWithoutPhotosOrId_whenFrom_thenNoPhotoUrls() {
        InspectionResponse response = InspectionResponse.from(new VehicleInspection());

        assertThat(response.odometerPhotoUrl()).isNull();
        assertThat(response.beaconPhotoUrl()).isNull();
        assertThat(response.exteriorPhotoUrl()).isNull();
    }

    @Test
    void givenInspectionLinkedToAShift_whenFrom_thenExposesTheShiftAndWhetherTheVanMatches() {
        VehicleInspection inspection = fullInspection();
        inspection.setShiftId(40L);
        inspection.setMatchesAssignedVehicle(false);

        InspectionResponse response = InspectionResponse.from(inspection);

        assertThat(response.shiftId()).isEqualTo(40L);
        assertThat(response.assignedVehicleMatches()).isFalse();
    }

    @Test
    void givenInspectionWithoutShift_whenFrom_thenNoLinkAndTheLeftoverFlagIsHidden() {
        // al borrar un turno la base de datos pone shift_id a null pero deja la marca: sin turno no se muestra
        VehicleInspection inspection = fullInspection();
        inspection.setShiftId(null);
        inspection.setMatchesAssignedVehicle(true);

        InspectionResponse response = InspectionResponse.from(inspection);

        assertThat(response.shiftId()).isNull();
        assertThat(response.assignedVehicleMatches()).isNull();
    }

    @Test
    void response_doesNotDeclareEntitiesNorSensitiveComponents() {
        List<String> components = Arrays.stream(InspectionResponse.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();

        assertThat(components).doesNotContain(
                "driver", "vehicle", "reviewedBy", "password",
                "odometerPhotoPath", "beaconPhotoPath", "exteriorPhotosPath");
    }

    private static VehicleInspection fullInspection() {
        Vehicle vehicle = new Vehicle();
        vehicle.setId(5L);
        vehicle.setLicensePlate("1234-ABC");

        User driver = new User();
        driver.setId(7L);
        driver.setUsername("conductor1");
        driver.setPassword("$2a$10$hash-que-no-debe-salir");
        driver.setRole(User.Role.DRIVER);

        VehicleInspection inspection = new VehicleInspection();
        inspection.setId(1L);
        inspection.setVehicle(vehicle);
        inspection.setDriver(driver);
        inspection.setKilometers(15000);
        inspection.setTiresOk(true);
        inspection.setLightsOk(true);
        inspection.setBeaconV16Present(true);
        inspection.setOdometerPhotoPath("/srv/uploads/odometro.jpg");
        inspection.setStatus(VehicleInspection.InspectionStatus.APPROVED);
        return inspection;
    }
}
