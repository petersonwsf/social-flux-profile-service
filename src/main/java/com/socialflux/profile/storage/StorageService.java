package com.socialflux.profile.storage;

import java.util.Optional;

/** Armazenamento dos avatares (implementação: {@link BucketAvatarStorage}, sobre o {@code MediaStorage} da biblioteca). */
public interface StorageService {

    void put(String key, byte[] data);

    Optional<byte[]> get(String key);

    /** Silencioso se não existir. */
    void delete(String key);
}
