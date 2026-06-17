package com.example.dailyreportbot.config;

import com.example.dailyreportbot.bot.DailyReportBot;
import com.example.dailyreportbot.service.TelegramCommandService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands;
import org.telegram.telegrambots.meta.api.objects.commands.scope.BotCommandScopeDefault;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

@Configuration
@EnableConfigurationProperties(TelegramBotProperties.class)
public class TelegramBotConfig {

    private static final Logger log = LoggerFactory.getLogger(TelegramBotConfig.class);

    @Bean
    public TelegramBotsApi telegramBotsApi(
            DailyReportBot dailyReportBot,
            TelegramBotProperties properties,
            TelegramCommandService commandService
    )
            throws TelegramApiException {
        validate(properties);

        TelegramBotsApi telegramBotsApi = new TelegramBotsApi(DefaultBotSession.class);
        telegramBotsApi.registerBot(dailyReportBot);
        registerCommandMenu(dailyReportBot, commandService);
        log.info("Telegram bot '{}' registered with long polling.", properties.getUsername());
        return telegramBotsApi;
    }

    private void registerCommandMenu(DailyReportBot dailyReportBot, TelegramCommandService commandService)
            throws TelegramApiException {
        SetMyCommands setMyCommands = new SetMyCommands();
        setMyCommands.setCommands(commandService.createBotCommandMenu());
        setMyCommands.setScope(new BotCommandScopeDefault());

        dailyReportBot.execute(setMyCommands);
        log.info("Telegram bot command menu registered.");
    }

    private void validate(TelegramBotProperties properties) {
        if (!StringUtils.hasText(properties.getToken())) {
            throw new IllegalStateException("Missing telegram.bot.token or TELEGRAM_BOT_TOKEN.");
        }

        if (!StringUtils.hasText(properties.getUsername())) {
            throw new IllegalStateException("Missing telegram.bot.username or TELEGRAM_BOT_USERNAME.");
        }
    }
}
