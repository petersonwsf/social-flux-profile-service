package com.socialflux.profile.service;

import com.socialflux.profile.domain.Profile;
import com.socialflux.profile.storage.AvatarKeys;
import com.socialflux.profile.storage.AvatarProcessor;
import com.socialflux.profile.storage.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

/** Troca da foto de perfil: valida/re-codifica, grava com nome gerado no servidor e só então atualiza o banco. */
@Service
public class AvatarService {

    private static final Logger log = LoggerFactory.getLogger(AvatarService.class);

    private final AvatarProcessor processor;
    private final StorageService storage;
    private final ProfileService profiles;
    private final RateLimiter rateLimiter;
    private final Clock clock;

    public AvatarService(AvatarProcessor processor, StorageService storage, ProfileService profiles,
                         RateLimiter rateLimiter, Clock clock) {
        this.processor = processor;
        this.storage = storage;
        this.profiles = profiles;
        this.rateLimiter = rateLimiter;
        this.clock = clock;
    }

    public Profile replace(UUID accountId, byte[] upload) {
        rateLimiter.hit("avatar:" + accountId, 10, Duration.ofHours(1));
        byte[] jpeg = processor.process(upload);
        String key = AvatarKeys.newKey(clock);
        storage.put(key, jpeg);
        try {
            return profiles.replaceAvatar(accountId, key);
        } catch (RuntimeException e) {
            try {
                storage.delete(key);
            } catch (RuntimeException cleanup) {
                log.warn("Não foi possível remover o avatar órfão {}: {}", key, cleanup.toString());
            }
            throw e;
        }
    }
}
