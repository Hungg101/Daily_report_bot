package com.example.dailyreportbot.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
/**
 * Trình lên lịch cronjob tự động (Scheduler) cho việc nhắc nhở.
 * Kích hoạt định kỳ (hàng ngày) để quét và khởi tạo các tiến trình
 * nhắc nhở nhân viên nộp báo cáo công việc.
 */
public class ReminderScheduler {

    private final ReminderRunService reminderRunService;

    public ReminderScheduler(ReminderRunService reminderRunService) {
        this.reminderRunService = reminderRunService;
    }

    @Scheduled(
            cron = "${app.reminder.initial-cron:0 30 16 * * MON-FRI}",
            zone = "${app.reminder.time-zone:Asia/Ho_Chi_Minh}"
    )
    public void runInitialSlot() { reminderRunService.runInitialSlot(); }

    @Scheduled(
            cron = "${app.reminder.first-retry-cron:0 35 16 * * MON-FRI}",
            zone = "${app.reminder.time-zone:Asia/Ho_Chi_Minh}"
    )
    public void runFirstRetrySlot() { reminderRunService.runFirstRetrySlot(); }

    @Scheduled(
            cron = "${app.reminder.final-retry-cron:0 50 16 * * MON-FRI}",
            zone = "${app.reminder.time-zone:Asia/Ho_Chi_Minh}"
    )
    public void runFinalRetrySlot() { reminderRunService.runFinalRetrySlot(); }
}
