package com.socialflux.profile.storage;

import com.socialflux.media.ProcessedMedia;
import com.socialflux.media.storage.MediaStorage;
import com.socialflux.profile.config.ProfileProperties;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Avatares no bucket {@code profile.avatars.bucket} do armazenamento de mídia (RustFS). Toda chave é conferida contra
 * {@link AvatarKeys} antes de chegar ao armazenamento: nenhuma entrada do cliente vira nome de objeto.
 */
@Component
public class BucketAvatarStorage implements StorageService {

    private final MediaStorage storage;
    private final String bucket;

    public BucketAvatarStorage(MediaStorage storage, ProfileProperties properties) {
        this.storage = storage;
        this.bucket = properties.avatars().bucket();
    }

    @Override
    public void put(String key, byte[] data) {
        storage.put(bucket, requireValid(key), data, ProcessedMedia.JPEG);
    }

    @Override
    public Optional<byte[]> get(String key) {
        if (!AvatarKeys.isValid(key)) {
            return Optional.empty();
        }
        return storage.get(bucket, key).map(MediaStorage.StoredObject::bytes);
    }

    @Override
    public void delete(String key) {
        if (AvatarKeys.isValid(key)) {
            storage.delete(bucket, key);
        }
    }

    private static String requireValid(String key) {
        if (!AvatarKeys.isValid(key)) {
            throw new IllegalArgumentException("Chave de avatar inválida");
        }
        return key;
    }
}
