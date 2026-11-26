package com.socialflux.profile;

import com.socialflux.audit.AuditMode;
import com.socialflux.audit.InMemoryAuditRecorder;
import com.socialflux.profile.support.AbstractProfileTest;
import com.socialflux.profile.support.Http.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Auditoria central do perfil: só nomes de campos e valores de privacidade, nunca o conteúdo editado. */
class AuditTest extends AbstractProfileTest {

    @Autowired
    InMemoryAuditRecorder audit;

    @BeforeEach
    void clear() {
        audit.clear();
    }

    @Test
    void updateIsAuditedWithFieldNamesOnly() {
        Account ana = createActive(uniqueUsername());
        audit.clear();

        Response response = http().send("PATCH", "/api/usuarios/" + ana.id(),
                "{\"nome\":\"Nome Muito Secreto\",\"biografia\":\"minha bio privada\"}", ana.token());
        assertThat(response.status()).as(response.body()).isEqualTo(200);

        assertThat(audit.recorded("PROFILE_UPDATED")).singleElement().satisfies(item -> {
            assertThat(item.mode()).isEqualTo(AuditMode.IN_TRANSACTION);
            assertThat(item.payload()).containsEntry("actorId", ana.id().toString()).containsEntry("service", "profile");
            String detail = item.payload().get("detail").toString();
            assertThat(detail).contains("nome", "biografia").doesNotContain("Secreto").doesNotContain("privada");
        });
        assertThat(audit.recorded("PROFILE_PRIVACY_CHANGED")).isEmpty();
    }

    @Test
    void privacyChangeRecordsFromAndTo() {
        Account ana = createActive(uniqueUsername());
        audit.clear();

        assertThat(http().send("PATCH", "/api/usuarios/" + ana.id(), "{\"privado\":true}", ana.token()).status())
                .isEqualTo(200);
        assertThat(audit.recorded("PROFILE_PRIVACY_CHANGED")).singleElement().satisfies(item ->
                assertThat(item.payload().get("detail").toString()).contains("from=PUBLIC", "to=PRIVATE"));
        assertThat(audit.recorded("PROFILE_UPDATED")).isEmpty();

        // Repetir o mesmo valor não muda nada e não gera evento.
        audit.clear();
        http().send("PATCH", "/api/usuarios/" + ana.id(), "{\"privado\":true}", ana.token());
        assertThat(audit.recorded()).isEmpty();
    }

    @Test
    void avatarChangeIsAudited() throws IOException {
        Account ana = createActive(uniqueUsername());
        audit.clear();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB), "png", out);

        Response response = http().multipart("PATCH", "/api/img/usuarios/" + ana.id(), ana.token(), "foto_perfil",
                "a.png", "image/png", out.toByteArray(), Map.of());
        assertThat(response.status()).as(response.body()).isEqualTo(200);

        assertThat(audit.recorded("AVATAR_CHANGED")).singleElement().satisfies(item ->
                assertThat(item.payload()).containsEntry("targetType", "PROFILE")
                        .containsEntry("targetId", ana.id().toString()).doesNotContainKey("detail"));
    }
}
