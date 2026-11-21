package com.socialflux.profile.storage;

import com.socialflux.media.MediaKeys;

import java.time.Clock;
import java.util.regex.Pattern;

/** Chaves de avatar são geradas SEMPRE pelo servidor; o cliente nunca informa nome ou caminho. */
public final class AvatarKeys {

    private static final Pattern VALID =
            Pattern.compile("^av_[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.jpg$");

    private AvatarKeys() {
    }

    public static String newKey(Clock clock) {
        return MediaKeys.newKey(MediaKeys.AVATAR_PREFIX, "jpg", clock);
    }

    /** Avatares são sempre JPEG: a chave precisa ser exatamente {@code av_<uuid>.jpg}. */
    public static boolean isValid(String key) {
        return key != null && VALID.matcher(key).matches();
    }

    /** URL pública (imutável e cacheável) de uma chave. */
    public static String urlOf(String key) {
        return key == null ? null : "/media/avatars/" + key;
    }
}
