package com.socialflux.profile.web;

import com.socialflux.profile.domain.Profile;
import com.socialflux.profile.service.AvatarService;
import com.socialflux.profile.service.ProfileService;
import com.socialflux.profile.service.RateLimiter;
import com.socialflux.profile.web.Dtos.OwnerProfile;
import com.socialflux.profile.web.Dtos.PublicProfile;
import com.socialflux.profile.web.Dtos.UpdateRequest;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * API pública do perfil (contrato que o frontend já usa). Regras de segurança desta classe:
 * <ul>
 *   <li>quem está agindo vem SEMPRE do {@code sub} do token — o {@code id} do caminho/corpo só é conferido;</li>
 *   <li>só o dono recebe {@link OwnerProfile} (dados pessoais); os demais recebem {@link PublicProfile};</li>
 *   <li>perfis inexistentes, pendentes de outros ou excluídos respondem igual (404), sem permitir enumeração.</li>
 * </ul>
 */
@RestController
public class ProfileController {

    private final ProfileService profiles;
    private final AvatarService avatars;
    private final RateLimiter rateLimiter;

    public ProfileController(ProfileService profiles, AvatarService avatars, RateLimiter rateLimiter) {
        this.profiles = profiles;
        this.avatars = avatars;
        this.rateLimiter = rateLimiter;
    }

    @GetMapping("/api/usuarios")
    Object find(@RequestParam(required = false) String username, @RequestParam(required = false) String id,
                @AuthenticationPrincipal Jwt jwt) {
        UUID viewer = accountId(jwt);
        Optional<Profile> found;
        if (id != null) {
            found = byIdentifier(id);
        } else if (username != null) {
            found = profiles.byUsername(username);
        } else {
            throw ApiException.badRequest("invalid_search", "Busca inválida");
        }
        Profile profile = profiles.findVisible(found, viewer)
                .orElseThrow(() -> ApiException.notFound("Usuário não encontrado"));
        return view(profile, viewer);
    }

    @GetMapping("/api/usuarios/me")
    OwnerProfile me(@AuthenticationPrincipal Jwt jwt) {
        Profile profile = profiles.findOwn(accountId(jwt))
                .orElseThrow(() -> ApiException.notFound("Usuário não encontrado"));
        return OwnerProfile.of(profile);
    }

    @GetMapping("/api/usuarios/search")
    List<PublicProfile> search(@RequestParam(required = false) String search, @AuthenticationPrincipal Jwt jwt) {
        UUID viewer = accountId(jwt);
        rateLimiter.hit("search:" + viewer, 60, Duration.ofMinutes(1));
        return profiles.search(search).stream().map(PublicProfile::of).toList();
    }

    @PatchMapping("/api/usuarios/{id}")
    OwnerProfile update(@PathVariable String id, @Valid @RequestBody UpdateRequest body, @AuthenticationPrincipal Jwt jwt) {
        UUID self = requireSelf(id, jwt);
        return OwnerProfile.of(profiles.update(self, new ProfileService.UpdateCommand(
                body.username(), body.nome(), body.biografia(), body.privado())));
    }

    /** Troca a foto. O campo legado {@code foto_antiga} é ignorado: o servidor conhece a foto atual. */
    @PatchMapping(value = "/api/img/usuarios/{id}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    OwnerProfile updateAvatar(@PathVariable String id, @RequestPart("foto_perfil") MultipartFile file,
                              @AuthenticationPrincipal Jwt jwt) throws IOException {
        UUID self = requireSelf(id, jwt);
        return OwnerProfile.of(avatars.replace(self, file.getBytes()));
    }

    // ------------------------------------------------------------------ helpers

    /** O id do usuário é o UUID da conta. */
    private Optional<Profile> byIdentifier(String id) {
        try {
            return profiles.byId(UUID.fromString(id));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("invalid_id", "ID Inválido");
        }
    }

    /**
     * O {@code id} do caminho só é aceito se for o UUID do próprio usuário autenticado.
     * Qualquer outro valor é 403 — nunca se age sobre o perfil de outra pessoa.
     */
    private UUID requireSelf(String pathId, Jwt jwt) {
        UUID self = accountId(jwt);
        profiles.findOwn(self).orElseThrow(() -> ApiException.notFound("Usuário não encontrado"));
        boolean matches;
        try {
            matches = UUID.fromString(pathId).equals(self);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("invalid_id", "ID inválido");
        }
        if (!matches) {
            throw ApiException.forbidden("Você só pode alterar o seu próprio perfil");
        }
        return self;
    }

    private static Object view(Profile profile, UUID viewer) {
        return profile.getId().equals(viewer) ? OwnerProfile.of(profile) : PublicProfile.of(profile);
    }

    static UUID accountId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
