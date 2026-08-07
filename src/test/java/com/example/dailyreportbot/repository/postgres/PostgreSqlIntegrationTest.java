package com.example.dailyreportbot.repository.postgres;

import com.example.dailyreportbot.repository.IdentityAuditEventRepository;
import com.example.dailyreportbot.service.DailyReportService;
import com.example.dailyreportbot.service.IdentityAdministrationService;
import com.example.dailyreportbot.service.UserRegistrationService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true",
        "spring.flyway.validate-on-migrate=true",
        "spring.flyway.locations=classpath:db/migration,classpath:db/postgresql-migration"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        IdentityAdministrationService.class,
        IdentityAuditEventRepository.class,
        DailyReportService.class,
        UserRegistrationService.class,
        PostgreSqlIntegrationTest.ClockConfiguration.class
})
public abstract class PostgreSqlIntegrationTest {

    protected static final Duration CONCURRENCY_TIMEOUT = Duration.ofSeconds(15);

    static final PostgreSQLContainer POSTGRES = startPostgreSql();

    protected static ExecutorService executor;

    private static PostgreSQLContainer startPostgreSql() {
        PostgreSQLContainer container = new PostgreSQLContainer(
                DockerImageName.parse("postgres:16-alpine")
        )
                .withDatabaseName("daily_report_bot_test")
                .withUsername("test_user")
                .withPassword("test_password");
        container.start();
        return container;
    }

    @BeforeAll
    static void createExecutor() {
        executor = Executors.newFixedThreadPool(4);
    }

    @AfterAll
    static void stopExecutor() throws InterruptedException {
        executor.shutdownNow();
        executor.awaitTermination(5, TimeUnit.SECONDS);
    }

    @DynamicPropertySource
    static void postgreSqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    protected <T> T inNewTransaction(PlatformTransactionManager transactionManager, Supplier<T> work) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setTimeout((int) CONCURRENCY_TIMEOUT.toSeconds());
        return transaction.execute(status -> work.get());
    }

    @TestConfiguration
    static class ClockConfiguration {
        @Bean
        Clock testClock() {
            return Clock.systemUTC();
        }
    }
}
