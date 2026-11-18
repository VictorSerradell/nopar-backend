package com.company.nopar.dto;

import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/**
 * Página de resultados con un formato propio y estable para los clientes (Android, iOS, Windows):
 * no se serializa el Page de Spring Data, cuyo JSON no es un contrato.
 * Las constantes son los límites de ?page= y ?size= que validan los controladores.
 */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {

    public static final String DEFAULT_PAGE = "0";
    public static final String DEFAULT_SIZE = "20";
    public static final int MAX_SIZE = 100;

    public static final String PAGE_MIN_MESSAGE = "La página no puede ser negativa";
    public static final String SIZE_MIN_MESSAGE = "El tamaño de página debe ser al menos 1";
    public static final String SIZE_MAX_MESSAGE = "El tamaño máximo de página es " + MAX_SIZE;

    public static <E, T> PageResponse<T> of(Page<E> page, Function<E, T> mapper) {
        return new PageResponse<>(
                page.getContent().stream().map(mapper).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages()
        );
    }
}
