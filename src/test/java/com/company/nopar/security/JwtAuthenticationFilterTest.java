package com.company.nopar.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Test unitario del filtro con un JwtService real (claves solo para test): comprueba que un token
 * inválido nunca rompe la petición y que simplemente se continúa sin autenticar.
 */
class JwtAuthenticationFilterTest {

    private static final String PROTECTED_PATH = "/api/v1/fleet/vehicles";
    private static final String SECRET = base64("unit-test-only-signing-key-0123456789-ab");
    private static final String OTHER_SECRET = base64("another-test-only-signing-key-9876543210");

    private final UserDetailsService userDetailsService = mock(UserDetailsService.class);
    private final UserDetails driver = org.springframework.security.core.userdetails.User
            .withUsername("conductor1")
            .password("irrelevante")
            .authorities("ROLE_DRIVER")
            .build();

    private JwtService jwtService;
    private JwtAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        jwtService = jwtServiceWith(SECRET, 3_600_000L);
        filter = new JwtAuthenticationFilter(jwtService, userDetailsService);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void givenNoAuthorizationHeader_whenFilter_thenContinuesWithoutAuthentication() throws Exception {
        MockFilterChain chain = new MockFilterChain();

        run(new MockHttpServletRequest("GET", PROTECTED_PATH), chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void givenValidToken_whenFilter_thenAuthenticatesWithTheRolesFromTheDatabase() throws Exception {
        when(userDetailsService.loadUserByUsername("conductor1")).thenReturn(driver);
        String token = jwtService.generateToken(driver);
        MockFilterChain chain = new MockFilterChain();

        run(requestWithBearer(PROTECTED_PATH, token), chain);

        var authentication = SecurityContextHolder.getContext().getAuthentication();
        assertThat(chain.getRequest()).isNotNull();
        assertThat(authentication).isNotNull();
        assertThat(authentication.getName()).isEqualTo("conductor1");
        assertThat(authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList())
                .containsExactly("ROLE_DRIVER");
    }

    @Test
    void givenRoleChangedAfterTokenWasIssued_whenFilter_thenDatabaseRolesWinOverTokenClaims() throws Exception {
        UserDetails formerAdmin = org.springframework.security.core.userdetails.User
                .withUsername("conductor1")
                .password("irrelevante")
                .authorities("ROLE_ADMIN")
                .build();
        String tokenWithAdminClaim = jwtService.generateToken(formerAdmin);
        when(userDetailsService.loadUserByUsername("conductor1")).thenReturn(driver); // en la BD ya es conductor
        MockFilterChain chain = new MockFilterChain();

        run(requestWithBearer(PROTECTED_PATH, tokenWithAdminClaim), chain);

        var authentication = SecurityContextHolder.getContext().getAuthentication();
        assertThat(authentication).isNotNull();
        assertThat(authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList())
                .containsExactly("ROLE_DRIVER");
    }

    @Test
    void givenExpiredToken_whenFilter_thenContinuesWithoutAuthentication() throws Exception {
        String expiredToken = jwtServiceWith(SECRET, -1_000L).generateToken(driver);
        MockFilterChain chain = new MockFilterChain();

        MockHttpServletResponse response = run(requestWithBearer(PROTECTED_PATH, expiredToken), chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(response.getStatus()).isEqualTo(200); // el filtro no decide el 401: lo hace el entry point
    }

    @Test
    void givenTokenSignedWithAnotherKey_whenFilter_thenContinuesWithoutAuthentication() throws Exception {
        String forgedToken = jwtServiceWith(OTHER_SECRET, 3_600_000L).generateToken(driver);
        MockFilterChain chain = new MockFilterChain();

        run(requestWithBearer(PROTECTED_PATH, forgedToken), chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void givenMalformedToken_whenFilter_thenContinuesWithoutAuthentication() throws Exception {
        MockFilterChain chain = new MockFilterChain();

        run(requestWithBearer(PROTECTED_PATH, "esto-no-es-un-jwt"), chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void givenEmptyBearerToken_whenFilter_thenContinuesWithoutAuthentication() throws Exception {
        MockFilterChain chain = new MockFilterChain();

        run(requestWithBearer(PROTECTED_PATH, ""), chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void givenTokenOfDeletedUser_whenFilter_thenContinuesWithoutAuthentication() throws Exception {
        when(userDetailsService.loadUserByUsername("conductor1"))
                .thenThrow(new UsernameNotFoundException("Usuario no encontrado"));
        String token = jwtService.generateToken(driver);
        MockFilterChain chain = new MockFilterChain();

        run(requestWithBearer(PROTECTED_PATH, token), chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void givenPublicPath_whenFilter_thenTokenIsNotEvenParsed() throws Exception {
        JwtService spiedJwtService = spy(jwtService);
        JwtAuthenticationFilter publicPathFilter = new JwtAuthenticationFilter(spiedJwtService, userDetailsService);
        MockFilterChain chain = new MockFilterChain();

        publicPathFilter.doFilter(
                requestWithBearer("/api/v1/auth/refresh", "access-token-caducado"),
                new MockHttpServletResponse(),
                chain);

        assertThat(chain.getRequest()).isNotNull();
        verify(spiedJwtService, never()).extractUsername(any());
    }

    // ---------- utilidades ----------

    private MockHttpServletResponse run(MockHttpServletRequest request, MockFilterChain chain) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return response;
    }

    private static MockHttpServletRequest requestWithBearer(String path, String token) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.addHeader("Authorization", "Bearer " + token);
        return request;
    }

    private static JwtService jwtServiceWith(String base64Secret, long expirationMillis) {
        JwtService service = new JwtService();
        ReflectionTestUtils.setField(service, "secretKey", base64Secret);
        ReflectionTestUtils.setField(service, "jwtExpiration", expirationMillis);
        return service;
    }

    private static String base64(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }
}
