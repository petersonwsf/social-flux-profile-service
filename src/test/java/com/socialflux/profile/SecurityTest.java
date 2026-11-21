package com.socialflux.profile;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.socialflux.profile.support.AbstractProfileTest;
import com.socialflux.profile.support.Http.Response;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** O serviço é um resource server: só aceita JWT RS256 válido do authentication_service e separa token de usuário × serviço. */
class SecurityTest extends AbstractProfileTest {

    private static final String ME = "/api/usuarios/me";

    @Test
    void anonymousRequestsGetJson401() {
        Response response = http().get(ME, null);
        assertThat(response.status()).isEqualTo(401);
        assertThat(response.json()).containsEntry("sucesso", false).containsKey("mensagem");
        assertThat(http().get("/api/usuarios?username=x", null).status()).isEqualTo(401);
        assertThat(http().get("/api/usuarios/search?search=ab", null).status()).isEqualTo(401);
        assertThat(http().send("PATCH", "/api/usuarios/1", "{}", null).status()).isEqualTo(401);
    }

    @Test
    void validUserTokenWorks() {
        Account account = createActive(uniqueUsername());
        assertThat(http().get(ME, account.token()).status()).isEqualTo(200);
    }

    @Test
    void tamperedAlgNoneAndForeignKeysAreRejected() throws Exception {
        Account account = createActive(uniqueUsername());
        String token = account.token();
        String[] parts = token.split("\\.");

        String tampered = parts[0] + "." + parts[1] + "." + parts[2].substring(0, parts[2].length() - 4) + "AAAA";
        assertThat(http().get(ME, tampered).status()).isEqualTo(401);

        String none = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"none\"}".getBytes()) + "."
                + parts[1] + ".";
        assertThat(http().get(ME, none).status()).isEqualTo(401);

        // Assinado por OUTRA chave RSA reutilizando o mesmo kid
        RSAKey other = new RSAKeyGenerator(2048).keyID(KEY.getKeyID()).generate();
        SignedJWT forged = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(other.getKeyID()).build(),
                SignedJWT.parse(token).getJWTClaimsSet());
        forged.sign(new RSASSASigner(other));
        assertThat(http().get(ME, forged.serialize()).status()).isEqualTo(401);

        // Cabeçalho HS256 usando a chave pública como segredo (ataque de confusão de algoritmo)
        assertThat(http().get(ME, "eyJhbGciOiJIUzI1NiJ9." + parts[1] + ".c2ln").status()).isEqualTo(401);
    }

    @Test
    void expiredWrongIssuerAndWrongAudienceAreRejected() {
        UUID id = createActive(uniqueUsername()).id();
        Date now = new Date();
        JWTClaimsSet.Builder base = new JWTClaimsSet.Builder().subject(id.toString()).issueTime(now)
                .claim("roles", List.of("USER"));

        String expired = sign(base.issuer(ISSUER).audience(USER_AUDIENCE)
                .expirationTime(new Date(now.getTime() - 600_000)).build());
        String wrongIssuer = sign(new JWTClaimsSet.Builder(base.build()).issuer("http://evil.example")
                .audience(USER_AUDIENCE).expirationTime(new Date(now.getTime() + 600_000)).build());
        String wrongAudience = sign(new JWTClaimsSet.Builder(base.build()).issuer(ISSUER).audience("outra-api")
                .expirationTime(new Date(now.getTime() + 600_000)).build());

        assertThat(http().get(ME, expired).status()).isEqualTo(401);
        assertThat(http().get(ME, wrongIssuer).status()).isEqualTo(401);
        assertThat(http().get(ME, wrongAudience).status()).isEqualTo(401);
    }

    @Test
    void userTokensCannotCallInternalRoutesAndServiceTokensCannotCallUserRoutes() {
        Account account = createActive(uniqueUsername());
        String service = serviceToken("profiles:write profiles:read");

        assertThat(http().get("/internal/profiles/" + account.id(), account.token()).status()).isEqualTo(403);
        assertThat(http().send("PUT", "/internal/profiles/" + UUID.randomUUID(), createBody(uniqueUsername()),
                account.token()).status()).isEqualTo(403);

        assertThat(http().get(ME, service).status()).isEqualTo(403);
        assertThat(http().get("/api/usuarios?username=" + account.username(), service).status()).isEqualTo(403);
    }

    @Test
    void serviceScopesAreEnforcedSeparately() {
        Account account = createActive(uniqueUsername());
        String readOnly = serviceToken("profiles:read");
        String writeOnly = serviceToken("profiles:write");

        assertThat(http().get("/internal/profiles/" + account.id(), readOnly).status()).isEqualTo(200);
        assertThat(http().send("PUT", "/internal/profiles/" + UUID.randomUUID(), createBody(uniqueUsername()),
                readOnly).status()).isEqualTo(403);
        assertThat(http().send("PUT", "/internal/profiles/" + UUID.randomUUID(), createBody(uniqueUsername()),
                writeOnly).status()).isEqualTo(201);
        assertThat(http().get("/internal/profiles/" + account.id(), writeOnly).status()).isEqualTo(403);
    }

    @Test
    void aUserTokenCannotImpersonateAServiceByClaimingScopes() {
        UUID id = UUID.randomUUID();
        String token = sign(new JWTClaimsSet.Builder().issuer(ISSUER).audience(USER_AUDIENCE).subject(id.toString())
                .issueTime(new Date()).expirationTime(new Date(System.currentTimeMillis() + 600_000))
                .claim("roles", List.of("USER")).claim("scope", "profiles:write profiles:read").build());
        assertThat(http().get("/internal/profiles/" + id, token).status()).isEqualTo(403);
    }

    @Test
    void unknownRoutesAreDeniedByDefault() {
        Account account = createActive(uniqueUsername());
        assertThat(http().get("/qualquer-coisa", account.token()).status()).isEqualTo(403);
        assertThat(http().get("/actuator/env", account.token()).status()).isIn(403, 404);
    }

    @Test
    void healthEndpointIsPublic() {
        assertThat(http().get("/actuator/health", null).status()).isEqualTo(200);
    }

    @Test
    void crossSiteWritesAreRejectedButSameOriginAndNonBrowserClientsPass() {
        Account account = createActive(uniqueUsername());
        String path = "/api/usuarios/" + account.id();
        String body = "{\"nome\":\"Nome Novo\"}";

        assertThat(http().send("PATCH", path, body, account.token(), Map.of("Origin", "http://evil.example")).status())
                .isEqualTo(403);
        assertThat(http().send("PATCH", path, body, account.token(), Map.of("Sec-Fetch-Site", "cross-site")).status())
                .isEqualTo(403);
        assertThat(http().send("PATCH", path, body, account.token(), Map.of("Origin", http().baseUrl())).status())
                .isEqualTo(200);
        assertThat(http().send("PATCH", path, body, account.token()).status()).isEqualTo(200);
    }
}
