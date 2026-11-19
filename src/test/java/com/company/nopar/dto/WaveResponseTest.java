package com.company.nopar.dto;

import com.company.nopar.entity.Wave;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WaveResponseTest {

    @Test
    void givenWave_whenFrom_thenCopiesItsData() {
        WaveResponse response = WaveResponse.from(wave(1L, "Ola 1", LocalTime.of(9, 30)));

        assertThat(response.id()).isEqualTo(1L);
        assertThat(response.name()).isEqualTo("Ola 1");
        assertThat(response.startTime()).isEqualTo(LocalTime.of(9, 30));
    }

    @Test
    void givenSeveralWaves_whenFromAll_thenMapsEachOneInOrder() {
        List<WaveResponse> responses = WaveResponse.fromAll(
                List.of(wave(1L, "Ola 1", LocalTime.of(9, 30)), wave(2L, "Ola 2", LocalTime.of(11, 0))));

        assertThat(responses).extracting(WaveResponse::name).containsExactly("Ola 1", "Ola 2");
    }

    private static Wave wave(Long id, String name, LocalTime startTime) {
        Wave wave = new Wave();
        wave.setId(id);
        wave.setName(name);
        wave.setStartTime(startTime);
        return wave;
    }
}
