package com.socialflux.profile.web;

import com.socialflux.profile.domain.Profile;
import com.socialflux.profile.storage.AvatarKeys;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** DTOs da API. Os nomes de campo em português/snake_case acompanham o contrato que o frontend já usa. */
public final class Dtos {

    private Dtos() {
    }

    /** Visão pública: o que QUALQUER usuário autenticado pode ver. Nunca contém dados pessoais. */
    public record PublicProfile(
            String id,
            String username,
            String nome,
            String biografia,
            String foto_perfil,
            String foto_url,
            Boolean privado) {

        public static PublicProfile of(Profile p) {
            return new PublicProfile(p.getId().toString(), p.getUsername(), p.getDisplayName(),
                    p.getBio(), p.getAvatarKey(), AvatarKeys.urlOf(p.getAvatarKey()), p.isPrivate());
        }
    }

    /** Visão do dono: pública + dados pessoais que só ele enxerga. */
    public record OwnerProfile(
            String id,
            String username,
            String nome,
            String biografia,
            String foto_perfil,
            String foto_url,
            Boolean privado,
            String telefone,
            String sexo,
            LocalDate data_nascimento,
            LocalDate data_criacao) {

        public static OwnerProfile of(Profile p) {
            return new OwnerProfile(p.getId().toString(), p.getUsername(), p.getDisplayName(),
                    p.getBio(), p.getAvatarKey(), AvatarKeys.urlOf(p.getAvatarKey()), p.isPrivate(),
                    p.getPhone(), p.getSex(), p.getBirthDate(),
                    p.getCreatedAt().atZone(java.time.ZoneOffset.UTC).toLocalDate());
        }
    }

    /** Campos opcionais: ausente = não altera. O {@code id} do corpo (que o frontend ainda envia) é ignorado. */
    public record UpdateRequest(
            @Size(max = 60) String username,
            @Size(max = 120) String nome,
            @Size(max = 1000) String biografia,
            Boolean privado) {
    }

    public record CreateProfileRequest(
            @NotBlank(message = "Informe o username") @Size(max = 60) String username,
            @NotBlank(message = "Informe o nome") @Size(max = 120) String nome,
            @NotBlank(message = "Informe o telefone") @Size(max = 40) String telefone,
            @NotBlank(message = "Selecione uma opção de sexo") String sexo,
            @NotNull(message = "Informe a data de nascimento") LocalDate data_nascimento) {
    }

    public record CreateProfileResponse(String accountId, boolean created) {
    }

    public record BatchRequest(@NotNull @Size(max = 200) List<UUID> ids) {
    }

    public record ProfilePage(List<PublicProfile> items, String nextCursor) {
    }
}
