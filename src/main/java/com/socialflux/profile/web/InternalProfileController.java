package com.socialflux.profile.web;

import com.socialflux.profile.domain.Profile;
import com.socialflux.profile.service.ProfileService;
import com.socialflux.profile.web.Dtos.BatchRequest;
import com.socialflux.profile.web.Dtos.CreateProfileRequest;
import com.socialflux.profile.web.Dtos.CreateProfileResponse;
import com.socialflux.profile.web.Dtos.ProfilePage;
import com.socialflux.profile.web.Dtos.PublicProfile;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * API entre serviços. Exige token de SERVIÇO (escopos {@code profiles:write}/{@code profiles:read}) e nunca é roteada pelo
 * gateway público. Só devolve a visão pública (sem dados pessoais).
 */
@RestController
@RequestMapping("/internal/profiles")
public class InternalProfileController {

    private static final int MAX_PAGE = 200;

    private final ProfileService profiles;

    public InternalProfileController(ProfileService profiles) {
        this.profiles = profiles;
    }

    /** Criação idempotente no cadastro: o UUID é o da conta, gerado pelo authentication_service. */
    @PutMapping("/{id}")
    ResponseEntity<CreateProfileResponse> create(@PathVariable UUID id, @Valid @RequestBody CreateProfileRequest body) {
        ProfileService.CreateResult result = profiles.create(id, new ProfileService.CreateCommand(
                body.username(), body.nome(), body.telefone(), body.sexo(), body.data_nascimento()));
        Profile profile = result.profile();
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(new CreateProfileResponse(profile.getId().toString(), result.created()));
    }

    /** Compensação: remove um perfil que ainda não foi confirmado pelo auth. */
    @DeleteMapping("/{id}")
    ResponseEntity<Void> discard(@PathVariable UUID id) {
        profiles.discardPending(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}")
    PublicProfile get(@PathVariable UUID id) {
        return profiles.byId(id)
                .filter(p -> p.getStatus() == com.socialflux.profile.domain.ProfileStatus.ACTIVE)
                .map(PublicProfile::of)
                .orElseThrow(() -> ApiException.notFound("Usuário não encontrado"));
    }

    @PostMapping("/batch-get")
    List<PublicProfile> batchGet(@Valid @RequestBody BatchRequest body) {
        return profiles.activeByIds(body.ids()).stream().map(PublicProfile::of).toList();
    }

    /**
     * Carga inicial de um novo consumidor (cópia local). Paginação por cursor estável {@code <updatedAt>|<uuid>};
     * depois da carga o consumidor se mantém atualizado pelos eventos {@code profile.*}.
     */
    @GetMapping
    ProfilePage bootstrap(@RequestParam(required = false) String cursor,
                          @RequestParam(defaultValue = "100") int limit) {
        int size = Math.max(1, Math.min(limit, MAX_PAGE));
        Instant afterTs = Instant.EPOCH;
        UUID afterId = new UUID(0, 0);
        if (cursor != null && !cursor.isBlank()) {
            try {
                String[] parts = cursor.split("\\|", 2);
                afterTs = Instant.parse(parts[0]);
                afterId = UUID.fromString(parts[1]);
            } catch (RuntimeException e) {
                throw ApiException.badRequest("invalid_cursor", "Cursor inválido");
            }
        }
        List<Profile> page = profiles.pageAfter(afterTs, afterId, size);
        String next = page.size() < size ? null
                : page.get(page.size() - 1).getUpdatedAt() + "|" + page.get(page.size() - 1).getId();
        return new ProfilePage(page.stream().map(PublicProfile::of).toList(), next);
    }
}
