# profile_service

Serviço de **perfil de usuário** do Social Flux: perfil, busca, foto de perfil e privacidade da conta.
Spring Boot 4 · Java 21 · PostgreSQL (banco próprio) · Flyway · RabbitMQ (outbox) · resource server JWT RS256.

Plano e decisões: [`../docs/plano-dominio-perfil-servico-proprio.md`](../docs/plano-dominio-perfil-servico-proprio.md).

## Como se relaciona com os outros serviços

```
Navegador ─► Caddy ──► /api/usuarios*, PATCH /api/img/usuarios/*, /media/avatars/*  ─► profile_service
                       (cookie -> Bearer; forward_auth no auth confere a revogação)
authentication_service ──PUT /internal/profiles/{uuid} (token de serviço)──► profile_service   (cadastro)
authentication_service ──account.registered.v1 / account.deleted.v1 (RabbitMQ)──► profile_service
profile_service ──profile.created|updated|privacy-changed|deleted.v1 (RabbitMQ)──► social_graph_service, post_service e notification_service (cópias locais do perfil)
```

- **Identidade:** o perfil tem o **mesmo UUID da conta** (`sub` do JWT). O serviço **nunca** usa um id vindo do cliente
  como autoridade: a rota `PATCH /api/usuarios/{id}` só aceita o `id` (UUID) do próprio usuário autenticado
  e ignora o `id` do corpo.
- **Quem vê o quê (D3/D9):** qualquer usuário autenticado vê a visão **pública** (`id` = UUID da conta, `username`, `nome`,
  `biografia`, `foto_perfil`, `foto_url`, `privado`) — inclusive de contas privadas; **telefone, sexo e data de nascimento só o
  dono** vê (`GET /api/usuarios/me` e a própria visão em `GET /api/usuarios`). E-mail pertence ao authentication_service.

## API pública (mesmo contrato que o frontend usava)

| Rota | Descrição |
| --- | --- |
| `GET /api/usuarios?username=` / `?id=` | Perfil (público; do dono inclui dados pessoais). `id` é o UUID da conta (outro formato: 400). 404 uniforme |
| `GET /api/usuarios/me` | Perfil do usuário logado |
| `GET /api/usuarios/search?search=` | 2–50 caracteres, curingas do `LIKE` escapados, ordenado por relevância, máx. 10, 60/min por usuário |
| `PATCH /api/usuarios/{id}` | `username`, `nome`, `biografia`, `privado` (todos opcionais). Username é livre (D7), 3–30 chars `a-z0-9._` |
| `PATCH /api/img/usuarios/{id}` | multipart `foto_perfil` (PNG, JPEG ou WebP, ≤ 5 MB, ≤ 16 MP; orientação EXIF corrigida; PNG/WebP animados e GIF recusados); `foto_antiga` é ignorado. 503 `servidor_ocupado` se o limite de processamentos simultâneos estiver cheio |
| `GET /media/avatars/{chave}` | Avatar (D8: público), JPEG 256×256 re-codificado, `nosniff`, cache imutável; lido do bucket `avatars` do RustFS |

Erros: `{ "mensagem", "sucesso": false, "codigo" }`.

## API interna (`/internal/**`, token de serviço, nunca roteada pelo gateway)

| Rota | Escopo | Descrição |
| --- | --- | --- |
| `PUT /internal/profiles/{uuid}` | `profiles:write` | Criação idempotente no cadastro (o auth chama). Devolve `{ accountId, created }` |
| `DELETE /internal/profiles/{uuid}` | `profiles:write` | Compensação: só perfis ainda `PENDING` |
| `GET /internal/profiles/{uuid}`, `POST /internal/profiles/batch-get` | `profiles:read` | Visão pública |
| `GET /internal/profiles?cursor=&limit=` | `profiles:read` | **Bootstrap** de novos consumidores (cópia local), paginado |

O token de serviço é um JWT RS256 de 5 min emitido pelo authentication_service (`sub=svc:authentication-service`,
`aud=profile-service`, `scope=...`). Tokens de usuário nunca têm escopos, e tokens de serviço nunca têm papéis.

## Eventos (RabbitMQ, exchange `socialflux.events`, routing key = tipo)

