-- Esquema do profile_service (PostgreSQL). Banco próprio do serviço; nada aqui referencia outro banco.
-- Sem extensões (citext/pg_trgm): o username é sempre gravado em minúsculas pela aplicação.

create sequence legacy_id_seq;                              -- TEMPORÁRIA: id inteiro usado pelos domínios legados (MySQL)

create table profile (
    id            uuid         primary key,                 -- = account.id do authentication_service (o `sub` do JWT)
    legacy_id     integer      not null unique,             -- TEMPORÁRIO (ponte com o MySQL legado)
    username      varchar(60)  not null unique,
    display_name  varchar(120) not null,
    bio           varchar(500),
    avatar_key    varchar(80),                              -- av_<uuid>.jpg, gerado no servidor
    is_private    boolean      not null default false,
    phone         varchar(20),                              -- PII: só o dono vê
    sex           varchar(1)   check (sex in ('M', 'F')),
    birth_date    date,
    status        varchar(10)  not null default 'PENDING' check (status in ('PENDING', 'ACTIVE', 'DELETED')),
    confirmed_at  timestamp with time zone,                 -- quando account.registered.v1 foi recebido
    version       bigint       not null default 1,          -- vai nos eventos; consumidores ignoram versões antigas
    created_at    timestamp with time zone not null,
    updated_at    timestamp with time zone not null
);
create index idx_profile_display_name on profile (lower(display_name) varchar_pattern_ops);
create index idx_profile_updated on profile (updated_at, id);
create index idx_profile_pending on profile (created_at) where status = 'PENDING';

-- Transactional outbox: o evento é gravado na mesma transação da mudança e publicado depois no RabbitMQ.
create table outbox_event (
    seq                bigint generated always as identity primary key,   -- ordem de publicação (não depende de relógio)
    id                 uuid        not null unique,
    type               varchar(80) not null,
    aggregate_id       uuid        not null,
    aggregate_version  bigint      not null,
    payload            text        not null,
    created_at         timestamp with time zone not null,
    published_at       timestamp with time zone
);
create index idx_outbox_unpublished on outbox_event (seq) where published_at is null;

-- Inbox: garante processamento idempotente das mensagens recebidas (entrega é "pelo menos uma vez").
create table processed_message (
    id            uuid primary key,
    processed_at  timestamp with time zone not null
);
