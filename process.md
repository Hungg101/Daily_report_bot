# Process / Handoff

File này dùng để bắt đầu phiên chat mới. AI agent mới chỉ cần đọc file này trước, rồi tiếp tục làm việc trong project.

## Project

- Tên: Telegram Bot báo cáo công việc hằng ngày.
- Path local: `C:\Users\ADMIN\Downloads\code\Bot_telegram`
- GitHub: `https://github.com/Hungg101/Daily_report_bot`
- Backend: Java 17, Spring Boot 3, Maven.
- Bot: Telegram Bot API bằng Long Polling.
- Database: PostgreSQL.
- Database name: `daily_report_bot`
- PostgreSQL user local: `postgres`
- PostgreSQL password local đang dùng: `postgres`
- Config/secret đọc từ environment variables hoặc `.env`, không hard-code token/API key trong source.

## Important Security Notes

- Không commit `.env`.
- `.env` local có thể chứa token thật, password thật, API key thật.
- `.env.example` chỉ chứa placeholder và được phép commit.
- Token Telegram đã từng bị paste trong chat, nên nên rotate/revoke token cũ bằng BotFather trước khi demo thật.
- Khi rà secret trước khi commit, bỏ qua `target/` và `.git/`.

## Current Files

- `pom.xml`
- `.gitignore`
- `.env.example`
- `.env` local, không commit
- `run-dev.ps1`
- `README.md`
- `process.md`
- `src/main/resources/application.yml`
- `src/main/resources/static/miniapp/index.html`
- `src/main/resources/static/miniapp/app.js`
- `src/main/java/com/example/dailyreportbot/DailyReportTelegramBotApplication.java`
- `src/main/java/com/example/dailyreportbot/bot/DailyReportBot.java`
- `src/main/java/com/example/dailyreportbot/config/TelegramBotConfig.java`
- `src/main/java/com/example/dailyreportbot/config/TelegramBotProperties.java`
- `src/main/java/com/example/dailyreportbot/controller/MiniAppController.java`
- `src/main/java/com/example/dailyreportbot/entity/TelegramUser.java`
- `src/main/java/com/example/dailyreportbot/entity/DailyReport.java`
- `src/main/java/com/example/dailyreportbot/repository/TelegramUserRepository.java`
- `src/main/java/com/example/dailyreportbot/repository/DailyReportRepository.java`
- `src/main/java/com/example/dailyreportbot/service/TelegramUserRegistrationService.java`
- `src/main/java/com/example/dailyreportbot/service/DailyReportService.java`
- `src/main/java/com/example/dailyreportbot/service/DailyReportSubmissionStatus.java`
- `src/main/java/com/example/dailyreportbot/service/TelegramCommandService.java`
- Tests trong `src/test/java/com/example/dailyreportbot/service/`

## Implemented

1. Spring Boot 3 Maven project.
2. Dependencies:
   - Spring Web
   - Spring Boot DevTools
   - Lombok
   - Spring Data JPA
   - PostgreSQL Driver
   - TelegramBots Java library
   - JAXB dependencies cho Java mới.
3. `application.yml` đọc config từ env:
   - `TELEGRAM_BOT_USERNAME`
   - `TELEGRAM_BOT_TOKEN`
   - `TELEGRAM_MINI_APP_URL`
   - `SPRING_DATASOURCE_URL`
   - `SPRING_DATASOURCE_USERNAME`
   - `SPRING_DATASOURCE_PASSWORD`
4. Bot Long Polling đã xử lý:
   - `/start`
   - `/help`
   - `/report`
   - `/miniapp`
   - echo tin nhắn thường.
5. Entity `TelegramUser`:
   - id
   - telegramUserId
   - username
   - firstName
   - createdAt
6. Entity `DailyReport`:
   - id
   - telegramUser
   - reportDate
   - content
   - createdAt
7. Quan hệ:
   - Một `TelegramUser` có nhiều `DailyReport`.
   - Một `DailyReport` thuộc về một `TelegramUser`.
8. Khi user nhắn bot lần đầu:
   - Lưu Telegram user vào PostgreSQL.
   - Không tạo trùng theo `telegramUserId`.
9. Flow `/report`:
   - User gửi `/report`.
   - Bot trả lời: `Vui lòng nhập nội dung báo cáo hôm nay.`
   - User gửi nội dung thường.
   - Bot lưu báo cáo hôm nay.
   - Bot trả lời: `Đã lưu báo cáo hôm nay.`
10. Business rule:
   - Một Telegram user chỉ gửi được một báo cáo mỗi ngày.
   - Nếu gửi lại trong ngày, bot trả lời: `Bạn đã gửi báo cáo hôm nay rồi.`
11. Blank content bị từ chối:
   - Bot trả lời: `Nội dung báo cáo không được để trống.`
