# Arquitetura — profile_service

Serviço de **perfil de usuário**: dados do perfil, busca, foto de perfil (avatar) e privacidade da conta. É um
**resource server** JWT (valida pelo JWKS do authentication_service), publica eventos `profile.*` por outbox.
Spring Boot 4 · Java 21 · Spring Security (OAuth2 Resource Server) · JPA + Flyway · PostgreSQL (banco `profile`) · RabbitMQ · porta **8082**.

API pública/interna, eventos e configuração: [`../README.md`](../README.md). Decisões D1–D12:
[`../../docs/plano-dominio-perfil-servico-proprio.md`](../../docs/plano-dominio-perfil-servico-proprio.md).

## Camadas

```
web (ProfileController, InternalProfileController, MediaController, DTOs, erros)
  └─► service (ProfileService, AvatarService, ProfileRules, RateLimiter)
        ├─► domain (Profile + ProfileRepository) ─► PostgreSQL
        ├─► storage (AvatarProcessor → libs/socialflux-media, StorageService → bucket `avatars` no RustFS) 
        └─► Outbox ─► OutboxRelay ─► RabbitMQ                              (libs/socialflux-events)
                                              └─► outros consumidores
events/AccountEventsListener ◄─ RabbitMQ (account.*) ─► AccountEventHandler ─► ProfileService
security (JwtDecoder via JWKS, JwtAuthorities, OriginCheckFilter, SecurityConfig) envolve tudo.
```

## Árvore de diretórios

```
profile_service/
├── Dockerfile                    build Maven multi-stage (contexto = raiz do repo, para incluir libs/socialflux-events); runtime Temurin 21 JRE, usuário sem root
├── .gitignore                    exclui target/ e arquivos de IDE
├── pom.xml                       dependências (Spring Boot 4, Security/Resource Server, JPA, Flyway, AMQP; testes: embedded-postgres)
├── README.md                     relação com os outros serviços, API, eventos e configuração
├── docs/ARCHITECTURE.md          este arquivo
└── src/
    ├── main/java/com/socialflux/profile/
    │   ├── config/               relógio e propriedades tipadas
    │   ├── domain/               entidade Profile e repositório
    │   ├── events/               topologia RabbitMQ, outbox, inbox e consumo dos eventos da conta
    │   ├── security/             validação do JWT, autoridades, CSRF e cadeia de segurança
    │   ├── service/              regras de perfil e avatar
    │   ├── storage/              processamento e armazenamento de avatares
    │   ├── util/                 UUIDv7
    │   └── web/                  controllers, DTOs e erros
    ├── main/resources/
    │   ├── application.yml       datasource, multipart (5 MB), RabbitMQ (retry + DLQ), Actuator e bloco `profile.*`
    │   └── db/migration/         migrations Flyway
    └── test/java/com/socialflux/profile/   testes de integração e unidade
```

## Arquivos

### Raiz do pacote

| Arquivo | O que representa |
| --- | --- |
| `ProfileServiceApplication.java` | Classe `main`; habilita `@ConfigurationPropertiesScan` e `@EnableScheduling` (relay e limpeza do outbox, limpeza do limitador) |

### `config/`

| Arquivo | O que representa |
| --- | --- |
| `ClockConfig.java` | Bean `Clock` em UTC (injetado em vez de `Instant.now()`, para os testes controlarem o tempo) |
| `ProfileProperties.java` | Record `@ConfigurationProperties(prefix = "profile")` validado: segurança (JWKS, issuer, audiências), origens permitidas, armazenamento (diretório, limites de bytes/pixels), eventos e conexão com o MySQL legado |

### `domain/`

| Arquivo | O que representa |
| --- | --- |
| `Profile.java` | Entidade `profile`: UUID (= conta), username, nome de exibição, bio, chave do avatar, privado, dados pessoais (telefone, sexo, nascimento), status, versão e datas |
| `ProfileRepository.java` | Busca por username / ids, perfis pendentes antigos e a pesquisa por username/nome ordenada por relevância (curingas escapados) |
| `ProfileStatus.java` | Enum `PENDING` (aguardando `account.registered.v1`), `ACTIVE`, `DELETED` (dados pessoais apagados) |

### `events/`

| Arquivo | O que representa |
| --- | --- |
| `Amqp.java` | Filas `profile-service.account-events` (`account.#`) e a sua ligação; o exchange e a DLX/DLQ vêm da biblioteca |
| `AccountEventsListener.java` | `@RabbitListener` da fila de eventos da conta; repassa ao handler |
| `AccountEventHandler.java` | `account.registered.v1` ativa o perfil; `account.deleted.v1` apaga os dados pessoais. Idempotente e tolerante a tipos desconhecidos |

`Outbox`, `OutboxRelay`, `Inbox`, `EventPublisher`/`AmqpEventPublisher` e `EventEnvelope` vêm da biblioteca
[`libs/socialflux-events`](../../libs/socialflux-events/README.md) (`com.socialflux.events.*`, configurada em
`socialflux.events.*`). `ProfileService` grava `profile.created/updated/privacy-changed/deleted.v1` com `Outbox.add`.

### `security/`

