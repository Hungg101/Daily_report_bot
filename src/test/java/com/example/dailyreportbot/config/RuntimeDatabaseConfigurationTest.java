package com.example.dailyreportbot.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeDatabaseConfigurationTest {

    @Test
    void shouldStartOneDockerOnlyRuntimeWithExplicitCredentialsAndMaskedSecrets() throws IOException {
        String applicationConfiguration = readProjectFile("src/main/resources/application.yml");
        String developmentLauncher = readProjectFile("run-dev.ps1");

        assertThat(applicationConfiguration)
                .contains("url: ${SPRING_DATASOURCE_URL:jdbc:postgresql://localhost:5433/daily_report_bot}")
                .contains("username: ${SPRING_DATASOURCE_USERNAME}")
                .contains("password: ${SPRING_DATASOURCE_PASSWORD}")
                .contains("mini-app-enabled: false")
                .contains("enabled: ${APP_REMINDER_ENABLED:false}")
                .contains("department: ${APP_REMINDER_DEPARTMENT:}")
                .contains("unit: ${APP_REMINDER_UNIT:}")
                .doesNotContain("jdbc:postgresql://localhost:5432/daily_report_bot")
                .doesNotContain("username: ${SPRING_DATASOURCE_USERNAME:")
                .doesNotContain("password: ${SPRING_DATASOURCE_PASSWORD:");
        assertThat(developmentLauncher)
                .contains("TELEGRAM_BOT_USERNAME")
                .contains("TELEGRAM_BOT_TOKEN")
                .contains("SPRING_DATASOURCE_PASSWORD")
                .contains("POSTGRES_SUPERUSER_PASSWORD")
                .contains("Get-NetTCPConnection -LocalPort 8080 -State Listen")
                .contains("Get-CimInstance Win32_Process")
                .contains("com.example.dailyreportbot.DailyReportTelegramBotApplication")
                .contains("$parentProcess.CommandLine -like \"*$PSScriptRoot*\"")
                .contains("spring-boot:run")
                .contains("Stop-Process -Id")
                .contains("docker compose up --build -d")
                .doesNotContain("mvn spring-boot:run")
                .doesNotContain("SPRING_DATASOURCE_USERNAME")
                .doesNotContain("SPRING_DATASOURCE_URL")
                .doesNotContain("Write-Host \" - TELEGRAM_BOT_USERNAME=$env:")
                .doesNotContain("Write-Host \" - TELEGRAM_BOT_TOKEN=$env:")
                .doesNotContain("Write-Host \" - SPRING_DATASOURCE_URL=$env:")
                .doesNotContain("Write-Host \" - SPRING_DATASOURCE_USERNAME=$env:")
                .doesNotContain("Write-Host \" - SPRING_DATASOURCE_PASSWORD=")
                .doesNotContain("Write-Host \" - POSTGRES_SUPERUSER_PASSWORD=");
    }

    @Test
    void shouldKeepComposePostgreSqlLocalAndPassOnlyDedicatedRuntimeCredentials() throws IOException {
        String compose = readProjectFile("docker-compose.yml");

        assertThat(compose)
                .contains("- \"127.0.0.1:5433:5432\"")
                .containsSubsequence(
                        "postgres:",
                        "restart: unless-stopped",
                        "app:",
                        "restart: unless-stopped"
                )
                .contains("POSTGRES_PASSWORD: ${POSTGRES_SUPERUSER_PASSWORD:?")
                .contains("APP_DATABASE_PASSWORD: ${SPRING_DATASOURCE_PASSWORD:?")
                .contains("SPRING_DATASOURCE_USERNAME: daily_report_app")
                .contains("SPRING_DATASOURCE_PASSWORD: ${SPRING_DATASOURCE_PASSWORD:?")
                .contains("TELEGRAM_BOT_MINI_APP_URL: ${TELEGRAM_BOT_MINI_APP_URL:-}")
                .contains("APP_REMINDER_ENABLED: ${APP_REMINDER_ENABLED:-false}")
                .contains("APP_REMINDER_DEPARTMENT: ${APP_REMINDER_DEPARTMENT:-}")
                .contains("APP_REMINDER_UNIT: ${APP_REMINDER_UNIT:-}")
                .contains("./docker/postgres/init/00-create-runtime-role.sh:/docker-entrypoint-initdb.d/00-create-runtime-role.sh:ro")
                .doesNotContain("8080:8080")
                .doesNotContain("POSTGRES_PASSWORD: postgres")
                .doesNotContain("SPRING_DATASOURCE_PASSWORD: postgres");
    }

    @Test
    void shouldKeepThePinnedTunnelOptInAndReadItsTokenOnlyFromAComposeSecret() throws IOException {
        String compose = readProjectFile("docker-compose.yml");

        assertThat(compose)
                .containsSubsequence(
                        "cloudflared:",
                        "image: cloudflare/cloudflared:2026.7.2",
                        "restart: unless-stopped",
                        "profiles:",
                        "- miniapp",
                        "command: tunnel --no-autoupdate run --token-file /run/secrets/cloudflare-tunnel-token",
                        "secrets:",
                        "- cloudflare-tunnel-token"
                )
                .containsSubsequence(
                        "secrets:",
                        "cloudflare-tunnel-token:",
                        "file: ./secrets/cloudflare-tunnel-token.txt"
                )
                .doesNotContain("cloudflare/cloudflared:latest")
                .doesNotContain("TUNNEL_TOKEN:");

        String appService = compose.substring(compose.indexOf("\n  app:"), compose.indexOf("\n  cloudflared:"));
        assertThat(appService).doesNotContain("ports:");
    }

    @Test
    void shouldExcludeTheConfirmedCommonsLoggingTransitivelyFromTelegramBots() throws IOException {
        String projectModel = readProjectFile("pom.xml");

        assertThat(projectModel)
                .containsSubsequence(
                        "<artifactId>telegrambots</artifactId>",
                        "<exclusions>",
                        "<groupId>commons-logging</groupId>",
                        "<artifactId>commons-logging</artifactId>",
                        "</exclusions>"
                );
    }

    @Test
    void shouldBootstrapOnlyTheNonSuperuserRoleNeededForFlywayOwnedSchema() throws IOException {
        String bootstrapScript = readProjectFile("docker/postgres/init/00-create-runtime-role.sh");

        assertThat(bootstrapScript)
                .contains("set -eu")
                .contains(": \"${APP_DATABASE_PASSWORD:?")
                .contains("CREATE ROLE daily_report_app")
                .contains("NOSUPERUSER")
                .contains("NOCREATEDB")
                .contains("NOCREATEROLE")
                .contains("NOREPLICATION")
                .contains("NOBYPASSRLS")
                .contains("NOINHERIT")
                .contains("GRANT CONNECT ON DATABASE daily_report_bot TO daily_report_app")
                .contains("GRANT USAGE, CREATE ON SCHEMA public TO daily_report_app");
    }

    @Test
    void shouldExposeOnlyPlaceholderCredentialsInTheTrackedEnvironmentExample() throws IOException {
        String environmentExample = readProjectFile(".env.example");

        assertThat(environmentExample)
                .contains("SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5433/daily_report_bot")
                .contains("SPRING_DATASOURCE_USERNAME=daily_report_app")
                .contains("SPRING_DATASOURCE_PASSWORD=replace-with-local-app-password")
                .contains("POSTGRES_SUPERUSER_PASSWORD=replace-with-local-bootstrap-password")
                .doesNotContain("jdbc:postgresql://localhost:5432/daily_report_bot")
                .doesNotContain("SPRING_DATASOURCE_PASSWORD=postgres");
        assertThat(environmentExample.lines().toList())
                .contains(
                        "TELEGRAM_BOT_MINI_APP_URL=",
                        "COMPOSE_PROFILES=",
                        "APP_REMINDER_ENABLED=false",
                        "APP_REMINDER_DEPARTMENT=",
                        "APP_REMINDER_UNIT="
                );
    }

    @Test
    void shouldIgnoreOnlyTheOperatorOwnedTunnelTokenFile() throws IOException {
        String gitIgnore = readProjectFile(".gitignore");
        String dockerIgnore = readProjectFile(".dockerignore");

        assertThat(gitIgnore)
                .contains("/secrets/cloudflare-tunnel-token.txt")
                .doesNotContain("\n/secrets/\n");
        assertThat(dockerIgnore)
                .contains("/secrets/cloudflare-tunnel-token.txt")
                .doesNotContain("\n/secrets/\n");
    }

    @Test
    void shouldClearDataThroughTheRunningProjectContainerWithoutComposeInterpolation() throws IOException {
        String clearScript = readProjectFile("clear-dev-data.ps1");

        assertThat(clearScript)
                .contains("[CmdletBinding(SupportsShouldProcess = $true, ConfirmImpact = \"High\")]")
                .contains("label=com.docker.compose.service=postgres")
                .contains("label=com.docker.compose.project.working_dir=$PSScriptRoot")
                .contains("docker exec $postgresContainerId psql")
                .contains("TRUNCATE TABLE users RESTART IDENTITY CASCADE;")
                .contains("Schema and Flyway history were preserved")
                .doesNotContain("docker compose exec")
                .doesNotContain("POSTGRES_SUPERUSER_PASSWORD");
    }

    @Test
    void shouldSeedOnlyMarkerOwnedDemoDataThroughTheRunningProjectContainer() throws IOException {
        Path seedPath = projectPath("seed-demo-data.ps1");
        byte[] seedBytes = Files.readAllBytes(seedPath);
        String seedScript = Files.readString(seedPath);
        String userValues = between(seedScript, "INSERT INTO users (", "ON CONFLICT (phone_number)");
        String reportValues = between(seedScript, "INSERT INTO daily_reports (", ") AS fixture(");

        assertThat(seedBytes).startsWith((byte) 0xEF, (byte) 0xBB, (byte) 0xBF);
        assertThat(seedScript)
                .contains("[CmdletBinding(SupportsShouldProcess = $true, ConfirmImpact = \"Medium\")]")
                .contains("label=com.docker.compose.service=postgres")
                .contains("label=com.docker.compose.project.working_dir=$PSScriptRoot")
                .contains("docker exec $postgresContainerId psql")
                .contains("--set ON_ERROR_STOP=1")
                .contains("flyway_schema_history")
                .contains("version = '11'")
                .contains("BEGIN;")
                .contains("COMMIT;")
                .contains("$PSCmdlet.ShouldProcess")
                .contains("DEMO022")
                .contains("Tiêu đề: ")
                .contains("Người thực hiện: ")
                .contains("Người cùng thực hiện: ")
                .contains("Nội dung: ")
                .contains("performer IS NULL OR btrim(performer) = ''")
                .contains("Hôm nay đã hoàn thành")
                .contains("Phòng Kỹ thuật", "Phòng Tài chính", "Phòng Nhân sự")
                .contains("Nền tảng", "Kiểm thử", "Kế toán", "Kiểm soát", "Tuyển dụng", "Vận hành")
                .containsPattern("COALESCE\\(unit_name, '\\(none\\)'\\)\\s+FROM users u\\s+WHERE u\\.telegram_user_id IN")
                .doesNotContain(".env")
                .doesNotContainIgnoringCase("TRUNCATE")
                .doesNotContainIgnoringCase("DROP ")
                .doesNotContainIgnoringCase("down -v")
                .doesNotContainIgnoringCase("RESTART IDENTITY")
                .doesNotContainIgnoringCase("ALTER SEQUENCE")
                .doesNotContainIgnoringCase("setval(")
                .doesNotContainIgnoringCase("DISABLE TRIGGER")
                .doesNotContainIgnoringCase("INSERT INTO identity_admin_audit_events")
                .doesNotContainIgnoringCase("UPDATE identity_admin_audit_events")
                .doesNotContainIgnoringCase("DELETE FROM identity_admin_audit_events");
        assertThat(seedScript).doesNotContain(
                "Ti\u00c3",
                "Ng\u00c6",
                "\u00c3\u0084\u00e2\u20ac\u02dc",
                "\u00c3\u00a1\u00c2\u00bb",
                "\u00c3\u201a",
                "\u00ef\u00bf\u00bd",
                "\ufffd"
        );
        assertThat(countMatches(userValues, "(?m)^\\s*\\(990220\\d{6}, NULL, NULL,"))
                .isGreaterThanOrEqualTo(25);
        assertThat(countMatches(reportValues, "(?m)^\\s*\\(990220\\d{6}::BIGINT, DATE '\\$selectedReportDate'"))
                .isEqualTo(50);
        assertThat(reportValues).doesNotContain("DEMO022");
    }

    @Test
    void shouldNotPrintTheRealActorIdentifierInSeedEvidence() throws IOException {
        String seedScript = readProjectFile("seed-demo-data.ps1");

        assertThat(seedScript)
                .doesNotContain("$actorPreviewPredicate")
                .doesNotContain("No unique mapped user exists for ActorTelegramUserId $ActorTelegramUserId.");
    }

    private String readProjectFile(String relativePath) throws IOException {
        return Files.readString(projectPath(relativePath));
    }

    private Path projectPath(String relativePath) {
        return Path.of("").toAbsolutePath().resolve(relativePath);
    }

    private String between(String source, String start, String end) {
        int startIndex = source.indexOf(start);
        int endIndex = source.indexOf(end, startIndex);
        assertThat(startIndex).isGreaterThanOrEqualTo(0);
        assertThat(endIndex).isGreaterThan(startIndex);
        return source.substring(startIndex, endIndex);
    }

    private long countMatches(String source, String regex) {
        return Pattern.compile(regex).matcher(source).results().count();
    }
}
