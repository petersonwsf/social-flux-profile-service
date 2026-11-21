package com.socialflux.profile.service;

import com.socialflux.events.Outbox;
import com.socialflux.profile.domain.Profile;
import com.socialflux.profile.domain.ProfileRepository;
import com.socialflux.profile.domain.ProfileStatus;
import com.socialflux.profile.storage.AvatarKeys;
import com.socialflux.profile.storage.StorageService;
import com.socialflux.profile.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Regras do domínio Perfil. Toda mudança de estado grava o evento correspondente no outbox na mesma transação.
 * A identidade de quem altera vem SEMPRE do token (parâmetro {@code accountId}); nenhum id do cliente é autoridade.
 */
@Service
public class ProfileService {

    private static final Logger log = LoggerFactory.getLogger(ProfileService.class);

    public static final String CREATED = "profile.created.v1";
    public static final String UPDATED = "profile.updated.v1";
    public static final String PRIVACY_CHANGED = "profile.privacy-changed.v1";
    public static final String DELETED = "profile.deleted.v1";

    public record CreateCommand(String username, String nome, String telefone, String sexo, LocalDate dataNascimento) {
    }

    public record CreateResult(Profile profile, boolean created) {
    }

    /** Campos {@code null} significam "não alterar". Biografia vazia limpa o campo. */
    public record UpdateCommand(String username, String nome, String biografia, Boolean privado) {
    }

    private final ProfileRepository profiles;
    private final Outbox outbox;
    private final StorageService storage;
    private final Clock clock;

    public ProfileService(ProfileRepository profiles, Outbox outbox, StorageService storage, Clock clock) {
        this.profiles = profiles;
        this.outbox = outbox;
        this.storage = storage;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ criação (chamada pelo auth)

    /** Idempotente: repetir a chamada com os mesmos dados devolve o perfil já criado. */
    @Transactional
    public CreateResult create(UUID id, CreateCommand command) {
        String username = ProfileRules.normalizeUsername(command.username());
        ProfileRules.validateUsername(username);
        String displayName = ProfileRules.validateDisplayName(command.nome());
        String phone = ProfileRules.validatePhone(command.telefone());
        String sex = ProfileRules.validateSex(command.sexo());
        LocalDate birthDate = ProfileRules.validateBirthDate(command.dataNascimento(), clock);

        Optional<Profile> existing = profiles.findById(id);
        if (existing.isPresent()) {
            Profile profile = existing.get();
            if (profile.getStatus() == ProfileStatus.PENDING && profile.getUsername().equals(username)) {
                return new CreateResult(profile, false);
            }
            throw ApiException.conflict("profile_exists", "Já existe um perfil para essa conta");
        }
        if (profiles.existsByUsername(username)) {
            throw usernameTaken("Já existe um usuário com esse username");
        }
        Profile profile = new Profile(id, username, displayName, phone, sex, birthDate, clock.instant());
        try {
            profiles.saveAndFlush(profile);
        } catch (DataIntegrityViolationException e) {
            throw usernameTaken("Já existe um usuário com esse username");
        }
        return new CreateResult(profile, true);
    }

    /** Compensação do cadastro: só remove perfis que nunca foram confirmados. */
    @Transactional
    public void discardPending(UUID id) {
        profiles.findById(id).ifPresent(profile -> {
            if (profile.getStatus() != ProfileStatus.PENDING) {
                throw ApiException.conflict("profile_active", "O perfil já foi ativado");
            }
            profiles.delete(profile);
        });
    }

    /** Reação a {@code account.registered.v1}. Idempotente. */
    @Transactional
    public void activate(UUID id) {
        Optional<Profile> found = profiles.findById(id);
        if (found.isEmpty()) {
            log.warn("account.registered recebido para um perfil inexistente ({})", id);
            return;
        }
        Profile profile = found.get();
        if (profile.getStatus() != ProfileStatus.PENDING) {
            return;
        }
        Instant now = clock.instant();
        profile.setStatus(ProfileStatus.ACTIVE);
        profile.setConfirmedAt(now);
        profile.touch(now);
        profiles.save(profile);
        outbox.add(CREATED, profile.getId(), profile.getVersion(), snapshot(profile));
    }

    /** Reação a {@code account.deleted.v1}: apaga os dados pessoais e libera o username. Idempotente. */
    @Transactional
    public void deleteAccountData(UUID id) {
        Optional<Profile> found = profiles.findById(id);
        if (found.isEmpty() || found.get().getStatus() == ProfileStatus.DELETED) {
            return;
        }
        Profile profile = found.get();
        boolean wasProjected = profile.getStatus() == ProfileStatus.ACTIVE;
        String oldAvatar = profile.getAvatarKey();

        profile.setStatus(ProfileStatus.DELETED);
        profile.setUsername("deleted." + id.toString().replace("-", "").substring(0, 20));
        profile.setDisplayName("Conta excluída");
        profile.setBio(null);
        profile.setAvatarKey(null);
        profile.setPhone(null);
        profile.setSex(null);
        profile.setBirthDate(null);
        profile.setPrivate(false);
        profile.touch(clock.instant());
        profiles.save(profile);

        // Perfis que nunca chegaram a ser publicados (PENDING) não têm o que desfazer nos consumidores.
        if (wasProjected) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("accountId", id.toString());
            outbox.add(DELETED, id, profile.getVersion(), data);
        }
        deleteFileAfterCommit(oldAvatar);
    }

