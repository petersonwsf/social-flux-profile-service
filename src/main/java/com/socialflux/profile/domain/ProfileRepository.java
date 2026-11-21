package com.socialflux.profile.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProfileRepository extends JpaRepository<Profile, UUID> {

    Optional<Profile> findByUsername(String username);


    boolean existsByUsername(String username);

    List<Profile> findByIdInAndStatus(Collection<UUID> ids, ProfileStatus status);

    List<Profile> findByStatusAndCreatedAtBefore(ProfileStatus status, Instant before);

    /**
     * Busca por username/nome. Os curingas ({@code %}, {@code _}) e a barra já chegam escapados em {@code contains}/{@code prefix}
     * (a pesquisa do usuário nunca vira padrão). Ordem: username exato, prefixo do username, prefixo do nome, o restante.
     */
    @Query(value = """
            select * from profile p
            where p.status = 'ACTIVE'
              and (p.username like :contains escape '\\' or lower(p.display_name) like :contains escape '\\')
            order by case
                       when p.username = :exact then 0
                       when p.username like :prefix escape '\\' then 1
                       when lower(p.display_name) like :prefix escape '\\' then 2
                       else 3 end,
                     p.username
            limit :limit
            """, nativeQuery = true)
    List<Profile> search(@Param("exact") String exact, @Param("prefix") String prefix,
                         @Param("contains") String contains, @Param("limit") int limit);

    /** Página para o bootstrap de novos consumidores, ordenada por (updated_at, id). */
    @Query(value = """
            select * from profile p
            where p.status = 'ACTIVE' and (p.updated_at, p.id) > (:afterTs, :afterId)
            order by p.updated_at, p.id
            limit :limit
            """, nativeQuery = true)
    List<Profile> pageAfter(@Param("afterTs") Instant afterTs, @Param("afterId") UUID afterId, @Param("limit") int limit);
}
