package com.company.nopar.dto;

import com.company.nopar.entity.Wave;

import java.time.LocalTime;
import java.util.List;

public record WaveResponse(Long id, String name, LocalTime startTime) {

    public static WaveResponse from(Wave wave) {
        return new WaveResponse(wave.getId(), wave.getName(), wave.getStartTime());
    }

    public static List<WaveResponse> fromAll(List<Wave> waves) {
        return waves.stream().map(WaveResponse::from).toList();
    }
}
