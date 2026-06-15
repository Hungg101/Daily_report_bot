package com.example.dailyreportbot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "telegram.bot")
public class TelegramBotProperties {

    private String username;

    private String token;

    private String miniAppUrl;

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getMiniAppUrl() {
        return miniAppUrl;
    }

    public void setMiniAppUrl(String miniAppUrl) {
        this.miniAppUrl = miniAppUrl;
    }
}
