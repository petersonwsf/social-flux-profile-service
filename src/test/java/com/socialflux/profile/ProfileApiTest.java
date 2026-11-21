package com.socialflux.profile;

import com.socialflux.profile.support.AbstractProfileTest;
import com.socialflux.profile.support.Http.Response;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ProfileApiTest extends AbstractProfileTest {

    private static final String SERVICE = "profiles:write profiles:read";

    private Response create(UUID id, String body) {
        return http().send("PUT", "/internal/profiles/" + id, body, serviceToken(SERVICE));
    }

    // ------------------------------------------------------------------ criação (chamada pelo auth)

    @Test
    void creationValidatesEverythingOnTheServer() {
        String username = uniqueUsername();
        String base = createBody(username);

        assertThat(create(UUID.randomUUID(), base.replace(username, "Nome Inválido")).json().get("codigo")).isEqualTo("invalid_username");
        assertThat(create(UUID.randomUUID(), base.replace(username, "a..b")).json().get("codigo")).isEqualTo("invalid_username");
        assertThat(create(UUID.randomUUID(), base.replace(username, ".abc")).json().get("codigo")).isEqualTo("invalid_username");
        assertThat(create(UUID.randomUUID(), base.replace(username, "ab")).json().get("codigo")).isEqualTo("invalid_username");
        assertThat(create(UUID.randomUUID(), base.replace(username, "admin")).json().get("codigo")).isEqualTo("reserved_username");
        assertThat(create(UUID.randomUUID(), base.replace("Pessoa Teste", "x")).json().get("codigo")).isEqualTo("invalid_name");
        assertThat(create(UUID.randomUUID(), base.replace("11 99999-8888", "123")).json().get("codigo")).isEqualTo("invalid_phone");
        assertThat(create(UUID.randomUUID(), base.replace("\"F\"", "\"X\"")).json().get("codigo")).isEqualTo("invalid_sex");

        String underage = base.replace("1990-05-20", java.time.LocalDate.now().minusYears(17).toString());
        Response response = create(UUID.randomUUID(), underage);
        assertThat(response.status()).isEqualTo(400);
        assertThat(response.json().get("codigo")).isEqualTo("underage");
    }

    @Test
    void creationIsIdempotentAndRejectsDuplicates() {
        String username = uniqueUsername();
        UUID id = UUID.randomUUID();

        Response first = create(id, createBody(username));
        assertThat(first.status()).isEqualTo(201);
        Response again = create(id, createBody(username));
        assertThat(again.status()).isEqualTo(200);
        assertThat(again.json().get("accountId")).isEqualTo(first.json().get("accountId"));

        Response other = create(UUID.randomUUID(), createBody(username.toUpperCase()));   // case-insensitive
        assertThat(other.status()).isEqualTo(409);
        assertThat(other.json().get("codigo")).isEqualTo("username_taken");

        Response sameAccountOtherUsername = create(id, createBody(uniqueUsername()));
        assertThat(sameAccountOtherUsername.status()).isEqualTo(409);
        assertThat(sameAccountOtherUsername.json().get("codigo")).isEqualTo("profile_exists");
    }

    @Test
    void discardOnlyRemovesUnconfirmedProfiles() {
        Account pending = createPending(uniqueUsername());
        assertThat(http().send("DELETE", "/internal/profiles/" + pending.id(), null, serviceToken(SERVICE)).status())
                .isEqualTo(204);
        assertThat(create(pending.id(), createBody(pending.username())).status()).isEqualTo(201); // username liberado

        Account active = createActive(uniqueUsername());
        assertThat(http().send("DELETE", "/internal/profiles/" + active.id(), null, serviceToken(SERVICE)).status())
                .isEqualTo(409);
        assertThat(http().get("/api/usuarios/me", active.token()).status()).isEqualTo(200);
        // inexistente: idempotente
        assertThat(http().send("DELETE", "/internal/profiles/" + UUID.randomUUID(), null, serviceToken(SERVICE)).status())
                .isEqualTo(204);
    }

    // ------------------------------------------------------------------ leitura e privacidade de dados

    @Test
    void pendingProfilesAreVisibleOnlyToTheirOwner() {
        Account pending = createPending(uniqueUsername());
        Account other = createActive(uniqueUsername());

        assertThat(http().get("/api/usuarios/me", pending.token()).status()).isEqualTo(200);
        assertThat(http().get("/api/usuarios?username=" + pending.username(), pending.token()).status()).isEqualTo(200);
        assertThat(http().get("/api/usuarios?username=" + pending.username(), other.token()).status()).isEqualTo(404);
        assertThat(http().get("/api/usuarios?id=" + pending.id(), other.token()).status()).isEqualTo(404);

        publishAccountEvent("account.registered.v1", pending.id());
        assertThat(http().get("/api/usuarios?username=" + pending.username(), other.token()).status()).isEqualTo(200);
    }

    @Test
    void otherUsersNeverSeePersonalData() {
        Account owner = createActive(uniqueUsername());
        Account viewer = createActive(uniqueUsername());

        for (String path : List.of("/api/usuarios?username=" + owner.username(),
                "/api/usuarios?id=" + owner.id())) {
            Response response = http().get(path, viewer.token());
            assertThat(response.status()).as(path).isEqualTo(200);
            Map<String, Object> json = response.json();
            assertThat(json).containsKeys("id", "username", "nome", "biografia", "foto_perfil", "foto_url", "privado");
            assertThat(json).doesNotContainKeys("email", "telefone", "sexo", "data_nascimento", "data_criacao", "senha");
            assertThat(response.body()).doesNotContain("11999998888").doesNotContain("1990");
            assertThat(json.get("id")).isEqualTo(owner.id().toString());
            assertThat(json).doesNotContainKey("accountId");
        }

        Map<String, Object> own = http().get("/api/usuarios/me", owner.token()).json();
        assertThat(own.get("telefone")).isEqualTo("11999998888");
        assertThat(own.get("sexo")).isEqualTo("F");
        assertThat(own.get("data_nascimento")).isEqualTo("1990-05-20");
        assertThat(http().get("/api/usuarios?username=" + owner.username(), owner.token()).json()).containsKey("telefone");

        // a visão pública dos eventos/internos também não tem dados pessoais
        Response internal = http().get("/internal/profiles/" + owner.id(), serviceToken("profiles:read"));
        assertThat(internal.body()).doesNotContain("telefone").doesNotContain("data_nascimento");
    }

    @Test
    void lookupHandlesBadInputAndIsCaseInsensitive() {
        Account account = createActive(uniqueUsername());
        assertThat(http().get("/api/usuarios", account.token()).status()).isEqualTo(400);
        assertThat(http().get("/api/usuarios?id=abc", account.token()).status()).isEqualTo(400);
        assertThat(http().get("/api/usuarios?id=99999999", account.token()).status()).isEqualTo(400);   // o id inteiro não existe mais: só UUID
        assertThat(http().get("/api/usuarios?id=" + java.util.UUID.randomUUID(), account.token()).status()).isEqualTo(404);
        assertThat(http().get("/api/usuarios?username=nao.existe.mesmo", account.token()).status()).isEqualTo(404);
        assertThat(http().get("/api/usuarios?username=" + account.username().toUpperCase(), account.token()).status())
                .isEqualTo(200);
    }

    // ------------------------------------------------------------------ edição e autorização (IDOR)

    @Test
    void usersCanOnlyEditTheirOwnProfile_theBodyIdIsIgnored() {
        Account a = createActive(uniqueUsername());
        Account b = createActive(uniqueUsername());

        // Ataque clássico do legado: caminho/corpo apontando para outra pessoa
        Response attack = http().send("PATCH", "/api/usuarios/" + b.id(),
                "{\"id\":\"%s\",\"nome\":\"Hackeado\",\"privado\":true}".formatted(b.id()), a.token());
        assertThat(attack.status()).isEqualTo(403);
        Response attackByUuid = http().send("PATCH", "/api/usuarios/" + b.id(), "{\"nome\":\"Hackeado\"}", a.token());
        assertThat(attackByUuid.status()).isEqualTo(403);

        // Caminho próprio + id de outra pessoa no corpo: edita SÓ o próprio
        Response mixed = http().send("PATCH", "/api/usuarios/" + a.id(),
                "{\"id\":\"%s\",\"nome\":\"Nome Do A\"}".formatted(b.id()), a.token());
        assertThat(mixed.status()).isEqualTo(200);
        assertThat(mixed.json().get("nome")).isEqualTo("Nome Do A");

        Map<String, Object> bAfter = http().get("/api/usuarios/me", b.token()).json();
        assertThat(bAfter.get("nome")).isEqualTo("Pessoa Teste");
        assertThat(bAfter.get("privado")).isEqualTo(false);

        // Pelo UUID da conta também funciona
        assertThat(http().send("PATCH", "/api/usuarios/" + a.id(), "{\"biografia\":\"olá\"}", a.token()).status())
                .isEqualTo(200);
        assertThat(http().send("PATCH", "/api/usuarios/abc", "{}", a.token()).status()).isEqualTo(400);
    }

    @Test
    void editValidationUniquenessAndPartialUpdates() {
        Account a = createActive(uniqueUsername());
        Account b = createActive(uniqueUsername());
        String path = "/api/usuarios/" + a.id();

        Response taken = http().send("PATCH", path, "{\"username\":\"%s\"}".formatted(b.username()), a.token());
        assertThat(taken.status()).isEqualTo(409);
        assertThat(taken.json().get("mensagem")).isEqualTo("Username já está em uso, tente outro!");

        assertThat(http().send("PATCH", path, "{\"username\":\"Com Espaço\"}", a.token()).json().get("codigo"))
                .isEqualTo("invalid_username");
        assertThat(http().send("PATCH", path, "{\"nome\":\"\"}", a.token()).json().get("codigo")).isEqualTo("invalid_name");
        assertThat(http().send("PATCH", path, "{\"biografia\":\"%s\"}".formatted("x".repeat(501)), a.token()).json().get("codigo"))
                .isEqualTo("invalid_bio");

        // `privado` ausente não é "false" nem NPE (o legado dava 500)
        http().send("PATCH", path, "{\"privado\":true}", a.token());
        Response partial = http().send("PATCH", path, "{\"nome\":\"Só o Nome\"}", a.token());
        assertThat(partial.status()).isEqualTo(200);
        assertThat(partial.json().get("privado")).isEqualTo(true);

        http().send("PATCH", path, "{\"biografia\":\"minha bio\"}", a.token());
        assertThat(http().send("PATCH", path, "{\"biografia\":\"\"}", a.token()).json().get("biografia")).isNull();

        // username é livre (D7): trocar libera o antigo para outra pessoa
        String old = a.username();
        String renamed = uniqueUsername();
        assertThat(http().send("PATCH", path, "{\"username\":\"%s\"}".formatted(renamed), a.token()).status()).isEqualTo(200);
        assertThat(http().send("PATCH", "/api/usuarios/" + b.id(), "{\"username\":\"%s\"}".formatted(old), b.token()).status())
                .isEqualTo(200);
    }
}
