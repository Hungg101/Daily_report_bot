package com.example.dailyreportbot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.util.Optional;

@ConfigurationProperties(prefix = "telegram.bot")
public class TelegramBotProperties {

    private String username;
    private String token;
    private boolean miniAppEnabled;
    private String miniAppUrl;

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
    public boolean isMiniAppEnabled() { return miniAppEnabled; }
    public void setMiniAppEnabled(boolean miniAppEnabled) { this.miniAppEnabled = miniAppEnabled; }
    public String getMiniAppUrl() { return miniAppUrl; }
    public void setMiniAppUrl(String miniAppUrl) { this.miniAppUrl = miniAppUrl; }

    public Optional<String> validMiniAppUrl() {
        if (!miniAppEnabled || miniAppUrl == null || miniAppUrl.isBlank()) {
            return Optional.empty();
        }
        try {
            URI uri = URI.create(miniAppUrl.strip());
            return uri.isAbsolute() && "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                    ? Optional.of(uri.toString())
                    : Optional.empty();
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }
}
