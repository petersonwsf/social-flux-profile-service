package com.socialflux.profile;

import com.socialflux.profile.support.AbstractProfileTest;
import com.socialflux.profile.support.Http.Response;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AvatarTest extends AbstractProfileTest {

    private static final String KEY_FORMAT = "av_[0-9a-f-]{36}\\.jpg";
    private static final String BUCKET = "avatars";

    private static byte[] png(int width, int height, boolean alpha) throws IOException {
        BufferedImage image = new BufferedImage(width, height, alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(200, 30, 30, alpha ? 128 : 255));
        g.fillRect(0, 0, width, height);
        g.setColor(Color.BLUE);
        g.fillOval(width / 4, height / 4, width / 2, height / 2);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private Response upload(Account account, String pathId, byte[] content, String filename, Map<String, String> fields) {
        return http().multipart("PATCH", "/api/img/usuarios/" + pathId, account.token(), "foto_perfil", filename,
                "image/png", content, fields);
    }

    @Test
    void uploadsAreReEncodedToASquareJpegServedWithSafeHeaders() throws Exception {
        Account account = createActive(uniqueUsername());

        Response response = upload(account, String.valueOf(account.id()), png(300, 200, true), "minha.png", Map.of());
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        String key = (String) response.json().get("foto_perfil");
        assertThat(key).matches(KEY_FORMAT);
        assertThat(response.json().get("foto_url")).isEqualTo("/media/avatars/" + key);
        assertThat(mediaStorage.exists(BUCKET, key)).as("gravado no bucket de avatares").isTrue();

        // Entrega pública, com tipo fixo, nosniff e cache imutável (o nome muda a cada troca de foto)
        Response media = http().get("/media/avatars/" + key, null);
        assertThat(media.status()).isEqualTo(200);
        assertThat(media.header("Content-Type")).isEqualTo("image/jpeg");
        assertThat(media.header("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(media.header("Cache-Control")).contains("immutable").contains("public");
        BufferedImage served = ImageIO.read(new ByteArrayInputStream(media.bytes()));
        assertThat(served.getWidth()).isEqualTo(256);
        assertThat(served.getHeight()).isEqualTo(256);
        assertThat(media.bytes()[0] & 0xFF).isEqualTo(0xFF);   // magic bytes de JPEG: o PNG original não é guardado

        // Outro usuário vê a foto (perfil público)
        Account viewer = createActive(uniqueUsername());
        assertThat(http().get("/api/usuarios?username=" + account.username(), viewer.token()).json().get("foto_perfil"))
                .isEqualTo(key);
    }

    @Test
    void replacingThePhotoDeletesTheOldFileAndIgnoresTheClientSuppliedOldName() throws Exception {
        Account account = createActive(uniqueUsername());
        String victim = "av_" + UUID.randomUUID() + ".jpg";                    // objeto de OUTRA pessoa no mesmo bucket
        mediaStorage.put(BUCKET, victim, new byte[]{1, 2, 3}, "image/jpeg");

        String first = (String) upload(account, String.valueOf(account.id()), png(64, 64, false), "a.png", Map.of())
                .json().get("foto_perfil");
        // O legado apagava qualquer arquivo indicado em `foto_antiga` (path traversal). Aqui o campo é ignorado.
        Response second = upload(account, String.valueOf(account.id()), png(80, 80, false), "b.png",
                Map.of("foto_antiga", victim, "foto_antiga2", "../" + first));
        assertThat(second.status()).isEqualTo(200);
        String secondKey = (String) second.json().get("foto_perfil");

        assertThat(secondKey).isNotEqualTo(first);
        assertThat(mediaStorage.exists(BUCKET, victim)).as("objeto indicado pelo cliente não pode ser tocado").isTrue();
        assertThat(mediaStorage.exists(BUCKET, first)).as("a foto anterior é removida").isFalse();
        assertThat(mediaStorage.exists(BUCKET, secondKey)).isTrue();
        assertThat(http().get("/media/avatars/" + first, null).status()).isEqualTo(404);
    }

    @Test
    void onlyTheOwnerCanChangeThePhoto() throws Exception {
        Account a = createActive(uniqueUsername());
        Account b = createActive(uniqueUsername());

        Response attack = upload(a, String.valueOf(b.id()), png(64, 64, false), "x.png", Map.of());
        assertThat(attack.status()).isEqualTo(403);
        assertThat(http().get("/api/usuarios/me", b.token()).json().get("foto_perfil")).isNull();
        assertThat(http().multipart("PATCH", "/api/img/usuarios/" + a.id(), null, "foto_perfil", "x.png",
                "image/png", png(64, 64, false), Map.of()).status()).isEqualTo(401);
    }

    @Test
    void rejectsFakeCorruptAndOversizedImages() throws Exception {
        Account account = createActive(uniqueUsername());
        String id = String.valueOf(account.id());

        // Texto com extensão/Content-Type de imagem (o legado só olhava a extensão)
        Response fake = upload(account, id, text("<html><script>alert(1)</script></html>"), "x.png", Map.of());
        assertThat(fake.status()).isEqualTo(400);
        assertThat(fake.json().get("codigo")).isEqualTo("invalid_image");

        // Bytes mágicos de JPEG com conteúdo corrompido
        byte[] corrupt = new byte[2048];
        corrupt[0] = (byte) 0xFF;
        corrupt[1] = (byte) 0xD8;
        corrupt[2] = (byte) 0xFF;
        assertThat(upload(account, id, corrupt, "x.jpg", Map.of()).json().get("codigo")).isEqualTo("invalid_image");

        // GIF (formato não permitido)
        assertThat(upload(account, id, text("GIF89a......"), "x.gif", Map.of()).status()).isEqualTo(400);

        // "Image bomb": arquivo minúsculo que expande para 25 megapixels (limite: 16 MP)
        Response bomb = upload(account, id, png(5000, 5000, false), "bomb.png", Map.of());
        assertThat(bomb.status()).isEqualTo(400);
        assertThat(bomb.json().get("codigo")).isEqualTo("image_too_large");

        // Parte ausente
        Response missing = http().multipart("PATCH", "/api/img/usuarios/" + id, account.token(), "foto_perfil", "x.png",
                "image/png", null, Map.of());
        assertThat(missing.status()).isEqualTo(400);

        assertThat(http().get("/api/usuarios/me", account.token()).json().get("foto_perfil")).isNull();
    }

    @Test
    void photoTakenStandingUpIsStoredUpright() throws Exception {
        Account account = createActive(uniqueUsername());
        // A câmera gravou "deitada" (40×80) com orientação EXIF 6 (girar 90° horário): a metade esquerda vira o topo.
        BufferedImage stored = new BufferedImage(40, 80, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = stored.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(0, 0, 20, 80);
        g.setColor(Color.BLUE);
        g.fillRect(20, 0, 20, 80);
        g.dispose();
        ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
        ImageIO.write(stored, "jpeg", jpeg);

        Response response = upload(account, String.valueOf(account.id()), withOrientation(jpeg.toByteArray(), 6),
                "foto.jpg", Map.of());
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        String key = (String) response.json().get("foto_perfil");
        BufferedImage served = ImageIO.read(new ByteArrayInputStream(http().get("/media/avatars/" + key, null).bytes()));
        assertThat(isRed(served.getRGB(128, 20))).as("topo vermelho").isTrue();
        assertThat(isRed(served.getRGB(128, 235))).as("base azul").isFalse();
    }

    @Test
    void acceptsWebpPhotos() throws Exception {
        Account account = createActive(uniqueUsername());
        byte[] webp;
        try (var in = AvatarTest.class.getResourceAsStream("/fixtures/foto.webp")) {
            webp = in.readAllBytes();
        }
        Response response = upload(account, String.valueOf(account.id()), webp, "foto.webp", Map.of());
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        byte[] served = http().get("/media/avatars/" + response.json().get("foto_perfil"), null).bytes();
        assertThat(served[0] & 0xFF).isEqualTo(0xFF);   // sempre JPEG
    }

    private static boolean isRed(int rgb) {
        return ((rgb >> 16) & 0xFF) > 150 && (rgb & 0xFF) < 110;
    }

    /** Insere um segmento APP1/EXIF mínimo (TIFF big-endian, só a tag de orientação) logo depois do SOI. */
    private static byte[] withOrientation(byte[] jpeg, int orientation) {
        byte[] tiff = {'M', 'M', 0, 42, 0, 0, 0, 8, 0, 1, 0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, (byte) orientation, 0, 0,
                0, 0, 0, 0};
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0xFF);
        out.write(0xD8);
        int length = 2 + 6 + tiff.length;
        out.writeBytes(new byte[]{(byte) 0xFF, (byte) 0xE1, (byte) (length >> 8), (byte) length, 'E', 'x', 'i', 'f', 0, 0});
        out.writeBytes(tiff);
        out.write(jpeg, 2, jpeg.length - 2);
        return out.toByteArray();
    }

    @Test
    void mediaEndpointOnlyServesServerGeneratedKeys() {
        assertThat(http().get("/media/avatars/av_x.jpg", null).status()).isEqualTo(404);
        assertThat(http().get("/media/avatars/" + "av_" + UUID.randomUUID() + ".jpg", null).status()).isEqualTo(404);
        assertThat(http().get("/media/avatars/..%2F..%2Fetc%2Fpasswd", null).status()).isIn(400, 404);
        assertThat(http().get("/media/avatars/passwd", null).status()).isEqualTo(404);
    }
}
