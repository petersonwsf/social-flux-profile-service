package com.socialflux.profile.service;

import com.socialflux.profile.web.ApiException;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Regras de validação do perfil (executadas SEMPRE no servidor, qualquer que seja o cliente). */
public final class ProfileRules {

    /** 3–30 caracteres; só a-z, 0-9, ponto e sublinhado; começa e termina com letra/número; sem pontos seguidos. */
    private static final Pattern USERNAME = Pattern.compile("^[a-z0-9](?:[a-z0-9._]{1,28})[a-z0-9]$");

    /** Nomes que não podem ser escolhidos (rotas, papéis e marcas do sistema). */
    private static final Set<String> RESERVED = Set.of(
            "admin", "administrator", "root", "system", "support", "suporte", "api", "auth", "login", "logout",
            "register", "perfil", "profile", "me", "search", "settings", "help", "socialflux", "social.flux",
            "moderator", "null", "undefined", "internal", "media");

    public static final int BIO_MAX = 500;

    private ProfileRules() {
    }

    public static String normalizeUsername(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    }

    public static void validateUsername(String username) {
        if (!USERNAME.matcher(username).matches() || username.contains("..")) {
            throw ApiException.badRequest("invalid_username",
                    "Username inválido: use de 3 a 30 caracteres minúsculos (a-z, 0-9, ponto ou underline), "
                            + "sem espaços, e comece/termine com letra ou número");
        }
        if (RESERVED.contains(username)) {
            throw ApiException.badRequest("reserved_username", "Esse username não está disponível");
        }
    }

    public static String validateDisplayName(String raw) {
        String name = raw == null ? "" : raw.trim();
        if (name.length() < 2 || name.length() > 120) {
            throw ApiException.badRequest("invalid_name", "Informe seu nome (2 a 120 caracteres)");
        }
        return name;
    }

    /** Devolve a biografia normalizada (vazia vira {@code null}). */
    public static String validateBio(String raw) {
        if (raw == null) {
            return null;
        }
        String bio = raw.strip();
        if (bio.length() > BIO_MAX) {
            throw ApiException.badRequest("invalid_bio", "A biografia pode ter no máximo " + BIO_MAX + " caracteres");
        }
        return bio.isEmpty() ? null : bio;
    }

    /** Devolve só os dígitos do telefone. */
    public static String validatePhone(String raw) {
        String digits = raw == null ? "" : raw.replaceAll("\\D", "");
        if (digits.length() < 10 || digits.length() > 15) {
            throw ApiException.badRequest("invalid_phone", "Telefone inválido, use o formato XX XXXXX-XXXX");
        }
        return digits;
    }

    public static String validateSex(String raw) {
        if (!"M".equals(raw) && !"F".equals(raw)) {
            throw ApiException.badRequest("invalid_sex", "Selecione uma opção de sexo");
        }
        return raw;
    }

    public static LocalDate validateBirthDate(LocalDate birthDate, Clock clock) {
        if (birthDate == null || birthDate.isAfter(LocalDate.now(clock).minusYears(18))) {
            throw ApiException.badRequest("underage", "Você deve ter no mínimo 18 anos");
        }
        if (birthDate.isBefore(LocalDate.now(clock).minusYears(120))) {
            throw ApiException.badRequest("invalid_birth_date", "Data de nascimento inválida");
        }
        return birthDate;
    }
}
