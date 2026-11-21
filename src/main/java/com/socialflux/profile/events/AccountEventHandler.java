package com.socialflux.profile.events;

import com.socialflux.events.EventEnvelope;
import com.socialflux.events.Inbox;
import com.socialflux.profile.service.ProfileService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

/**
 * Reage aos eventos do authentication_service: {@code account.registered.v1} ativa o perfil; {@code account.deleted.v1}
 * apaga os dados pessoais. Idempotente (inbox) e tolerante a tipos desconhecidos (evolução de contrato).
 */
@Component
public class AccountEventHandler {

    private static final Logger log = LoggerFactory.getLogger(AccountEventHandler.class);

    public static final String REGISTERED = "account.registered.v1";
    public static final String DELETED = "account.deleted.v1";

    private final ProfileService profiles;
    private final Inbox inbox;
    private final JsonMapper mapper;

    public AccountEventHandler(ProfileService profiles, Inbox inbox, JsonMapper mapper) {
        this.profiles = profiles;
        this.inbox = inbox;
        this.mapper = mapper;
    }

    public void handle(String json) {
        EventEnvelope event = EventEnvelope.parse(mapper, json);
        UUID accountId = event.aggregateId();

        switch (event.type()) {
            case REGISTERED -> inbox.once(event.id(), () -> profiles.activate(accountId));
            case DELETED -> inbox.once(event.id(), () -> profiles.deleteAccountData(accountId));
            default -> log.debug("Evento ignorado: {}", event.type());
        }
    }
}
