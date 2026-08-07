package com.example.dailyreportbot.web;

import com.example.dailyreportbot.config.TelegramBotProperties;
import com.example.dailyreportbot.service.DailyReportService;
import com.example.dailyreportbot.service.DailyReportSubmissionStatus;
import com.example.dailyreportbot.service.ReportSubmissionDetails;
import com.example.dailyreportbot.service.TelegramMiniAppInitDataValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MiniAppReportControllerTest {

    private static final String INIT_DATA = "signed-init-data";
    private static final long TELEGRAM_USER_ID = 12345L;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private TelegramBotProperties properties;
    private TelegramMiniAppInitDataValidator validator;
    private DailyReportService reportService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        properties = new TelegramBotProperties();
        properties.setMiniAppEnabled(true);
        properties.setMiniAppUrl("https://miniapp.example.com/miniapp/index.html");
        validator = mock(TelegramMiniAppInitDataValidator.class);
        reportService = mock(DailyReportService.class);
        mvc = MockMvcBuilders.standaloneSetup(
                new MiniAppReportController(properties, validator, reportService, objectMapper)
        ).build();
    }

    @Test
    void savesOneNormalizedReportForVerifiedActiveUser() throws Exception {
        when(validator.validate(INIT_DATA)).thenReturn(OptionalLong.of(TELEGRAM_USER_ID));
        when(reportService.submitToday(eq(TELEGRAM_USER_ID), any(ReportSubmissionDetails.class)))
                .thenReturn(DailyReportSubmissionStatus.SAVED);

        mvc.perform(post("/api/miniapp/reports")
                        .header("X-Telegram-Init-Data", INIT_DATA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("  Mini App  ", "  Không có  ", "  Hoàn thành  ")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("REPORT_SAVED"))
                .andExpect(jsonPath("$.message").value("Đã lưu báo cáo."));

        ArgumentCaptor<ReportSubmissionDetails> details = ArgumentCaptor.forClass(ReportSubmissionDetails.class);
        verify(reportService).submitToday(eq(TELEGRAM_USER_ID), details.capture());
        assertThat(details.getValue()).isEqualTo(new ReportSubmissionDetails(
                "Mini App", "Không có", "Hoàn thành"
        ));
    }

    @Test
    void rejectsBlankAndOverLimitFieldsWithoutCallingReportService() throws Exception {
        when(validator.validate(INIT_DATA)).thenReturn(OptionalLong.of(TELEGRAM_USER_ID));

        for (Map<String, String> body : List.of(
                Map.of("title", " ", "collaborators", "C", "content", "B"),
                Map.of("title", "T".repeat(201), "collaborators", "C", "content", "B"),
                Map.of("title", "T", "collaborators", "C".repeat(501), "content", "B"),
                Map.of("title", "T", "collaborators", "C", "content", "B".repeat(3_001))
        )) {
            MvcResult result = mvc.perform(post("/api/miniapp/reports")
                            .header("X-Telegram-Init-Data", INIT_DATA)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("FIELD_INVALID"))
                    .andReturn();
            String response = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
            body.values().stream()
                    .filter(value -> value.strip().length() > 20)
                    .forEach(value -> assertThat(response).doesNotContain(value.strip()));
        }

        verifyNoInteractions(reportService);
    }

    @Test
    void rejectsNonStringReportFieldsWithoutJacksonCoercion() throws Exception {
        when(validator.validate(INIT_DATA)).thenReturn(OptionalLong.of(TELEGRAM_USER_ID));

        for (Map<String, ?> body : List.of(
                Map.of("title", 123, "collaborators", "C", "content", "B"),
                Map.of("title", "T", "collaborators", true, "content", "B"),
                Map.of("title", "T", "collaborators", "C", "content", List.of("B"))
        )) {
            mvc.perform(post("/api/miniapp/reports")
                            .header("X-Telegram-Init-Data", INIT_DATA)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("FIELD_INVALID"));
        }

        verifyNoInteractions(reportService);
    }

    @Test
    void returnsTheBoundedFieldEnvelopeForMalformedJson() throws Exception {
        when(validator.validate(INIT_DATA)).thenReturn(OptionalLong.of(TELEGRAM_USER_ID));

        mvc.perform(post("/api/miniapp/reports")
                        .header("X-Telegram-Init-Data", INIT_DATA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FIELD_INVALID"))
                .andExpect(jsonPath("$.message").value("Dữ liệu báo cáo không hợp lệ."));

        verify(reportService, never()).submitToday(any(), any(ReportSubmissionDetails.class));
    }

    @Test
    void authenticatesLaunchEvidenceBeforeParsingMalformedJson() throws Exception {
        when(validator.validate(INIT_DATA)).thenReturn(OptionalLong.empty());

        mvc.perform(post("/api/miniapp/reports")
                        .header("X-Telegram-Init-Data", INIT_DATA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("LAUNCH_INVALID"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("/miniapp")));

        verifyNoInteractions(reportService);
    }

    @Test
    void rejectsMissingOrInvalidLaunchEvidenceBeforeSubmission() throws Exception {
        when(validator.validate(INIT_DATA)).thenReturn(OptionalLong.empty());

        mvc.perform(post("/api/miniapp/reports")
                        .header("X-Telegram-Init-Data", INIT_DATA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("T", "C", "B")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("LAUNCH_INVALID"));
        mvc.perform(post("/api/miniapp/reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("T", "C", "B")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("/miniapp")));

        verifyNoInteractions(reportService);
    }

    @Test
    void rejectsAlteredExpiredAndFutureLaunchEvidenceBeforeSubmission() throws Exception {
        for (String rejectedEvidence : List.of("altered-init-data", "expired-init-data", "future-init-data")) {
            when(validator.validate(rejectedEvidence)).thenReturn(OptionalLong.empty());

            mvc.perform(post("/api/miniapp/reports")
                            .header("X-Telegram-Init-Data", rejectedEvidence)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json("T", "C", "B")))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("LAUNCH_INVALID"))
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("/miniapp")));
        }

        verifyNoInteractions(reportService);
    }

    @Test
    void mapsUnknownInactiveAndRemappedIdentityWithoutClaimingSuccess() throws Exception {
        when(validator.validate(INIT_DATA)).thenReturn(OptionalLong.of(TELEGRAM_USER_ID));

        assertServiceOutcome(DailyReportSubmissionStatus.TELEGRAM_USER_NOT_FOUND, 404, "USER_NOT_REGISTERED");
        assertServiceOutcome(DailyReportSubmissionStatus.USER_INACTIVE, 403, "USER_INACTIVE");
        assertServiceOutcome(DailyReportSubmissionStatus.IDENTITY_CHANGED, 409, "IDENTITY_CHANGED");
    }

    @Test
    void failsClosedWhenMiniAppConfigurationIsDisabled() throws Exception {
        properties.setMiniAppEnabled(false);

        mvc.perform(post("/api/miniapp/reports")
                        .header("X-Telegram-Init-Data", INIT_DATA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("T", "C", "B")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("REPORT_NOT_SAVED"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("/report")));

        verifyNoInteractions(validator, reportService);
    }

    @Test
    void doesNotClaimSuccessWhenPersistenceFails() throws Exception {
        when(validator.validate(INIT_DATA)).thenReturn(OptionalLong.of(TELEGRAM_USER_ID));
        when(reportService.submitToday(eq(TELEGRAM_USER_ID), any(ReportSubmissionDetails.class)))
                .thenThrow(new RuntimeException("database unavailable"));

        mvc.perform(post("/api/miniapp/reports")
                        .header("X-Telegram-Init-Data", INIT_DATA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("T", "C", "B")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("REPORT_NOT_SAVED"));
    }

    @Test
    void requestDebugRepresentationDoesNotExposeReportFields() {
        MiniAppReportController.ReportRequest request = new MiniAppReportController.ReportRequest(
                "private title", "private collaborators", "private content"
        );

        assertThat(request.toString())
                .isEqualTo("ReportRequest[redacted]")
                .doesNotContain(request.title(), request.collaborators(), request.content());
    }

    private void assertServiceOutcome(
            DailyReportSubmissionStatus serviceStatus,
            int expectedHttpStatus,
            String expectedCode
    ) throws Exception {
        reset(reportService);
        when(reportService.submitToday(eq(TELEGRAM_USER_ID), any(ReportSubmissionDetails.class)))
                .thenReturn(serviceStatus);

        mvc.perform(post("/api/miniapp/reports")
                        .header("X-Telegram-Init-Data", INIT_DATA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("T", "C", "B")))
                .andExpect(status().is(expectedHttpStatus))
                .andExpect(jsonPath("$.code").value(expectedCode));

        verify(reportService).submitToday(eq(TELEGRAM_USER_ID), any(ReportSubmissionDetails.class));
    }

    private String json(String title, String collaborators, String content) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "title", title,
                "collaborators", collaborators,
                "content", content
        ));
    }
}