Publica (via **outbox transacional**): `profile.created.v1`, `profile.updated.v1`, `profile.privacy-changed.v1`,
`profile.deleted.v1`. O payload é o estado **público** + `aggregateVersion` (consumidores ignoram versões antigas); **nunca**
carrega telefone, sexo, nascimento ou e-mail. Consome: `account.registered.v1` (ativa o perfil `PENDING → ACTIVE`) e
`account.deleted.v1` (apaga dados pessoais e libera o username). Consumo idempotente (tabela `processed_message`). Outbox, inbox e publicador vêm da biblioteca
[`libs/socialflux-events`](../libs/socialflux-events/README.md).
Falhas repetidas vão para a fila `socialflux.events.dead`.

**Novo serviço consumidor:** declare a sua fila, ligue ao exchange com `profile.#` e faça o bootstrap por
`GET /internal/profiles` antes de depender dos eventos.

## Rodando

Na raiz do repositório (usa o `.env` e o mesmo PostgreSQL do auth, em um **banco próprio** `profile`):

```bash
docker compose up -d                  # postgres, rabbitmq, rustfs, mailpit (+ cria o banco `profile`)
powershell -ExecutionPolicy Bypass -File scripts/dev/run-auth.ps1       # 8081   (ou ./scripts/dev/run-auth.sh)
powershell -ExecutionPolicy Bypass -File scripts/dev/run-profile.ps1    # 8082   (ou ./scripts/dev/run-profile.sh)
```

Testes (PostgreSQL real embutido, JWKS local e JWT RS256 de verdade; sem Docker):

```bash
mvn test        # na pasta do serviço, depois de instalar as bibliotecas: mvn -f ../libs/socialflux-events/pom.xml install e mvn -f ../libs/socialflux-media/pom.xml install
# ou, na raiz: mvn -pl profile_service -am test
```

## Configuração (variáveis de ambiente)

| Variável | Descrição |
| --- | --- |
| `PROFILE_DB_URL` / `PROFILE_DB_NAME` | Banco do serviço (padrão `jdbc:postgresql://localhost:5432/profile`) |
| `PROFILE_DB_USER` / `PROFILE_DB_PASSWORD` | Se ausentes, **reutilizam** `IDENTITY_DB_USER` / `IDENTITY_DB_PASSWORD` do `.env` |
| `AUTH_JWKS_URI` | JWKS do authentication_service (só a chave **pública**) |
| `JWT_ISSUER`, `JWT_AUDIENCE`, `PROFILE_SERVICE_AUDIENCE` | Validação do token (usuário × serviço) |
| `RUSTFS_ENDPOINT`, `RUSTFS_ACCESS_KEY`, `RUSTFS_SECRET_KEY`, `RUSTFS_REGION` | Armazenamento de mídia S3 compatível (`libs/socialflux-media`); no container `http://rustfs:9000`, no host `http://localhost:9000`. Hoje a credencial **raiz** do RustFS (credencial por bucket pendente, D18) |
| `PROFILE_AVATAR_BUCKET` | Bucket dos avatares (padrão `avatars`, criado pelo `rustfs_init`) |
| `PROFILE_MEDIA_STORAGE` | `s3` (padrão) ou `memory` (só testes) |
| `PROFILE_AVATAR_MAX_BYTES`, `PROFILE_AVATAR_MAX_PIXELS` | Limites do upload (5MB, 16 MP) → `socialflux.media.image.*` |
| `PROFILE_MEDIA_MAX_CONCURRENT` | Processamentos de imagem simultâneos (padrão 4) |
| `RABBITMQ_HOST/PORT/USER/PASSWORD`, `EVENTS_EXCHANGE`, `PROFILE_EVENTS_ENABLED` | Broker e eventos |
| `PROFILE_ALLOWED_ORIGINS` | Origens aceitas em métodos não seguros (vazio = a própria origem) |

## Limites conhecidos

- A **revogação imediata** (logout geral, troca de senha) é aplicada pelo gateway (`forward_auth`); este serviço só valida
  assinatura/validade do JWT (até 2 h). Não o exponha sem o gateway.
- Rate limit em memória (1 instância). Avatares no RustFS com a credencial raiz (credencial restrita ao bucket pendente, D18); o teste contra o RustFS real ainda não foi executado.
- Um perfil `PENDING` órfão (falha do auth *e* da compensação) mantém o username ocupado até limpeza manual.
