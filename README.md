# Daily Report Telegram Bot

Spring Boot 3 project for a Telegram Bot that receives daily work reports through long polling.

## Tech Stack

- Java 17+
- Spring Boot 3
- Maven
- Spring Web
- Spring Boot DevTools
- Lombok
- Spring Data JPA
- PostgreSQL Driver
- TelegramBots Java library
- React 18 static frontend for the Telegram Mini App

The app uses Spring Data JPA with PostgreSQL and `spring.jpa.hibernate.ddl-auto=update` for the current demo schema.

## Create A Telegram Bot

1. Open Telegram and search for `@BotFather`.
2. Send `/newbot`.
3. Enter the display name for the bot.
4. Enter the bot username. It must end with `bot`, for example `daily_report_demo_bot`.
5. BotFather returns a token. Keep it private and do not commit it to git.

## Configure Bot

The bot reads config from environment variables:

- `TELEGRAM_BOT_USERNAME`
- `TELEGRAM_BOT_TOKEN`
- `TELEGRAM_MINI_APP_URL`

PowerShell:

```powershell
$env:TELEGRAM_BOT_USERNAME="your_bot_username"
$env:TELEGRAM_BOT_TOKEN="123456789:your-real-token"
$env:TELEGRAM_MINI_APP_URL="https://your-public-domain.example/miniapp/"
```

Git Bash, Linux, or macOS:

```bash
export TELEGRAM_BOT_USERNAME="your_bot_username"
export TELEGRAM_BOT_TOKEN="123456789:your-real-token"
export TELEGRAM_MINI_APP_URL="https://your-public-domain.example/miniapp/"
```

You can also put local values in a file such as `src/main/resources/application-local.yml` and run with `--spring.profiles.active=local`. That file is ignored by git.

Example local file:

```yaml
telegram:
  bot:
    username: your_bot_username
    token: 123456789:your-real-token
    mini-app-url: https://your-public-domain.example/miniapp/
```

## Configure PostgreSQL

Create a PostgreSQL database:

```sql
CREATE DATABASE daily_report_bot;
```

The app reads database config from these environment variables:

- `SPRING_DATASOURCE_URL`
- `SPRING_DATASOURCE_USERNAME`
- `SPRING_DATASOURCE_PASSWORD`

PowerShell example:

```powershell
$env:SPRING_DATASOURCE_URL="jdbc:postgresql://localhost:5432/daily_report_bot"
$env:SPRING_DATASOURCE_USERNAME="postgres"
$env:SPRING_DATASOURCE_PASSWORD="postgres"
```

By default, `application.yml` uses the same local values above and `spring.jpa.hibernate.ddl-auto=update`, so Hibernate will create or update the demo tables when the app starts.

## Run The Project

```bash
mvn spring-boot:run
```

For local development, you can put environment variables in `.env` and start the app with one PowerShell command:

```powershell
Copy-Item .env.example .env
notepad .env
.\run-dev.ps1
```

After `.env` is configured, the daily command is just:

```powershell
.\run-dev.ps1
```

If PowerShell blocks local scripts on your machine, use this one-command fallback:

```powershell
powershell -ExecutionPolicy Bypass -File .\run-dev.ps1
```

If you use `application-local.yml`:

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

When the app starts successfully, the console should show that the Telegram bot was registered with long polling.

## Mini App

The mini app UI is a React 18 static frontend served by Spring Boot from:

```text
http://localhost:8080/miniapp/
```

Send `/miniapp` to the bot to receive a Telegram WebApp keyboard button. Telegram opens the React UI inside its own WebView, not as a normal browser tab.

Telegram Mini Apps need an HTTPS public URL, so for a real Telegram Desktop/mobile test set `TELEGRAM_MINI_APP_URL` to a public HTTPS URL that points to `/miniapp/`. During local development, the default URL is `http://localhost:8080/miniapp/` only for browser preview.

If `/miniapp/` shows Whitelabel 404 after code changes, restart `mvn spring-boot:run` so Spring copies the latest static files to `target/classes`.

## Test On Telegram

Open your bot chat in Telegram and send:

- `/start`
- `/help`
- `/report`
- `/miniapp`
- any normal text message

Expected responses:

- `/start`: `Xin chào! Đây là bot báo cáo công việc hằng ngày.`
- `/help`: list of `/start`, `/help`, `/report`, `/miniapp`
- `/report`: `Vui lòng nhập nội dung báo cáo hôm nay.`
- `/miniapp`: sends a Telegram keyboard button that opens the mini app inside Telegram
- normal text: `Bot đã nhận: <your message>`

The console logs these fields for every text message or mini app submission:

- Telegram User ID
- Username
- Chat ID
- Message text or mini app payload

## Test Daily Report Submission

1. Send `/report`.
2. Bot replies: `Vui lòng nhập nội dung báo cáo hôm nay.`
3. Send today's report content as a normal text message.
4. Bot replies: `Đã lưu báo cáo hôm nay.`
5. Send `/report` again on the same day.
6. Bot replies: `Bạn đã gửi báo cáo hôm nay rồi.`

Blank report content is rejected with:

```text
Nội dung báo cáo không được để trống.
```

Mini app submission uses the same daily-report rule, so one Telegram user can still submit only one report per day.

Check PostgreSQL:

```sql
SELECT
  dr.id,
  tu.telegram_user_id,
  tu.username,
  dr.report_date,
  dr.content,
  dr.created_at
FROM daily_reports dr
JOIN telegram_users tu ON tu.id = dr.telegram_user_id
ORDER BY dr.created_at DESC;
```

## Next Steps

- Add migration management with Flyway or Liquibase when the schema becomes stable.
- Add employee and department modules.
- Add scheduled reminders.
- Add statistics and report views.
