package com.socialflux.profile.security;

import com.socialflux.profile.config.ProfileProperties;
import com.socialflux.profile.web.ApiError;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Resource server puro (só {@code Authorization: Bearer}). Padrão: NEGAR TUDO e liberar explicitamente.
 * <ul>
 *   <li>{@code /api/**}: token de usuário ({@code ROLE_USER});</li>
 *   <li>{@code /internal/**}: token de serviço com o escopo adequado — nunca é roteado pelo gateway público;</li>
 *   <li>{@code /media/avatars/**} (GET) e {@code /actuator/health}: públicos.</li>
 * </ul>
 * Revogação imediata (logout geral, troca de senha) é aplicada pelo gateway (forward_auth no authentication_service);
 * aqui o limite é o fim do token (2 h), por isso o gateway é obrigatório na frente deste serviço.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtDecoder decoder, JwtAuthorities authorities,
                                            ProfileProperties properties) throws Exception {
        http
                .csrf(csrf -> csrf.disable())          // substituído por OriginCheckFilter (sem sessão no servidor)
                .cors(cors -> cors.disable())          // mesma origem via Caddy
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/media/avatars/*").permitAll()
                        .requestMatchers(HttpMethod.GET, "/internal/**").hasAuthority("SCOPE_profiles:read")
                        .requestMatchers(HttpMethod.POST, "/internal/profiles/batch-get").hasAuthority("SCOPE_profiles:read")
                        .requestMatchers("/internal/**").hasAuthority("SCOPE_profiles:write")
                        .requestMatchers("/api/**").hasRole("USER")
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> jwt.decoder(decoder).jwtAuthenticationConverter(authorities))
                        .authenticationEntryPoint(unauthorized())
                        .accessDeniedHandler(forbidden()))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(unauthorized())
                        .accessDeniedHandler(forbidden()))
                .headers(headers -> headers
                        .cacheControl(Customizer.withDefaults())
                        .contentTypeOptions(Customizer.withDefaults())
                        .frameOptions(frame -> frame.deny()))
                .addFilterBefore(new OriginCheckFilter(properties), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    private static AuthenticationEntryPoint unauthorized() {
        return (request, response, exception) -> write(response, HttpServletResponse.SC_UNAUTHORIZED,
                ApiError.of("unauthorized", "Não autenticado"));
    }

    private static AccessDeniedHandler forbidden() {
        return (request, response, exception) -> write(response, HttpServletResponse.SC_FORBIDDEN,
                ApiError.of("forbidden", "Acesso negado"));
    }

    private static void write(HttpServletResponse response, int status, ApiError error) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(error.toJson());
    }
}
