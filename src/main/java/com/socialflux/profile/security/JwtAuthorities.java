package com.socialflux.profile.security;

import com.socialflux.profile.config.ProfileProperties;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Converte o JWT em autoridades, separando de forma estrita os dois tipos de token:
 * <ul>
 *   <li><b>usuário</b>: {@code sub} é um UUID e {@code aud} contém a audiência pública → {@code ROLE_*} a partir de {@code roles};</li>
 *   <li><b>serviço</b>: {@code sub} começa com {@code svc:} e {@code aud} contém a audiência do serviço → {@code SCOPE_*} a partir de {@code scope}.</li>
 * </ul>
 * Um token de usuário nunca ganha escopos e um de serviço nunca ganha papéis, então um não serve nas rotas do outro.
 */
@Component
public class JwtAuthorities implements Converter<Jwt, AbstractAuthenticationToken> {

    private final ProfileProperties properties;

    public JwtAuthorities(ProfileProperties properties) {
        this.properties = properties;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        String subject = jwt.getSubject() == null ? "" : jwt.getSubject();
        List<String> audience = jwt.getAudience() == null ? List.of() : jwt.getAudience();

        if (subject.startsWith("svc:")) {
            if (audience.contains(properties.security().serviceAudience())) {
                String scope = jwt.getClaimAsString("scope");
                if (scope != null) {
                    for (String item : scope.split("\\s+")) {
                        if (!item.isBlank()) {
                            authorities.add(new SimpleGrantedAuthority("SCOPE_" + item));
                        }
                    }
                }
            }
        } else if (isUuid(subject) && audience.contains(properties.security().audience())) {
            List<String> roles = jwt.getClaimAsStringList("roles");
            if (roles != null) {
                roles.forEach(role -> authorities.add(new SimpleGrantedAuthority("ROLE_" + role)));
            }
        }
        return new JwtAuthenticationToken(jwt, authorities, subject);
    }

    private static boolean isUuid(String value) {
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
