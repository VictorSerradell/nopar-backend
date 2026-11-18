package com.company.nopar.dto;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PageResponseTest {

    @Test
    void givenPage_whenOf_thenMapsContentAndCopiesPagingMetadata() {
        Page<Integer> page = new PageImpl<>(List.of(1, 2), PageRequest.of(1, 2), 5);

        PageResponse<String> response = PageResponse.of(page, number -> "n" + number);

        assertThat(response.content()).containsExactly("n1", "n2");
        assertThat(response.page()).isEqualTo(1);
        assertThat(response.size()).isEqualTo(2);
        assertThat(response.totalElements()).isEqualTo(5);
        assertThat(response.totalPages()).isEqualTo(3);
    }

    @Test
    void givenEmptyPage_whenOf_thenEmptyContentAndZeroTotals() {
        Page<Integer> page = new PageImpl<Integer>(List.of(), PageRequest.of(0, 20), 0);

        PageResponse<String> response = PageResponse.of(page, Object::toString);

        assertThat(response.content()).isEmpty();
        assertThat(response.totalElements()).isZero();
        assertThat(response.totalPages()).isZero();
    }
}
