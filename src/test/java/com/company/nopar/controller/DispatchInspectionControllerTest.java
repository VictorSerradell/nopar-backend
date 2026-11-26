package com.company.nopar.controller;

import com.company.nopar.entity.User;
import com.company.nopar.entity.Vehicle;
import com.company.nopar.entity.VehicleInspection;
import com.company.nopar.entity.VehicleInspection.InspectionStatus;
import com.company.nopar.security.JwtAuthenticationFilter;
import com.company.nopar.security.JwtService;
import com.company.nopar.security.SecurityConfig;
import com.company.nopar.service.InspectionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Con la cadena de seguridad real: los encargados y los administradores ven los partes; los conductores no. Aprobar o
 * rechazar sigue siendo solo del administrador.
 */
@WebMvcTest(DispatchInspectionController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class DispatchInspectionControllerTest {

    private static final String URL = "/api/v1/dispatch/inspections";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private InspectionService inspectionService;

    @MockitoBean
    private JwtService jwtService;

    @MockitoBean
    private UserDetailsService userDetailsService;

    // ---------------------------------------------------------------- quién puede

    @Test
    void givenNoToken_whenGetInspections_thenUnauthorized() throws Exception {
        mockMvc.perform(get(URL)).andExpect(status().isUnauthorized());

        verifyNoInteractions(inspectionService);
    }

    @Test
    void givenADriver_whenGetInspections_thenForbidden() throws Exception {
        mockMvc.perform(get(URL).with(user(newUser(2L, "ana", User.Role.DRIVER))))
                .andExpect(status().isForbidden());

        verifyNoInteractions(inspectionService);
    }

    @Test
    void givenAManager_whenReview_thenForbiddenBecauseOnlyAdminsReview() throws Exception {
        mockMvc.perform(patch("/api/v1/admin/inspections/1/review").with(user(newUser(5L, "encargado1", User.Role.MANAGER)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\": \"APPROVED\"}"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(inspectionService);
    }

    // ---------------------------------------------------------------- listar

    @Test
    void givenAManager_whenGetInspections_thenAllOfThemNewestFirstWithDefaultPaging() throws Exception {
        when(inspectionService.getAllInspections(0, 20))
                .thenReturn(new PageImpl<>(List.of(inspection(InspectionStatus.SUBMITTED)), PageRequest.of(0, 20), 1));

        mockMvc.perform(get(URL).with(user(newUser(5L, "encargado1", User.Role.MANAGER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(1))
                .andExpect(jsonPath("$.content[0].status").value("SUBMITTED"))
                .andExpect(jsonPath("$.content[0].vehicleLicensePlate").value("1234-ABC"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$..password").isEmpty());
    }

    @Test
    void givenAnAdmin_whenGetInspectionsWithAStatus_thenOnlyThatStatusIsAskedFor() throws Exception {
        when(inspectionService.getInspectionsByStatus(InspectionStatus.REJECTED, 1, 50))
                .thenReturn(new PageImpl<>(List.of(inspection(InspectionStatus.REJECTED)), PageRequest.of(1, 50), 51));

        mockMvc.perform(get(URL + "?status=REJECTED&page=1&size=50").with(user(newUser(1L, "admin1", User.Role.ADMIN))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].status").value("REJECTED"))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.totalElements").value(51));

        verify(inspectionService).getInspectionsByStatus(InspectionStatus.REJECTED, 1, 50);
        verifyNoMoreInteractions(inspectionService);
    }

    @Test
    void givenAnUnknownStatus_whenGetInspections_thenBadRequest() throws Exception {
        mockMvc.perform(get(URL + "?status=PENDIENTE").with(user(newUser(5L, "encargado1", User.Role.MANAGER))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Datos no válidos"));

        verifyNoInteractions(inspectionService);
    }

    @Test
    void givenAPageSizeOverTheLimit_whenGetInspections_thenBadRequest() throws Exception {
        mockMvc.perform(get(URL + "?size=101").with(user(newUser(5L, "encargado1", User.Role.MANAGER))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Datos no válidos"))
                .andExpect(jsonPath("$.errors[0]").value("El tamaño máximo de página es 100"));

        verifyNoInteractions(inspectionService);
    }

    private static VehicleInspection inspection(InspectionStatus status) {
        Vehicle vehicle = new Vehicle();
        vehicle.setId(5L);
        vehicle.setLicensePlate("1234-ABC");

        VehicleInspection inspection = new VehicleInspection();
        inspection.setId(1L);
        inspection.setVehicle(vehicle);
        inspection.setKilometers(15000);
        inspection.setStatus(status);
        return inspection;
    }

    private static User newUser(Long id, String username, User.Role role) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setPassword("not-a-real-hash");
        user.setRole(role);
        return user;
    }
}
