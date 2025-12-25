package com.tindapp.service;

import com.tindapp.config.AppConfig;
import com.tindapp.model.Message;
import com.tindapp.util.LanguageUtils;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

public class TranslationService {

    private static final Logger logger = LoggerFactory.getLogger(TranslationService.class);

    private final HttpClient httpClient;
    private final String endpoint;

    public TranslationService() {
        this(AppConfig.TRANSLATION_API_URL);
    }

    public TranslationService(final String endpoint) {
        this.endpoint = endpoint;
        httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    }

    public Optional<Message.MessageTranslation> translate(final String text, final String sourceLanguage, final String targetLanguage) {
        if (text == null || text.trim().isEmpty()) {
            return Optional.empty();
        }

        final String normalizedTarget = LanguageUtils.normalizeLanguage(targetLanguage);
        final String normalizedSource = LanguageUtils.normalizeLanguage(sourceLanguage);

        if (!LanguageUtils.canTranslate(normalizedSource, normalizedTarget)) {
            return Optional.empty();
        }

        try {
            final JsonObject payload = new JsonObject()
                .put("q", text)
                .put("source", normalizedSource)
                .put("target", normalizedTarget)
                .put("format", "text")
                .put("alternatives", 0)
                .put("api_key", System.getenv("TRANSLATION_API_KEY"));

            final HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.encode(), StandardCharsets.UTF_8))
                .build();

            final HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                logger.warn("Translation request failed with status {}: {}", response.statusCode(), response.body());
                return Optional.empty();
            }

            final JsonObject json = new JsonObject(response.body());
            final String translatedText = json.getString("translatedText");
            if (translatedText == null || translatedText.isBlank()) {
                return Optional.empty();
            }

            final Message.MessageTranslation translation = new Message.MessageTranslation(
                normalizedTarget,
                normalizedSource,
                translatedText
            );
            return Optional.of(translation);
        } catch (final Exception e) {
            logger.warn("Failed to translate text", e);
            return Optional.empty();
        }
    }
}
