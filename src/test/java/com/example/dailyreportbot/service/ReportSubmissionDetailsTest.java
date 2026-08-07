package com.example.dailyreportbot.service;

import com.example.dailyreportbot.entity.User;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReportSubmissionDetailsTest {

    @Test
    void normalizesAndSerializesTheExistingReportFormat() {
        ReportSubmissionDetails details = new ReportSubmissionDetails(
                "  Kế hoạch quý III  ",
                "  Trần Thị B  ",
                "  Hoàn thành API  "
        );

        assertThat(details.title()).isEqualTo("Kế hoạch quý III");
        assertThat(details.collaborators()).isEqualTo("Trần Thị B");
        assertThat(details.content()).isEqualTo("Hoàn thành API");
        assertThat(details.serialize("Nguyễn Văn A")).isEqualTo("""
                Tiêu đề: Kế hoạch quý III
                Người thực hiện: Nguyễn Văn A
                Người cùng thực hiện: Trần Thị B
                Nội dung:
                Hoàn thành API""");
    }

    @Test
    void keepsTheApprovedSoloValue() {
        ReportSubmissionDetails details = new ReportSubmissionDetails(
                "Solo", ReportSubmissionDetails.SOLO_COLLABORATORS, "Done"
        );

        assertThat(details.collaborators()).isEqualTo("Không có");
    }

    @Test
    void appliesExactFieldLimits() {
        assertThat(new ReportSubmissionDetails("T".repeat(200), "C".repeat(500), "B".repeat(3_000)))
                .isNotNull();

        assertInvalid("title", () -> new ReportSubmissionDetails("T".repeat(201), "C", "B"));
        assertInvalid("collaborators", () -> new ReportSubmissionDetails("T", "C".repeat(501), "B"));
        assertInvalid("content", () -> new ReportSubmissionDetails("T", "C", "B".repeat(3_001)));
    }

    @Test
    void rejectsEveryBlankField() {
        assertInvalid("title", () -> new ReportSubmissionDetails(" ", "C", "B"));
        assertInvalid("collaborators", () -> new ReportSubmissionDetails("T", " ", "B"));
        assertInvalid("content", () -> new ReportSubmissionDetails("T", "C", null));
    }

    @Test
    void derivesPerformerOnlyFromTheTrustedUser() {
        User user = new User();
        user.setFullName("  Nguyễn Văn A  ");

        assertThat(ReportSubmissionDetails.primaryPerformer(user, 12345L))
                .isEqualTo("Nguyễn Văn A");

        user.setFullName(null);
        user.setFirstName("An");
        assertThat(ReportSubmissionDetails.primaryPerformer(user, 12345L)).isEqualTo("An");

        user.setFirstName(null);
        user.setUsername("reporter");
        assertThat(ReportSubmissionDetails.primaryPerformer(user, 12345L)).isEqualTo("@reporter");

        user.setUsername(null);
        assertThat(ReportSubmissionDetails.primaryPerformer(user, 12345L))
                .isEqualTo("Telegram user ID 12345");
    }

    private void assertInvalid(String field, Runnable construction) {
        assertThatThrownBy(construction::run)
                .isInstanceOf(ReportSubmissionDetails.ValidationException.class)
                .extracting(exception -> ((ReportSubmissionDetails.ValidationException) exception).field())
                .isEqualTo(field);
    }
}
