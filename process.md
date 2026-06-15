# Process

## 2026-06-15

### Done

- Tạo Spring Boot 3 Maven project cho Telegram daily report bot.
- Cấu hình Telegram bot dùng Long Polling, đọc username/token từ environment variables.
- Cấu hình PostgreSQL qua environment variables.
- Tạo entity/repository/service cho `TelegramUser`.
- Lưu Telegram user lần đầu nhắn bot, không tạo trùng theo `telegramUserId`.
- Tạo entity/repository/service cho `DailyReport`.
- Implement flow `/report`:
  - Bot hỏi nội dung báo cáo hôm nay.
  - User gửi text thường sau `/report`.
  - Bot lưu báo cáo vào PostgreSQL.
- Chặn gửi trùng báo cáo trong cùng ngày.
- Từ chối nội dung báo cáo rỗng.
- Giữ state `/report` bằng in-memory `ConcurrentHashMap.newKeySet()`.
- Thêm Telegram Mini App demo:
  - Command `/miniapp`.
  - React static frontend tại `/miniapp/`.
  - Telegram WebApp keyboard button để mở UI trong Telegram.
  - Mini App gửi dữ liệu về bot bằng `Telegram.WebApp.sendData(...)`.
  - Bot nhận `web_app_data` và lưu báo cáo bằng `DailyReportService`.
- Thêm route `/miniapp` và `/miniapp/` để tránh Whitelabel 404.
- Thêm `run-dev.ps1` để chạy project bằng một lệnh sau khi cấu hình `.env`.
- Thêm `.env.example` làm mẫu cấu hình local, không chứa secret thật.

### Verified

- `mvn test` pass.
- `mvn -DskipTests package` pass.
- Jar build có static resources:
  - `static/miniapp/index.html`
  - `static/miniapp/app.js`

### Notes

- Telegram Mini App thật cần public HTTPS URL. `localhost` chỉ dùng để preview trong browser.
- Secret như bot token/API key chỉ nên nằm trong `.env` local hoặc environment variables của máy/server.
- Không commit `.env`, token thật, password thật, hoặc API key thật.

### Next

- Demo Telegram Mini App trên Telegram Desktop bằng HTTPS tunnel như ngrok/cloudflared.
- Có thể thêm script riêng để chạy tunnel nếu chọn một tool cố định.
