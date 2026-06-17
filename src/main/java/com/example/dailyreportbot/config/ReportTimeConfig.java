package com.example.dailyreportbot.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

@Configuration
public class ReportTimeConfig {

    @Bean
    public Clock reportClock(@Value("${app.report-time-zone:Asia/Ho_Chi_Minh}") String reportTimeZone) {
        return Clock.system(ZoneId.of(reportTimeZone));
    }
}
