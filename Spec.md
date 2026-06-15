# Development of a Daily Work Reporting and Automated Reminder Bot System

## Mô tả đề tài

Xây dựng hệ thống Telegram Bot hỗ trợ quản lý và báo cáo công việc hằng ngày cho nhân viên trong doanh nghiệp. Hệ thống cho phép nhân viên gửi báo cáo công việc, quản lý theo dõi tiến độ thực hiện, đồng thời tự động nhắc nhở nhân viên hoàn thành báo cáo đúng thời hạn.

Ngoài ra, hệ thống cung cấp các chức năng thống kê và tổng hợp báo cáo theo nhiều khoảng thời gian khác nhau nhằm hỗ trợ công tác quản lý và đánh giá hiệu quả làm việc.

---

## Công nghệ sử dụng

### Ngôn ngữ lập trình

* Java

### Backend

* Spring Boot 3
* Spring Security
* Spring Scheduler
* Spring Data JPA / Hibernate

### Cơ sở dữ liệu

* PostgreSQL hoặc MySQL

### Tích hợp

* Telegram Bot API

---

## Chức năng chính

### 1. Quản lý nhân viên

* Quản lý thông tin nhân viên.
* Quản lý phòng ban.
* Phân quyền người dùng.

### 2. Báo cáo công việc hằng ngày

* Gửi báo cáo công việc qua Telegram Bot.
* Quản lý và lưu trữ báo cáo.
* Theo dõi trạng thái hoàn thành công việc.

### 3. Nhắc nhở tự động

* Tự động gửi thông báo nhắc nộp báo cáo.
* Cấu hình thời gian nhắc nhở.
* Theo dõi tình trạng nộp báo cáo của nhân viên.

### 4. Thống kê và báo cáo

* Báo cáo theo ngày.
* Báo cáo theo tuần.
* Báo cáo theo tháng.
* Báo cáo theo quý.
* Thống kê tiến độ làm việc theo nhân viên và phòng ban.

---

## Kế hoạch thực hiện

| Tuần | Công việc                                     | Sản phẩm đầu ra                             |
| ---- | --------------------------------------------- | ------------------------------------------- |
| 1    | Phân tích yêu cầu và nghiệp vụ                | Requirement Specification Document          |
| 2    | Thiết kế hệ thống và cơ sở dữ liệu            | ERD, Use Case Diagram, Architecture Diagram |
| 3    | Thiết lập môi trường và tích hợp Telegram Bot | Telegram Bot kết nối Backend                |
| 4    | Xây dựng module quản lý nhân viên             | Employee & Department Management            |
| 5    | Xây dựng module báo cáo công việc             | Report Submission & Management              |
| 6    | Xây dựng module nhắc nhở tự động              | Scheduled Reminder Notifications            |
| 7    | Xây dựng module thống kê và báo cáo           | Daily, Weekly, Monthly, Quarterly Reports   |
| 8    | Kiểm thử, triển khai và hoàn thiện báo cáo    | System Demo & Internship Report             |

---

## Kết quả mong đợi

* Telegram Bot hoạt động hoàn chỉnh phục vụ báo cáo công việc hằng ngày.
* Hệ thống nhắc nhở tự động cho nhân viên.
* Theo dõi tiến độ công việc theo thời gian thực.
* Tổng hợp và thống kê báo cáo theo nhiều khoảng thời gian.
* Vận dụng thực tế các công nghệ:

  * Spring Boot
  * Spring Security
  * Spring Scheduler
  * Spring Data JPA / Hibernate
  * PostgreSQL/MySQL
  * Telegram Bot API

---

## Kiến thức đạt được

* Phân tích và thiết kế hệ thống phần mềm.
* Thiết kế cơ sở dữ liệu quan hệ.
* Xây dựng REST API bằng Spring Boot.
* Quản lý xác thực và phân quyền bằng Spring Security.
* Lập lịch tác vụ tự động với Spring Scheduler.
* Tích hợp Telegram Bot API.
* Triển khai và vận hành hệ thống trong môi trường doanh nghiệp.
