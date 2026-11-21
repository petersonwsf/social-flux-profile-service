package com.socialflux.profile.security;

import com.socialflux.profile.config.ProfileProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.time.Clock;
import java.util.List;

/**
 * Valida o JWT RS256 do authentication_service usando só a chave PÚBLICA publicada no JWKS (recarregada quando
 * aparece um {@code kid} novo, o que torna a rotação de chaves transparente). Só RS256: {@code alg=none} e HS256 são recusados.
 */
@Configuration
public class JwtDecoderConfig {

    @Bean
    JwtDecoder jwtDecoder(ProfileProperties properties, Clock clock) {
        ProfileProperties.Security security = properties.security();
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(security.jwksUri())
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .build();

        JwtTimestampValidator timestamp = new JwtTimestampValidator(security.clockSkew());
        timestamp.setClock(clock);
        // Aceita tokens de usuário (aud = audience) e tokens de serviço (aud = serviceAudience);
        // quem pode usar o quê é decidido pelo conversor de autoridades e pelas regras de rota.
        List<String> accepted = List.of(security.audience(), security.serviceAudience());
        OAuth2TokenValidator<Jwt> audience = token -> token.getAudience() != null
                && token.getAudience().stream().anyMatch(accepted::contains)
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Audiência inválida", null));

        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                timestamp, new JwtIssuerValidator(security.issuer()), audience));
        return decoder;
    }
}
