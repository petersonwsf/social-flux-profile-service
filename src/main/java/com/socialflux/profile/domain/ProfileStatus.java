package com.socialflux.profile.domain;

public enum ProfileStatus {
    /** Criado pelo cadastro, ainda sem a confirmação (account.registered.v1) do authentication_service. Só o dono enxerga. */
    PENDING,
    ACTIVE,
    /** Conta excluída: dados pessoais apagados; a linha fica como marcador. */
    DELETED
}
