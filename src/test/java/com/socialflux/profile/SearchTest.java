package com.socialflux.profile;

import com.socialflux.profile.support.AbstractProfileTest;
import com.socialflux.profile.support.Http.Response;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SearchTest extends AbstractProfileTest {

    private static String tag() {
        return "q" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    private List<String> usernames(Response response) {
        return response.jsonList().stream().map(m -> (String) m.get("username")).toList();
    }

    @Test
    void findsByUsernameAndNameOrderedByRelevance() {
        String tag = tag();
        Account exact = createActive(tag);                               // username == busca
        Account prefix = createActive(tag + "x");                        // prefixo
        Account contains = createActive("zz" + tag);                     // contém
        Account byName = createActive(uniqueUsername());
        http().send("PATCH", "/api/usuarios/" + byName.id(), "{\"nome\":\"Fulano %s\"}".formatted(tag), byName.token());
        createPending(tag + "pend");                                     // pendente: nunca aparece

        Response response = http().get("/api/usuarios/search?search=" + tag.toUpperCase(), exact.token());
        assertThat(response.status()).isEqualTo(200);
        List<String> found = usernames(response);

        assertThat(found).containsExactly(exact.username(), prefix.username(), byName.username(), contains.username());
        response.jsonList().forEach(item ->
                assertThat(item).doesNotContainKeys("email", "telefone", "sexo", "data_nascimento"));
    }

    @Test
    void wildcardsInTheQueryAreLiteralNotPatterns() {
        Account account = createActive(uniqueUsername());
        // No legado "%" e "_" viravam curingas do LIKE e listavam usuários à vontade
        assertThat(http().get("/api/usuarios/search?search=%25%25", account.token()).jsonList()).isEmpty();
        assertThat(http().get("/api/usuarios/search?search=__", account.token()).jsonList()).isEmpty();
        assertThat(http().get("/api/usuarios/search?search=%5C%5C", account.token()).jsonList()).isEmpty();
    }

    @Test
    void validatesTheQueryAndCapsResults() {
        String tag = tag();
        List<Account> created = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            created.add(createActive(tag + String.format("%02d", i)));
        }
        String token = created.get(0).token();

        assertThat(http().get("/api/usuarios/search?search=a", token).status()).isEqualTo(400);
        assertThat(http().get("/api/usuarios/search", token).status()).isEqualTo(400);
        assertThat(http().get("/api/usuarios/search?search=" + "a".repeat(51), token).status()).isEqualTo(400);
        assertThat(http().get("/api/usuarios/search?search=" + tag, token).jsonList()).hasSize(10);
    }

    @Test
    void searchIsRateLimitedPerUser() {
        Account account = createActive(uniqueUsername());
        int ok = 0;
        Response last = null;
        for (int i = 0; i < 70; i++) {
            last = http().get("/api/usuarios/search?search=zz", account.token());
            if (last.status() == 200) {
                ok++;
            }
        }
        assertThat(ok).isEqualTo(60);
        assertThat(last.status()).isEqualTo(429);
        assertThat(last.header("Retry-After")).isNotNull();
        Map<String, Object> body = last.json();
        assertThat(body.get("codigo")).isEqualTo("too_many_requests");
    }
}
