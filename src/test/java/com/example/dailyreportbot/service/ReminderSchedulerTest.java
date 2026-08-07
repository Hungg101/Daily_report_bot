package com.example.dailyreportbot.service;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ReminderSchedulerTest {

    @Test
    void shouldDelegateEachApprovedSlotWithoutBusinessLogic() {
        ReminderRunService runService = mock(ReminderRunService.class);
        ReminderScheduler scheduler = new ReminderScheduler(runService);

        scheduler.runInitialSlot();
        scheduler.runFirstRetrySlot();
        scheduler.runFinalRetrySlot();

        verify(runService).runInitialSlot();
        verify(runService).runFirstRetrySlot();
        verify(runService).runFinalRetrySlot();
    }

    @Test
    void shouldUseOnlyTheThreeApprovedWeekdayCronPropertiesAndVietnamTimezone() throws Exception {
        assertScheduled("runInitialSlot", "${app.reminder.initial-cron:0 30 16 * * MON-FRI}");
        assertScheduled("runFirstRetrySlot", "${app.reminder.first-retry-cron:0 35 16 * * MON-FRI}");
        assertScheduled("runFinalRetrySlot", "${app.reminder.final-retry-cron:0 50 16 * * MON-FRI}");
    }

    private void assertScheduled(String methodName, String cron) throws Exception {
        Method method = ReminderScheduler.class.getMethod(methodName);
        Scheduled scheduled = method.getAnnotation(Scheduled.class);

        assertThat(scheduled).isNotNull();
        assertThat(scheduled.cron()).isEqualTo(cron);
        assertThat(scheduled.zone()).isEqualTo("${app.reminder.time-zone:Asia/Ho_Chi_Minh}");
    }
}
