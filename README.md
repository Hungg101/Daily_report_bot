<div align="center">
  <img src="https://img.shields.io/badge/Spring_Boot-F2F4F9?style=for-the-badge&logo=spring-boot" alt="Spring Boot">
  <img src="https://img.shields.io/badge/PostgreSQL-316192?style=for-the-badge&logo=postgresql&logoColor=white" alt="PostgreSQL">
  <img src="https://img.shields.io/badge/Telegram_Bot-2CA5E0?style=for-the-badge&logo=telegram&logoColor=white" alt="Telegram Bot">
  <img src="https://img.shields.io/badge/Gemini_AI-8E75B2?style=for-the-badge&logo=googlebard&logoColor=white" alt="Gemini AI">
  
  <h1>🤖 Daily Report Telegram Bot</h1>
  <p><b>Hệ thống Bot tự động hóa báo cáo công việc hàng ngày tích hợp Trí tuệ Nhân tạo (Gemini AI)</b></p>
</div>

---

## 🌟 Giới thiệu (Overview)
**Daily Report Bot** là một trợ lý ảo trên nền tảng Telegram, được thiết kế để giải quyết bài toán quản lý và tổng hợp báo cáo công việc hàng ngày cho các tổ chức, doanh nghiệp. 

Thay vì sử dụng các phần mềm cồng kềnh, nhân viên có thể tương tác trực tiếp với Bot như một người bạn để gửi báo cáo. Đặc biệt, hệ thống được tích hợp **AI Google Gemini** để phân tích ngữ cảnh, hỗ trợ người dùng khi họ nhập sai lệnh và giải đáp các thắc mắc thông minh.

## ✨ Tính năng nổi bật (Key Features)

- **📝 Báo cáo dễ dàng:** Gửi báo cáo công việc hằng ngày trực tiếp qua Telegram (chỉ bằng lệnh `/report`).
- **⏰ Tự động nhắc nhở (Reminders):** Hệ thống Cronjob quét và gửi tin nhắn nhắc nhở tự động đến những nhân viên chưa nộp báo cáo (vd: lúc 16:30 hàng ngày).
- **🛡️ Phân quyền chặt chẽ:** Tích hợp hệ thống quản lý danh tính.
  - **Admin:** Chuyển đổi phòng ban, vô hiệu hóa tài khoản, phân quyền.
  - **Manager:** Xem và tổng hợp báo cáo của tất cả thành viên trong team.
  - **Employee:** Chỉ xem và quản lý báo cáo cá nhân.
- **🧠 Trợ lý AI (Gemini):**
  - Tự động bắt lỗi và hướng dẫn thân thiện khi người dùng chat không đúng cú pháp.
  - Lệnh `/ai`: Cho phép người dùng hỏi đáp trực tiếp với AI có nhận thức về ngữ cảnh của nhân viên (tên, chức vụ, phòng ban).

## 🚀 Hướng dẫn cài đặt (Installation)

Bất kỳ ai cũng có thể clone dự án này về và triển khai ngay lập tức.

### Yêu cầu hệ thống (Prerequisites)
- [Java 17](https://www.oracle.com/java/technologies/javase/jdk17-archive-downloads.html) hoặc cao hơn.
- [Maven](https://maven.apache.org/) (để quản lý thư viện và build dự án).
- Cơ sở dữ liệu: PostgreSQL (Khuyên dùng) hoặc H2 (In-memory cho môi trường dev).
- Token của Telegram Bot (Lấy từ [BotFather](https://t.me/botfather)).
- API Key của Google Gemini.

### Các bước triển khai

**1. Clone dự án về máy:**
```bash
git clone https://github.com/Hungg101/Daily_report_bot.git
cd Daily_report_bot
```

**2. Cấu hình biến môi trường:**
Đổi tên file `.env.example` thành `.env` (hoặc cấu hình trực tiếp vào `application.yml`):
```env
TELEGRAM_BOT_TOKEN=your_telegram_bot_token_here
TELEGRAM_BOT_USERNAME=your_bot_username_here
GEMINI_API_KEY=your_gemini_api_key_here
DB_URL=jdbc:postgresql://localhost:5432/daily_report
DB_USERNAME=postgres
DB_PASSWORD=your_password
```

**3. Chạy ứng dụng:**

*Cách 1: Chạy trực tiếp bằng Maven (Dành cho môi trường dev)*
```bash
mvn clean install
mvn spring-boot:run
```

*Cách 2: Chạy bằng Docker Compose (Khuyên dùng cho production/deploy nhanh)*
Dự án đã được cấu hình sẵn môi trường containerized. Bạn chỉ cần cài đặt Docker và chạy 1 lệnh duy nhất:
```bash
docker-compose up -d --build
```
Lệnh này sẽ tự động tải image PostgreSQL, thiết lập Database, build ứng dụng Spring Boot và kết nối chúng lại với nhau.

## 📖 Hướng dẫn sử dụng (Usage)

Khi Bot đã được khởi chạy thành công, mở Telegram và tìm kiếm Username Bot của bạn:

*   `/start` - Khởi động và đăng ký thông tin nhân sự.
*   `/report` - Bắt đầu quá trình nộp báo cáo công việc hôm nay.
*   `/ai [câu hỏi]` - Trò chuyện với trợ lý AI (Ví dụ: `/ai tóm tắt lại công việc hôm nay của tôi`).
*   *(Và các lệnh quản trị dành riêng cho Admin/Manager...)*

## 🛠️ Kiến trúc công nghệ (Tech Stack)
- **Backend Framework:** Spring Boot (Java)
- **Database:** PostgreSQL & H2
- **ORM & Migration:** Hibernate / Spring Data JPA / Flyway
- **Telegram API:** `telegrambots-spring-boot-starter`
- **AI Integration:** Spring AI / Gemini REST API

## 🤝 Đóng góp (Contributing)
Mọi đóng góp (Pull Request, Report Bug, Feature Request) đều được hoan nghênh. Xin vui lòng tạo Issue trước khi tạo Pull Request để cùng thảo luận.

---
<div align="center">
  <i>Được phát triển với ❤️ bởi <a href="https://github.com/Hungg101">Hungg101</a></i>
</div>
