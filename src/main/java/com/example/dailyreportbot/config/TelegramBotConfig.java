package com.example.dailyreportbot.config;

import com.example.dailyreportbot.bot.DailyReportBot;
import com.example.dailyreportbot.service.TelegramCommandService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
        TelegramBotsApi telegramBotsApi = new TelegramBotsApi(DefaultBotSession.class);
        telegramBotsApi.registerBot(dailyReportBot);
        SetMyCommands setMyCommands = new SetMyCommands();
        setMyCommands.setCommands(commandService.createBotCommandMenu());
        setMyCommands.setScope(new BotCommandScopeDefault());
        dailyReportBot.execute(setMyCommands);
        log.info("Telegram bot command menu registered.");
        log.info("Telegram bot registered with long polling.");
        return telegramBotsApi;
    }
}
