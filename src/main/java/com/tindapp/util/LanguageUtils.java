package com.tindapp.util;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class LanguageUtils {

    private static final String DEFAULT_LANGUAGE = "ru";

    private static final Map<String, String> LANGUAGE_ALIASES;
    private static final Set<String> SUPPORTED_LANGUAGES;

    static {
        Map<String, String> aliases = new HashMap<>();
        aliases.put("ua", "uk");
        aliases.put("uk", "uk");
        aliases.put("en", "en");
        aliases.put("ru", "ru");
        aliases.put("es", "es");
        aliases.put("ar", "ar");
        aliases.put("de", "de");
        aliases.put("fr", "fr");
        aliases.put("tr", "tr");
        aliases.put("hi", "hi");
        aliases.put("zh", "zh");
        LANGUAGE_ALIASES = Collections.unmodifiableMap(aliases);

        Set<String> supported = new HashSet<>();
        supported.add("en");
        supported.add("ar");
        supported.add("de");
        supported.add("es");
        supported.add("zh");
        supported.add("ru");
        supported.add("tr");
        supported.add("uk");
        supported.add("fr");
        supported.add("hi");
        SUPPORTED_LANGUAGES = Collections.unmodifiableSet(supported);
    }

    private LanguageUtils() {
    }

    public static String normalizeLanguage(String language) {
        if (language == null || language.isBlank()) {
            return DEFAULT_LANGUAGE;
        }
        String lower = language.trim().toLowerCase();
        if (LANGUAGE_ALIASES.containsKey(lower)) {
            return LANGUAGE_ALIASES.get(lower);
        }
        return SUPPORTED_LANGUAGES.contains(lower) ? lower : DEFAULT_LANGUAGE;
    }

    public static boolean isSupportedLanguage(String language) {
        if (language == null) {
            return false;
        }
        return SUPPORTED_LANGUAGES.contains(language.trim().toLowerCase());
    }

    public static boolean canTranslate(String source, String target) {
        if (source == null || target == null) {
            return false;
        }
        String normalizedSource = normalizeLanguage(source);
        String normalizedTarget = normalizeLanguage(target);
        return !normalizedSource.equals(normalizedTarget) && isSupportedLanguage(normalizedTarget);
    }

    public static String getDefaultLanguage() {
        return DEFAULT_LANGUAGE;
    }
}
