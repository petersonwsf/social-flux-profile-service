package com.socialflux.profile.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;

/** Toda a configuração do serviço, vinda de variáveis de ambiente (ver application.yml). Falha rápida se inválida. */
@Validated
@ConfigurationProperties(prefix = "profile")
public record ProfileProperties(
        @Valid @NotNull Security security,
        @DefaultValue List<String> allowedOrigins,
        @Valid @NotNull Avatars avatars) {

    public record Security(
            @NotBlank String jwksUri,
            @NotBlank String issuer,
            @NotBlank String audience,
            @NotBlank String serviceAudience,
            @DefaultValue("PT60S") Duration clockSkew) {
    }

    /** Bucket dos avatares no armazenamento de mídia (limites de imagem ficam em {@code socialflux.media.image.*}). */
    public record Avatars(@NotBlank String bucket) {
    }
}
