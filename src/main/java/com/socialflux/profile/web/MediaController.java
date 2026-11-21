package com.socialflux.profile.web;

import com.socialflux.profile.storage.AvatarKeys;
import com.socialflux.profile.storage.StorageService;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/**
 * Entrega os avatares (D8: públicos). A chave só é aceita se for exatamente o formato gerado pelo servidor; o conteúdo
 * é sempre JPEG re-codificado, servido com tipo fixo, {@code nosniff} e cache imutável (o nome muda a cada troca de foto).
 */
@RestController
public class MediaController {

    private final StorageService storage;

    public MediaController(StorageService storage) {
        this.storage = storage;
    }

    @GetMapping("/media/avatars/{key}")
    ResponseEntity<byte[]> avatar(@PathVariable String key) {
        if (!AvatarKeys.isValid(key)) {
            return ResponseEntity.notFound().build();
        }
        return storage.get(key)
                .map(bytes -> ResponseEntity.ok()
                        .contentType(MediaType.IMAGE_JPEG)
                        .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
                        .header("X-Content-Type-Options", "nosniff")
                        .body(bytes))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