    // ------------------------------------------------------------------ leitura

    @Transactional(readOnly = true)
    public Optional<Profile> findOwn(UUID accountId) {
        return profiles.findById(accountId).filter(p -> p.getStatus() != ProfileStatus.DELETED);
    }

    /** Perfil visível para {@code viewer}: ativo, ou o próprio (mesmo ainda pendente). */
    @Transactional(readOnly = true)
    public Optional<Profile> findVisible(Optional<Profile> candidate, UUID viewer) {
        return candidate.filter(p -> p.getStatus() == ProfileStatus.ACTIVE
                || (p.getId().equals(viewer) && p.getStatus() == ProfileStatus.PENDING));
    }

    @Transactional(readOnly = true)
    public Optional<Profile> byUsername(String username) {
        return profiles.findByUsername(ProfileRules.normalizeUsername(username));
    }

    @Transactional(readOnly = true)
    public Optional<Profile> byId(UUID id) {
        return profiles.findById(id);
    }

    @Transactional(readOnly = true)
    public List<Profile> activeByIds(Collection<UUID> ids) {
        return profiles.findByIdInAndStatus(ids, ProfileStatus.ACTIVE);
    }

    @Transactional(readOnly = true)
    public List<Profile> pageAfter(Instant afterTs, UUID afterId, int limit) {
        return profiles.pageAfter(afterTs, afterId, limit);
    }

    @Transactional(readOnly = true)
    public List<Profile> search(String raw) {
        String query = raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
        if (query.length() < 2 || query.length() > 50) {
            throw ApiException.badRequest("invalid_search", "Digite de 2 a 50 caracteres para pesquisar");
        }
        String escaped = query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return profiles.search(query, escaped + "%", "%" + escaped + "%", 10);
    }

    // ------------------------------------------------------------------ edição

    @Transactional
    public Profile update(UUID accountId, UpdateCommand command) {
        Profile profile = findOwn(accountId).orElseThrow(() -> ApiException.notFound("Usuário não encontrado"));
        boolean changed = false;
        boolean privacyChanged = false;

        if (command.username() != null) {
            String username = ProfileRules.normalizeUsername(command.username());
            if (!username.equals(profile.getUsername())) {
                ProfileRules.validateUsername(username);
                if (profiles.existsByUsername(username)) {
                    throw usernameTaken("Username já está em uso, tente outro!");
                }
                profile.setUsername(username);
                changed = true;
            }
        }
        if (command.nome() != null) {
            String name = ProfileRules.validateDisplayName(command.nome());
            if (!name.equals(profile.getDisplayName())) {
                profile.setDisplayName(name);
                changed = true;
            }
        }
        if (command.biografia() != null) {
            String bio = ProfileRules.validateBio(command.biografia());
            if (!java.util.Objects.equals(bio, profile.getBio())) {
                profile.setBio(bio);
                changed = true;
            }
        }
        if (command.privado() != null && command.privado() != profile.isPrivate()) {
            profile.setPrivate(command.privado());
            privacyChanged = true;
            changed = true;
        }
        if (!changed) {
            return profile;
        }
        profile.touch(clock.instant());
        try {
            profiles.saveAndFlush(profile);
        } catch (DataIntegrityViolationException e) {
            throw usernameTaken("Username já está em uso, tente outro!");
        }
        publishChange(profile, privacyChanged);
        return profile;
    }

    /** Troca a foto. A chave nova já foi gravada no storage; a antiga é apagada depois do commit. */
    @Transactional
    public Profile replaceAvatar(UUID accountId, String newKey) {
        Profile profile = findOwn(accountId).orElseThrow(() -> ApiException.notFound("Usuário não encontrado"));
        String oldKey = profile.getAvatarKey();
        profile.setAvatarKey(newKey);
        profile.touch(clock.instant());
        profiles.saveAndFlush(profile);
        publishChange(profile, false);
        deleteFileAfterCommit(oldKey);
        return profile;
    }

    // ------------------------------------------------------------------ helpers

    private void publishChange(Profile profile, boolean privacyChanged) {
        if (profile.getStatus() != ProfileStatus.ACTIVE) {
            return; // pendente: o evento "created" levará o estado completo na ativação
        }
        outbox.add(UPDATED, profile.getId(), profile.getVersion(), snapshot(profile));
        if (privacyChanged) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("accountId", profile.getId().toString());
            data.put("isPrivate", profile.isPrivate());
            outbox.add(PRIVACY_CHANGED, profile.getId(), profile.getVersion(), data);
        }
    }

    /** Estado PÚBLICO do perfil para os eventos. Nunca inclui telefone, sexo, nascimento ou e-mail. */
    static Map<String, Object> snapshot(Profile p) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("accountId", p.getId().toString());
        data.put("username", p.getUsername());
        data.put("displayName", p.getDisplayName());
        data.put("bio", p.getBio());
        data.put("avatarKey", p.getAvatarKey());
        data.put("isPrivate", p.isPrivate());
        data.put("createdAt", p.getCreatedAt().toString());
        return data;
    }

    private static ApiException usernameTaken(String message) {
        return ApiException.conflict("username_taken", message);
    }

    private void deleteFileAfterCommit(String key) {
        if (key == null || !AvatarKeys.isValid(key)) {
            return;
        }
        Runnable action = () -> {
            try {
                storage.delete(key);
            } catch (RuntimeException e) {
                log.warn("Não foi possível remover o avatar {}: {}", key, e.toString());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }
}
