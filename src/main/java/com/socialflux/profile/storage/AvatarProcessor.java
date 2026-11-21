package com.socialflux.profile.storage;

import com.socialflux.media.MediaException;
import com.socialflux.media.image.ImageProcessor;
import com.socialflux.profile.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Valida e RE-CODIFICA a foto de perfil com o {@link ImageProcessor} da biblioteca de mídia: PNG, JPEG ou WebP, orientação
 * EXIF corrigida, recorte quadrado central em JPEG 256×256 sem metadados. Aqui só se traduz a recusa para os códigos
 * públicos que o perfil já usava ({@code missing_file}, {@code file_too_large}, {@code image_too_large}, {@code invalid_image}).
 */
@Component
public class AvatarProcessor {

    public static final int SIZE = 256;

    private final ImageProcessor images;

    public AvatarProcessor(ImageProcessor images) {
        this.images = images;
    }

    public byte[] process(byte[] data) {
        try {
            return images.squareJpeg(data, SIZE).bytes();
        } catch (MediaException e) {
            throw translate(e);
        }
    }

    private static ApiException translate(MediaException e) {
        return switch (e.reason()) {
            case EMPTY -> ApiException.badRequest("missing_file", "Arquivo de imagem vazio");
            case TOO_LARGE -> new ApiException(HttpStatus.CONTENT_TOO_LARGE, "file_too_large",
                    "A imagem excede o tamanho máximo permitido");
            case DIMENSIONS_TOO_LARGE -> ApiException.badRequest("image_too_large", "A imagem tem dimensões grandes demais");
            case BUSY -> new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "servidor_ocupado",
                    "Servidor ocupado processando imagens. Tente novamente em instantes.");
            case INVALID, ANIMATED, GIF_INVALID -> ApiException.badRequest("invalid_image",
                    "Formato de imagem não suportado (use PNG, JPEG ou WebP)");
        };
    }
}