| Arquivo | O que representa |
| --- | --- |
| `SecurityConfig.java` | Nega tudo por padrão; `/api/**` exige token de usuário (`ROLE_USER`), `/internal/**` exige token de serviço com escopo, `GET /media/avatars/**` e `/actuator/health` públicos |
| `JwtDecoderConfig.java` | `JwtDecoder` só RS256 pelo JWKS do auth (recarrega em `kid` novo), valida issuer, audiência e expiração |
| `JwtAuthorities.java` | Separa token de usuário (`sub` UUID → `ROLE_*`) de token de serviço (`sub` `svc:` → `SCOPE_*`) |
| `OriginCheckFilter.java` | Defesa CSRF em métodos não seguros (`Sec-Fetch-Site` e `Origin`) |

### `service/`

| Arquivo | O que representa |
| --- | --- |
| `ProfileService.java` | Regras do perfil: criação (interna), leitura com visão pública × do dono, busca, edição, privacidade, ativação, exclusão e descarte de pendentes; cada mudança grava o evento no outbox |
| `AvatarService.java` | Troca da foto: limita a frequência (10/h), processa a imagem, grava com chave nova e só então atualiza o banco; se o banco falhar, apaga o arquivo órfão |
| `ProfileRules.java` | Validações no servidor: formato do username e nomes reservados, nome, bio (≤ 500), telefone, sexo e idade mínima |
| `RateLimiter.java` | Janela deslizante em memória (busca, troca de foto) |

### `storage/`

| Arquivo | O que representa |
| --- | --- |
| `AvatarProcessor.java` | Usa o `ImageProcessor` da biblioteca de mídia (PNG/JPEG/WebP, orientação EXIF, limites antes de decodificar, recorte quadrado em JPEG 256×256 sem metadados) e traduz as recusas para `missing_file`/`file_too_large`/`image_too_large`/`invalid_image`/`servidor_ocupado` |
| `AvatarKeys.java` | Gera (via `MediaKeys`) e valida chaves `av_<uuidv7>.jpg` (o cliente nunca informa nome ou caminho) |
| `StorageService.java` | Interface de armazenamento dos avatares (`put`/`get`/`delete`) |
| `BucketAvatarStorage.java` | Implementação sobre o `MediaStorage` da biblioteca, no bucket `profile.avatars.bucket`; recusa qualquer chave fora do formato `av_<uuid>.jpg` |

### `web/`

| Arquivo | O que representa |
| --- | --- |
| `ProfileController.java` | `GET /api/usuarios` (`?username=`/`?id=`), `GET /api/usuarios/me`, `GET /api/usuarios/search`, `PATCH /api/usuarios/{id}`, `PATCH /api/img/usuarios/{id}` (multipart); quem age vem sempre do token |
| `InternalProfileController.java` | `/internal/profiles`: `PUT`/`DELETE /{uuid}` (`profiles:write`), `GET /{uuid}`, `POST /batch-get` e listagem paginada para bootstrap (`profiles:read`) |
| `MediaController.java` | `GET /media/avatars/{key}`: JPEG com tipo fixo, `nosniff` e cache imutável |
| `Dtos.java` | `PublicProfile` (o que qualquer usuário vê), `OwnerProfile` (inclui dados pessoais) e requisições, com nomes do contrato do frontend |
| `ApiError.java` | Corpo de erro `{ mensagem, sucesso: false, codigo }` |
| `ApiException.java` | Erro de negócio com status e código estável (inclui `TooManyRequests`) |
| `GlobalExceptionHandler.java` | Converte exceções (inclusive upload grande demais e validação) em `ApiError` |

### `src/main/resources/db/migration/`

| Arquivo | O que representa |
| --- | --- |
| `V1__profile.sql` | Tabelas `profile`, `outbox_event`, `processed_message` (e a sequence `legacy_id_seq`, removida na V2) |
| `V2__remove_legacy_id.sql` | Remove `profile.legacy_id` e a sequence `legacy_id_seq`: o id inteiro temporário não existe mais, só o UUID |

### `src/test/java/com/socialflux/profile/`

| Arquivo | O que representa |
| --- | --- |
| `ProfileApiTest.java` | Criação interna, validações, leitura pública × do dono, edição e privacidade |
| `SearchTest.java` | Pesquisa por username/nome, ordem por relevância, escape de curingas |
| `SecurityTest.java` | 401 JSON, só RS256 válido, separação token de usuário × serviço, escopos |
| `AvatarTest.java` | Upload de avatar ponta a ponta (formato, tamanho, chave gerada, entrega em `/media`, foto em pé com orientação EXIF, WebP aceito), com armazenamento em memória |
| `AvatarProcessorTest.java` | Unidade: tradução das recusas da biblioteca, chaves fora do formato recusadas pelo armazenamento do bucket, formato das chaves |
| `EventsTest.java` | Reação a `account.*` (idempotência) e eventos `profile.*` gerados no outbox |
| `support/AbstractProfileTest.java` | Base: serviço em porta aleatória com PostgreSQL embutido e servidor JWKS local de teste; helpers de tokens, contas e outbox |
| `support/Http.java` | Cliente HTTP mínimo dos testes (JSON e multipart) |
| `support/TestSupportConfig.java` | Publicador de eventos em memória (substitui o RabbitMQ e simula broker fora do ar) |

## `.github/workflows/`

| Arquivo | O que faz |
| --- | --- |
| `ci.yml` | CI em cada pull request: lint e, só se passar, testes |
| `cd.yml` | CD após o merge na `main`: repete os testes, publica a imagem no GHCR e atualiza o container na VPS por SSH (secrets listados no `docs/ARCHITECTURE.md` da raiz) |
