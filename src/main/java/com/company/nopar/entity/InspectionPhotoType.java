package com.company.nopar.entity;

import java.util.Arrays;
import java.util.Optional;
import java.util.function.Function;

/**
 * Las fotos que puede llevar un parte. El {@code slug} es el nombre que aparece en la URL de la foto
 * ({@code /api/v1/inspections/{id}/photos/{slug}}); el nombre del fichero en el servidor nunca sale al cliente.
 */
public enum InspectionPhotoType {

    ODOMETER("odometer", VehicleInspection::getOdometerPhotoPath),
    BEACON("beacon", VehicleInspection::getBeaconPhotoPath),
    EXTERIOR("exterior", VehicleInspection::getExteriorPhotosPath);

    private final String slug;
    private final Function<VehicleInspection, String> storedName;

    InspectionPhotoType(String slug, Function<VehicleInspection, String> storedName) {
        this.slug = slug;
        this.storedName = storedName;
    }

    public String slug() {
        return slug;
    }

    /** Nombre del fichero guardado para esta foto, o null si el parte no la tiene. */
    public String storedNameOf(VehicleInspection inspection) {
        String name = storedName.apply(inspection);
        return name == null || name.isBlank() ? null : name;
    }

    /** URL relativa de la foto: la misma ruta que atiende InspectionController. */
    public String urlFor(Long inspectionId) {
        return "/api/v1/inspections/" + inspectionId + "/photos/" + slug;
    }

    /** Busca el tipo por el nombre que lleva la URL ("odometer", "beacon" o "exterior", en minúsculas). */
    public static Optional<InspectionPhotoType> fromSlug(String slug) {
        return Arrays.stream(values()).filter(type -> type.slug.equals(slug)).findFirst();
    }
}
