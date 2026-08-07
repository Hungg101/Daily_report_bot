package com.example.dailyreportbot.config;

import com.example.dailyreportbot.service.TeamScope;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.reminder")
public class ReminderProperties {

    public static final String APPROVED_TIME_ZONE = "Asia/Ho_Chi_Minh";
    public static final String APPROVED_INITIAL_CRON = "0 30 16 * * MON-FRI";
    public static final String APPROVED_FIRST_RETRY_CRON = "0 35 16 * * MON-FRI";
    public static final String APPROVED_FINAL_RETRY_CRON = "0 50 16 * * MON-FRI";

    private boolean enabled;
    private String department;
    private String unit;
    private String timeZone = APPROVED_TIME_ZONE;
    private String initialCron = APPROVED_INITIAL_CRON;
    private String firstRetryCron = APPROVED_FIRST_RETRY_CRON;
    private String finalRetryCron = APPROVED_FINAL_RETRY_CRON;

    public boolean isApprovedAndEnabled() {
        return enabled
                && normalized(department) != null
                && APPROVED_TIME_ZONE.equals(normalized(timeZone))
                && APPROVED_INITIAL_CRON.equals(normalized(initialCron))
                && APPROVED_FIRST_RETRY_CRON.equals(normalized(firstRetryCron))
                && APPROVED_FINAL_RETRY_CRON.equals(normalized(finalRetryCron));
    }

    public TeamScope approvedScope() {
        if (!isApprovedAndEnabled()) {
            throw new IllegalStateException("Reminder policy is not approved and enabled");
        }
        return new TeamScope(normalized(department), normalized(unit));
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getDepartment() { return department; }
    public void setDepartment(String department) { this.department = department; }
    public String getUnit() { return unit; }
    public void setUnit(String unit) { this.unit = unit; }
    public String getTimeZone() { return timeZone; }
    public void setTimeZone(String timeZone) { this.timeZone = timeZone; }
    public String getInitialCron() { return initialCron; }
    public void setInitialCron(String initialCron) { this.initialCron = initialCron; }
    public String getFirstRetryCron() { return firstRetryCron; }
    public void setFirstRetryCron(String firstRetryCron) { this.firstRetryCron = firstRetryCron; }
    public String getFinalRetryCron() { return finalRetryCron; }
    public void setFinalRetryCron(String finalRetryCron) { this.finalRetryCron = finalRetryCron; }

    private static String normalized(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
