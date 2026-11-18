package com.company.nopar.entity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InspectionPhotoTypeTest {

    @Test
    void givenKnownSlug_whenFromSlug_thenFindsTheType() {
        assertThat(InspectionPhotoType.fromSlug("odometer")).contains(InspectionPhotoType.ODOMETER);
        assertThat(InspectionPhotoType.fromSlug("beacon")).contains(InspectionPhotoType.BEACON);
        assertThat(InspectionPhotoType.fromSlug("exterior")).contains(InspectionPhotoType.EXTERIOR);
    }

    @Test
    void givenUnknownSlug_whenFromSlug_thenEmpty() {
        assertThat(InspectionPhotoType.fromSlug("passport")).isEmpty();
        assertThat(InspectionPhotoType.fromSlug("ODOMETER")).isEmpty();
        assertThat(InspectionPhotoType.fromSlug("")).isEmpty();
        assertThat(InspectionPhotoType.fromSlug(null)).isEmpty();
    }

    @Test
    void givenInspection_whenStoredNameOf_thenEachTypeReadsItsOwnField() {
        VehicleInspection inspection = new VehicleInspection();
        inspection.setOdometerPhotoPath("odo.jpg");
        inspection.setBeaconPhotoPath("beacon.png");
        inspection.setExteriorPhotosPath("ext.jpg");

        assertThat(InspectionPhotoType.ODOMETER.storedNameOf(inspection)).isEqualTo("odo.jpg");
        assertThat(InspectionPhotoType.BEACON.storedNameOf(inspection)).isEqualTo("beacon.png");
        assertThat(InspectionPhotoType.EXTERIOR.storedNameOf(inspection)).isEqualTo("ext.jpg");
    }

    @Test
    void givenMissingOrBlankPhoto_whenStoredNameOf_thenNull() {
        VehicleInspection inspection = new VehicleInspection();
        inspection.setBeaconPhotoPath("   ");

        assertThat(InspectionPhotoType.ODOMETER.storedNameOf(inspection)).isNull();
        assertThat(InspectionPhotoType.BEACON.storedNameOf(inspection)).isNull();
    }

    @Test
    void givenInspectionId_whenUrlFor_thenRelativeApiPath() {
        assertThat(InspectionPhotoType.ODOMETER.urlFor(42L)).isEqualTo("/api/v1/inspections/42/photos/odometer");
        assertThat(InspectionPhotoType.EXTERIOR.urlFor(42L)).isEqualTo("/api/v1/inspections/42/photos/exterior");
    }
}
