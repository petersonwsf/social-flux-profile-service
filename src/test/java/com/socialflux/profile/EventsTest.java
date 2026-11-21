package com.socialflux.profile;

import com.socialflux.profile.support.AbstractProfileTest;
import com.socialflux.profile.support.Http.Response;
import com.socialflux.profile.support.TestSupportConfig.RecordingPublisher;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class EventsTest extends AbstractProfileTest {

    private static final String SERVICE_READ = "profiles:read";

    // ------------------------------------------------------------------ reação aos eventos da conta

    @Test
    void registrationEventActivatesTheProfileExactlyOnce() {
        Account account = createPending(uniqueUsername());
        assertThat(outboxTypes(account.id())).isEmpty();

        String message = """
                {"id":"%s","type":"account.registered.v1","occurredAt":"2026-01-01T00:00:00Z","aggregateId":"%s","aggregateVersion":1,"data":{}}
                """.formatted(UUID.randomUUID(), account.id());
        accountEvents.handle(message);
        accountEvents.handle(message);                                  // mesma mensagem entregue de novo (pelo menos uma vez)
        publishAccountEvent("account.registered.v1", account.id());     // outra mensagem para um perfil já ativo

        assertThat(outboxTypes(account.id())).containsExactly("profile.created.v1");
        assertThat(http().get("/api/usuarios?id=" + account.id(), createActive(uniqueUsername()).token()).status())
                .isEqualTo(200);
    }

    @Test
    void unknownEventsAndEventsForUnknownAccountsAreIgnored() {
        publishAccountEvent("account.algo-novo.v7", UUID.randomUUID());
        publishAccountEvent("account.registered.v1", UUID.randomUUID());
        publishAccountEvent("account.deleted.v1", UUID.randomUUID());
    }

    @Test
    void accountDeletionErasesPersonalDataFreesTheUsernameAndNotifiesOthers() throws Exception {
        Account account = createActive(uniqueUsername());
        Account viewer = createActive(uniqueUsername());
        String key = (String) http().multipart("PATCH", "/api/img/usuarios/" + account.id(), account.token(),
                "foto_perfil", "a.png", "image/png", pngBytes(), Map.of()).json().get("foto_perfil");
        assertThat(mediaStorage.exists("avatars", key)).isTrue();

        publishAccountEvent("account.deleted.v1", account.id());
        publishAccountEvent("account.deleted.v1", account.id());      // idempotente

        assertThat(http().get("/api/usuarios?username=" + account.username(), viewer.token()).status()).isEqualTo(404);
        assertThat(http().get("/api/usuarios?id=" + account.id(), viewer.token()).status()).isEqualTo(404);
        assertThat(http().get("/api/usuarios/me", account.token()).status()).isEqualTo(404);
        assertThat(http().send("PATCH", "/api/usuarios/" + account.id(), "{\"nome\":\"x y\"}", account.token()).status())
                .isEqualTo(404);
        assertThat(mediaStorage.exists("avatars", key)).as("a foto é apagada").isFalse();

        Map<String, Object> row = jdbc.sql("select status, username, display_name, phone, sex, birth_date, bio, avatar_key "
                        + "from profile where id = :id").param("id", account.id())
                .query((rs, n) -> {
                    Map<String, Object> m = new java.util.HashMap<>();
                    m.put("status", rs.getString("status"));
                    m.put("username", rs.getString("username"));
                    m.put("phone", rs.getString("phone"));
                    m.put("sex", rs.getString("sex"));
                    m.put("birth", rs.getObject("birth_date"));
                    m.put("avatar", rs.getString("avatar_key"));
                    return m;
                }).single();
        assertThat(row.get("status")).isEqualTo("DELETED");
        assertThat((String) row.get("username")).startsWith("deleted.");
        assertThat(row.get("phone")).isNull();
        assertThat(row.get("sex")).isNull();
        assertThat(row.get("birth")).isNull();
        assertThat(row.get("avatar")).isNull();

        assertThat(outboxTypes(account.id())).endsWith("profile.deleted.v1");
        assertThat(outboxTypes(account.id()).stream().filter("profile.deleted.v1"::equals)).hasSize(1);
        // O username volta a ficar disponível
        assertThat(http().send("PUT", "/internal/profiles/" + UUID.randomUUID(), createBody(account.username()),
                serviceToken("profiles:write")).status()).isEqualTo(201);
    }

    @Test
    void deletingAnUnconfirmedProfileEmitsNothing() {
        Account pending = createPending(uniqueUsername());
        publishAccountEvent("account.deleted.v1", pending.id());
        assertThat(outboxTypes(pending.id())).isEmpty();
    }

    // ------------------------------------------------------------------ eventos produzidos (outbox)

    @Test
    void changesAreRecordedInTheOutboxWithoutPersonalData() throws Exception {
        Account account = createPending(uniqueUsername());
        // Mudanças enquanto pendente não geram evento: o "created" leva o estado completo
        http().send("PATCH", "/api/usuarios/" + account.id(), "{\"nome\":\"Nome Ainda Pendente\"}", account.token());
        assertThat(outboxTypes(account.id())).isEmpty();

        publishAccountEvent("account.registered.v1", account.id());
        http().send("PATCH", "/api/usuarios/" + account.id(), "{\"biografia\":\"olá\"}", account.token());
        http().send("PATCH", "/api/usuarios/" + account.id(), "{\"privado\":true}", account.token());
        http().send("PATCH", "/api/usuarios/" + account.id(), "{\"nome\":\"Nome Ainda Pendente\"}", account.token()); // sem mudança

        assertThat(outboxTypes(account.id())).containsExactly("profile.created.v1", "profile.updated.v1",
                "profile.updated.v1", "profile.privacy-changed.v1");

        drainOutbox();
        List<RecordingPublisher.Sent> sent = publisher.sent.stream()
                .filter(s -> s.json().contains(account.id().toString())).toList();
        assertThat(sent).hasSize(4);
        for (RecordingPublisher.Sent message : sent) {
            assertThat(message.json()).doesNotContain("11999998888").doesNotContain("telefone").doesNotContain("phone")
                    .doesNotContain("birth").doesNotContain("sex").doesNotContain("@");
        }
        String created = sent.get(0).json();
        assertThat(created).contains("\"type\":\"profile.created.v1\"").contains("\"username\":\"" + account.username() + "\"")
                .contains("\"displayName\":\"Nome Ainda Pendente\"").doesNotContain("legacyId")
                .contains("\"aggregateId\":\"" + account.id() + "\"");
        // versões estritamente crescentes para os consumidores descartarem eventos antigos
        List<Long> versions = new ArrayList<>();
        for (RecordingPublisher.Sent message : sent) {
            String json = message.json();
            int index = json.indexOf("\"aggregateVersion\":") + "\"aggregateVersion\":".length();
            versions.add(Long.parseLong(json.substring(index, json.indexOf(',', index))));
        }
        assertThat(versions).isSorted();
    }

    @Test
    void relayKeepsEventsPendingWhileTheBrokerIsDownAndDeliversThemInOrderAfterwards() {
        Account account = createActive(uniqueUsername());
        http().send("PATCH", "/api/usuarios/" + account.id(), "{\"nome\":\"Primeiro Nome\"}", account.token());
        http().send("PATCH", "/api/usuarios/" + account.id(), "{\"nome\":\"Segundo Nome\"}", account.token());

        publisher.down = true;
        try {
            assertThat(relay.publishPending()).isZero();
            assertThat(publisher.routingKeysFor(account.id())).isEmpty();
        } finally {
            publisher.down = false;
        }
        drainOutbox();
        assertThat(publisher.routingKeysFor(account.id()))
                .containsExactly("profile.created.v1", "profile.updated.v1", "profile.updated.v1");
        // Nada reenviado
        int before = publisher.sent.size();
        drainOutbox();
        assertThat(publisher.sent).hasSize(before);
    }

    // ------------------------------------------------------------------ bootstrap e consulta em lote

    @Test
    void bootstrapPagesThroughAllActiveProfilesAndBatchGetFiltersPending() {
        List<Account> accounts = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            accounts.add(createActive(uniqueUsername()));
        }
        Account pending = createPending(uniqueUsername());
        String read = serviceToken(SERVICE_READ);

        List<String> seen = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            Response page = http().get("/internal/profiles?limit=2" + (cursor == null ? "" : "&cursor=" + enc(cursor)), read);
            assertThat(page.status()).as(page.body()).isEqualTo(200);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) page.json().get("items");
            assertThat(items.size()).isLessThanOrEqualTo(2);
            items.forEach(item -> seen.add((String) item.get("id")));
            cursor = (String) page.json().get("nextCursor");
            pages++;
        } while (cursor != null && pages < 500);

        assertThat(seen).containsAll(accounts.stream().map(a -> a.id().toString()).toList())
                .doesNotContain(pending.id().toString()).doesNotHaveDuplicates();
        assertThat(http().get("/internal/profiles?cursor=lixo", read).status()).isEqualTo(400);

        String ids = "{\"ids\":[\"%s\",\"%s\",\"%s\"]}".formatted(accounts.get(0).id(), pending.id(), UUID.randomUUID());
        Response batch = http().send("POST", "/internal/profiles/batch-get", ids, read);
        assertThat(batch.status()).isEqualTo(200);
        assertThat(batch.jsonList()).hasSize(1);
        assertThat(batch.jsonList().get(0).get("id")).isEqualTo(accounts.get(0).id().toString());
    }

    private static String enc(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static byte[] pngBytes() throws Exception {
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(64, 64, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "png", out);
        return out.toByteArray();
    }
}
