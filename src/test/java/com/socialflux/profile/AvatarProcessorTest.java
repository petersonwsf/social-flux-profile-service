package com.socialflux.profile;

import com.socialflux.media.MediaProperties;
import com.socialflux.media.ProcessingLimiter;
import com.socialflux.media.image.ImageProcessor;
import com.socialflux.media.storage.InMemoryMediaStorage;
import com.socialflux.profile.config.ProfileProperties;
import com.socialflux.profile.storage.AvatarKeys;
import com.socialflux.profile.storage.AvatarProcessor;
import com.socialflux.profile.storage.BucketAvatarStorage;
import com.socialflux.profile.web.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AvatarProcessorTest {

    private static ProfileProperties properties() {
        return new ProfileProperties(
                new ProfileProperties.Security("http://x/jwks", "iss", "aud", "svc", Duration.ofSeconds(60)),
                List.of(), new ProfileProperties.Avatars("avatars"));
    }

    private static AvatarProcessor processor(long maxBytes) {
        return new AvatarProcessor(new ImageProcessor(
                new MediaProperties.Image(DataSize.ofBytes(maxBytes), 1_000_000, 1600),
                new ProcessingLimiter(1, Duration.ofSeconds(1))));
    }

    @Test
    void rejectsFilesOverTheByteLimitBeforeDecoding() {
        AvatarProcessor processor = processor(1024);
        byte[] big = new byte[2048];
        big[0] = (byte) 0x89;
        assertThatThrownBy(() -> processor.process(big))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("file_too_large"));
        assertThatThrownBy(() -> processor.process(new byte[0]))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("missing_file"));
        assertThatThrownBy(() -> processor.process("GIF89a......".getBytes()))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("invalid_image"));
    }

    @Test
    void storageNeverAcceptsKeysOutsideTheServerFormat() {
        InMemoryMediaStorage media = new InMemoryMediaStorage(Clock.systemUTC());
        BucketAvatarStorage storage = new BucketAvatarStorage(media, properties());

        for (String malicious : List.of("../x.jpg", "..\\x.jpg", "/etc/passwd", "av_../x.jpg", "po_" + UUID.randomUUID() + ".jpg",
                "av_" + UUID.randomUUID() + ".png", "av_" + UUID.randomUUID() + ".jpg/../../x")) {
            assertThat(storage.get(malicious)).as(malicious).isEmpty();
            storage.delete(malicious);                                    // silencioso, sem efeito
            assertThatThrownBy(() -> storage.put(malicious, new byte[]{1}))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(media.count("avatars")).isZero();

        String key = AvatarKeys.newKey(Clock.systemUTC());
        storage.put(key, new byte[]{1, 2, 3});
        assertThat(media.get("avatars", key)).hasValueSatisfying(o -> assertThat(o.contentType()).isEqualTo("image/jpeg"));
        assertThat(storage.get(key)).contains(new byte[]{1, 2, 3});
        storage.delete(key);
        assertThat(storage.get(key)).isEmpty();
    }

    @Test
    void keysFollowTheServerGeneratedFormat() {
        String key = AvatarKeys.newKey(Clock.systemUTC());
        assertThat(key).matches("^av_[0-9a-f-]{36}\\.jpg$");
        assertThat(AvatarKeys.isValid(key)).isTrue();
        assertThat(AvatarKeys.isValid(null)).isFalse();
        assertThat(AvatarKeys.isValid("av_x.jpg")).isFalse();
        assertThat(AvatarKeys.isValid(key + "/..")).isFalse();
    }
}