12. State `/report` đang dùng in-memory `ConcurrentHashMap.newKeySet()`.
13. Telegram Mini App:
   - `/miniapp` gửi Telegram WebApp keyboard button.
   - React static frontend được serve tại `/miniapp/`.
   - Mini App gửi dữ liệu về bot bằng `Telegram.WebApp.sendData(...)`.
   - Bot nhận `web_app_data`, parse JSON field `content`, rồi lưu qua `DailyReportService`.
   - Có route `/miniapp` và `/miniapp/` trong `MiniAppController` để tránh Whitelabel 404.
14. `run-dev.ps1`:
   - Đọc `.env`.
   - Báo thiếu biến môi trường cần thiết.
   - Ẩn token khi in config.
   - Chạy `mvn spring-boot:run`.
15. Project đã được push lên GitHub.

## Verified

Đã chạy và pass:

```powershell
mvn test
mvn -DskipTests package
```

Lần gần nhất:

- `mvn test`: 16 tests pass.
- `mvn -DskipTests package`: build success.
- Jar có static resources:
  - `BOOT-INF/classes/static/miniapp/index.html`
  - `BOOT-INF/classes/static/miniapp/app.js`

## Local Setup

Nếu chưa có `.env`, tạo từ file mẫu:

```powershell
cd C:\Users\ADMIN\Downloads\code\Bot_telegram
Copy-Item .env.example .env
notepad .env
```

Nội dung `.env` local nên có dạng:

```env
TELEGRAM_BOT_USERNAME=your_bot_username
TELEGRAM_BOT_TOKEN=your_bot_token
TELEGRAM_MINI_APP_URL=http://localhost:8080/miniapp/

SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/daily_report_bot
SPRING_DATASOURCE_USERNAME=postgres
SPRING_DATASOURCE_PASSWORD=postgres
```

Chạy app:

```powershell
.\run-dev.ps1
```

Nếu PowerShell chặn script:

```powershell
powershell -ExecutionPolicy Bypass -File .\run-dev.ps1
```

Khi Spring Boot chạy đúng, terminal sẽ không trả prompt lại. Dừng bằng `Ctrl+C`.

## Demo Bot

1. Đảm bảo PostgreSQL đang chạy.
2. Đảm bảo database tồn tại:

```sql
CREATE DATABASE daily_report_bot;
```

3. Chạy app:

```powershell
.\run-dev.ps1
```

4. Mở Telegram, nhắn bot:

```text
/start
/help
/report
```

5. Test báo cáo:
   - Gửi `/report`.
   - Bot hỏi nội dung.
   - Gửi nội dung, ví dụ: `Hôm nay hoàn thành demo bot`.
   - Bot trả lời đã lưu.
   - Gửi `/report` lần nữa trong cùng ngày để test chặn duplicate.

6. Kiểm tra PostgreSQL:

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

## Demo Mini App

Browser preview local:

```text
http://localhost:8080/miniapp/
```

Lưu ý: `localhost` chỉ preview trên browser, không mở Mini App thật trong Telegram.

Muốn mở Mini App trong Telegram Desktop/mobile:

1. Dùng public HTTPS tunnel, ví dụ:

```powershell
ngrok http 8080
```

2. Copy HTTPS URL, ví dụ:

```text
https://abc.ngrok-free.app
```

3. Sửa `.env`:

```env
TELEGRAM_MINI_APP_URL=https://abc.ngrok-free.app/miniapp/
```

4. Restart app:

```powershell
.\run-dev.ps1
```

5. Trong Telegram gửi:

```text
/miniapp
```

6. Bấm nút `Mở mini app báo cáo` bên dưới khung chat.

## Git Workflow

Remote:

```text
origin https://github.com/Hungg101/Daily_report_bot.git
```

Trước khi commit/push:

```powershell
git status --short
rg -n "TELEGRAM_BOT_TOKEN=.*|[0-9]{8,}:[A-Za-z0-9_-]{20,}" -g "!target/**" -g "!.git/**"
mvn test
```

Commit/push:

```powershell
git add .
git commit -m "Your message"
git push
```

## Things Not Yet Implemented

Không tự thêm nếu user chưa yêu cầu:

- Employee
- Department
- Manager
- Approval
- Scheduler
- Statistics
- Security
- REST API
- Redis
- Flyway/Liquibase
- Full React/Vite build pipeline

## Good Next Tasks

- Demo mini app thật trong Telegram bằng ngrok/cloudflared.
- Thêm script `run-tunnel.ps1` nếu chọn ngrok/cloudflared cố định.
- Cải thiện UI miniapp sau khi demo được luồng gửi thật.
- Thêm command xem báo cáo hôm nay, ví dụ `/myreport`.
- Thêm command hủy nhập báo cáo, ví dụ `/cancel`.
