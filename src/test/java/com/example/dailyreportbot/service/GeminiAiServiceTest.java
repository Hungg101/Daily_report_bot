package com.example.dailyreportbot.service;

import com.example.dailyreportbot.entity.User;
import com.example.dailyreportbot.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class GeminiAiServiceTest {

        private MockRestServiceServer mockServer;
        private GeminiAiService service;
        private TeamReportReadService teamReportReadService;
        private UserRepository userRepository;
        private IdentityAdministrationService identityAdministrationService;

        @BeforeEach
        void setUp() {
                RestClient.Builder builder = RestClient.builder()
                                .baseUrl("https://generativelanguage.googleapis.com");
                mockServer = MockRestServiceServer.bindTo(builder).build();
                RestClient restClient = builder.build();
                teamReportReadService = mock(TeamReportReadService.class);
                userRepository = mock(UserRepository.class);
                identityAdministrationService = mock(IdentityAdministrationService.class);
                service = new GeminiAiService("test-api-key", "gemini-flash-latest", "Asia/Ho_Chi_Minh",
                                Clock.system(ZoneId.of("Asia/Ho_Chi_Minh")), restClient, teamReportReadService,
                                userRepository, identityAdministrationService);
        }

        @Test
        void getGuidanceResponse_returnsAiText_whenApiSucceeds() {
                String responseJson = """
                                {
                                  "candidates": [{
                                    "content": {
                                      "parts": [{"text": "Xin chào! Tôi là trợ lý bot báo cáo."}]
                                    }
                                  }]
                                }
                                """;

                mockServer.expect(requestTo(
                                "https://generativelanguage.googleapis.com/v1beta/models/gemini-flash-latest:generateContent?key=test-api-key"))
                                .andExpect(content().string(org.hamcrest.Matchers.containsString("/status")))
                                .andRespond(withSuccess(responseJson, MediaType.APPLICATION_JSON));

                String result = service.getGuidanceResponse(123L, "Xin chào", null);
                assertNotNull(result);
                assertEquals("Xin chào! Tôi là trợ lý bot báo cáo.", result);
                mockServer.verify();
        }

        @Test
        void getGuidanceResponse_usesGemini36Flash_whenModelIsBlank() {
                RestClient.Builder builder = RestClient.builder()
                                .baseUrl("https://generativelanguage.googleapis.com");
                MockRestServiceServer defaultModelServer = MockRestServiceServer.bindTo(builder).build();
                GeminiAiService defaultModelService = new GeminiAiService(
                                "test-api-key",
                                " ",
                                "Asia/Ho_Chi_Minh",
                                Clock.system(ZoneId.of("Asia/Ho_Chi_Minh")),
                                builder.build(),
                                teamReportReadService,
                                userRepository,
                                identityAdministrationService);
                defaultModelServer.expect(requestTo(
                                "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.6-flash:generateContent?key=test-api-key"))
                                .andRespond(withSuccess(
                                                "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"OK\"}]}}]}",
                                                MediaType.APPLICATION_JSON));

                assertEquals("OK", defaultModelService.getGuidanceResponse(123L, "Xin chào", null));
                defaultModelServer.verify();
        }

        @Test
        void getGuidanceResponse_returnsNull_whenApiKeyIsEmpty() {
                GeminiAiService disabledService = new GeminiAiService("", "gemini-flash-latest", "Asia/Ho_Chi_Minh",
                                Clock.system(ZoneId.of("Asia/Ho_Chi_Minh")), RestClient.create(), teamReportReadService,
                                userRepository, identityAdministrationService);
                assertNull(disabledService.getGuidanceResponse(123L, "Hello", null));
                assertFalse(disabledService.isEnabled());
        }

        @Test
        void getGuidanceResponse_returnsNull_whenApiKeyIsNull() {
                GeminiAiService disabledService = new GeminiAiService(null, "gemini-flash-latest", "Asia/Ho_Chi_Minh",
                                Clock.system(ZoneId.of("Asia/Ho_Chi_Minh")), RestClient.create(), teamReportReadService,
                                userRepository, identityAdministrationService);
                assertNull(disabledService.getGuidanceResponse(123L, "Hello", null));
                assertFalse(disabledService.isEnabled());
        }

        @Test
        void getGuidanceResponse_returnsNull_whenChatIdIsNull() {
                assertNull(service.getGuidanceResponse(null, "Hello", null));
        }

        @Test
        void getGuidanceResponse_returnsNull_whenUserMessageIsBlank() {
                assertNull(service.getGuidanceResponse(123L, "", null));
                assertNull(service.getGuidanceResponse(123L, "   ", null));
                assertNull(service.getGuidanceResponse(123L, null, null));
        }

        @Test
        void getGuidanceResponse_returnsNull_whenApiReturnsError() {
                mockServer.expect(requestTo(
                                "https://generativelanguage.googleapis.com/v1beta/models/gemini-flash-latest:generateContent?key=test-api-key"))
                                .andRespond(withServerError());

                String result = service.getGuidanceResponse(123L, "Hello", null);
                assertNull(result);
                mockServer.verify();
        }

        @Test
        void getGuidanceResponse_returnsNull_whenResponseHasNoCandidates() {
                String responseJson = """
                                {
                                  "candidates": []
                                }
                                """;

                mockServer.expect(requestTo(
                                "https://generativelanguage.googleapis.com/v1beta/models/gemini-flash-latest:generateContent?key=test-api-key"))
                                .andRespond(withSuccess(responseJson, MediaType.APPLICATION_JSON));

                assertNull(service.getGuidanceResponse(123L, "Hello", null));
                mockServer.verify();
        }

        @Test
        void getGuidanceResponse_maintainsHistoryPerChat() {
                String response1 = """
                                {"candidates":[{"content":{"parts":[{"text":"Trả lời 1"}]}}]}
                                """;
                String response2 = """
                                {"candidates":[{"content":{"parts":[{"text":"Trả lời 2"}]}}]}
                                """;

                mockServer.expect(requestTo(
                                "https://generativelanguage.googleapis.com/v1beta/models/gemini-flash-latest:generateContent?key=test-api-key"))
                                .andRespond(withSuccess(response1, MediaType.APPLICATION_JSON));
                mockServer.expect(requestTo(
                                "https://generativelanguage.googleapis.com/v1beta/models/gemini-flash-latest:generateContent?key=test-api-key"))
                                .andRespond(withSuccess(response2, MediaType.APPLICATION_JSON));

                String result1 = service.getGuidanceResponse(100L, "Câu hỏi 1", null);
                assertEquals("Trả lời 1", result1);

                String result2 = service.getGuidanceResponse(100L, "Câu hỏi 2", null);
                assertEquals("Trả lời 2", result2);

                mockServer.verify();
        }

        @Test
        void getGuidanceResponse_isolatesHistoryBetweenChats() {
                String responseA = """
                                {"candidates":[{"content":{"parts":[{"text":"Trả lời chat A"}]}}]}
                                """;
                String responseB = """
                                {"candidates":[{"content":{"parts":[{"text":"Trả lời chat B"}]}}]}
                                """;

                mockServer.expect(requestTo(
                                "https://generativelanguage.googleapis.com/v1beta/models/gemini-flash-latest:generateContent?key=test-api-key"))
                                .andRespond(withSuccess(responseA, MediaType.APPLICATION_JSON));
                mockServer.expect(requestTo(
                                "https://generativelanguage.googleapis.com/v1beta/models/gemini-flash-latest:generateContent?key=test-api-key"))
                                .andRespond(withSuccess(responseB, MediaType.APPLICATION_JSON));

                assertEquals("Trả lời chat A", service.getGuidanceResponse(100L, "Hello A", null));
                assertEquals("Trả lời chat B", service.getGuidanceResponse(200L, "Hello B", null));

                mockServer.verify();
        }

        @Test
        void isEnabled_returnsTrue_whenApiKeyIsConfigured() {
                assertTrue(service.isEnabled());
        }

        @Test
        void getGuidanceResponse_evictsOldHistory_whenExceedingLimit() {
                for (int i = 1; i <= 11; i++) {
                        String response = String.format(
                                        "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Reply %d\"}]}}]}", i);
                        mockServer.expect(requestTo(
                                        "https://generativelanguage.googleapis.com/v1beta/models/gemini-flash-latest:generateContent?key=test-api-key"))
                                        .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
                }

                for (int i = 1; i <= 11; i++) {
                        String result = service.getGuidanceResponse(300L, "Message " + i, null);
                        assertEquals("Reply " + i, result);
                }

                mockServer.verify();
        }

        @Test
        void answerUserQuery_returnsText_whenNoFunctionCall() {
                String responseJson = """
                                {
                                  "candidates": [{
                                    "content": {
                                      "parts": [{"text": "Báo cáo của bạn"}]
                                    }
                                  }]
                                }
                                """;

                mockServer.expect(requestTo(
                                "https://generativelanguage.googleapis.com/v1beta/models/gemini-flash-latest:generateContent?key=test-api-key"))
                                .andRespond(withSuccess(responseJson, MediaType.APPLICATION_JSON));

                User user = new User();
                user.setId(1L);
                String result = service.answerUserQuery(123L, "Xin chào", null, user);
                assertNotNull(result);
                assertEquals("Báo cáo của bạn", result);
                mockServer.verify();
        }

        @Test
        void answerUserQuery_returnsNull_whenInputsAreInvalid() {
                assertNull(service.answerUserQuery(null, "Hello", null, new User()));
                assertNull(service.answerUserQuery(123L, null, null, new User()));
                assertNull(service.answerUserQuery(123L, "", null, new User()));
        }

        @Test
        void answerUserQuery_handlesFunctionCall_andReturnsFinalText() {
                String callJson = """
                                {
                                  "candidates": [{
                                    "content": {
                                      "parts": [{"functionCall": {"name": "get_report_summary", "args": {"date": "2026-06-17", "department": "IT"}, "id": "call_123"}}]
                                    }
                                  }]
                                }
                                """;

                String followUpJson = """
                                {
                                  "candidates": [{
                                    "content": {
                                      "parts": [{"text": "Báo cáo IT có 5 người"}]
                                    }
                                  }]
                                }
                                """;

                mockServer.expect(requestTo(
                                "https://generativelanguage.googleapis.com/v1beta/models/gemini-flash-latest:generateContent?key=test-api-key"))
                                .andRespond(withSuccess(callJson, MediaType.APPLICATION_JSON));

                mockServer.expect(requestTo(
                                "https://generativelanguage.googleapis.com/v1beta/models/gemini-flash-latest:generateContent?key=test-api-key"))
                                .andExpect(content()
                                                .string(org.hamcrest.Matchers.containsString("\"id\":\"call_123\"")))
                                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"role\":\"user\"")))
                                .andRespond(withSuccess(followUpJson, MediaType.APPLICATION_JSON));

                TeamReportSummary summary = mock(TeamReportSummary.class);
                when(teamReportReadService.readTeamSummary(any(), any(), any(), any()))
                                .thenReturn(summary);

                User admin = new User();
                admin.setId(1L);
                admin.setTelegramUserId(1001L);
                admin.setAdmin(true);
                when(userRepository.findByTelegramUserId(1001L)).thenReturn(java.util.Optional.of(admin));

                String result = service.answerUserQuery(123L, "Báo cáo hôm nay", null, admin);
                assertEquals("Báo cáo IT có 5 người", result);
                mockServer.verify();
        }

        @Test
        void answerUserQuery_handlesMultipleFunctionCalls_andReturnsFinalText() {
                String callJson = """
                                {
                                  "candidates": [{
                                    "content": {
                                      "parts": [
                                          {"functionCall": {"name": "get_report_summary", "args": {"date": "2026-06-17", "department": "IT"}, "id": "call_123"}},
                                          {"functionCall": {"name": "get_report_summary", "args": {"date": "2026-06-18", "department": "IT"}, "id": "call_456"}}
                                      ]
                                    }
                                  }]
                                }
                                """;

                String followUpJson = """
                                {
                                  "candidates": [{
                                    "content": {
                                      "parts": [{"text": "Báo cáo hai ngày đều tốt"}]
                                    }
                                  }]
                                }
                                """;

                mockServer.expect(requestTo(
                                "https://generativelanguage.googleapis.com/v1beta/models/gemini-flash-latest:generateContent?key=test-api-key"))
                                .andRespond(withSuccess(callJson, MediaType.APPLICATION_JSON));

                mockServer.expect(requestTo(
                                "https://generativelanguage.googleapis.com/v1beta/models/gemini-flash-latest:generateContent?key=test-api-key"))
                                .andExpect(content()
                                                .string(org.hamcrest.Matchers.containsString("\"id\":\"call_123\"")))
                                .andExpect(content()
                                                .string(org.hamcrest.Matchers.containsString("\"id\":\"call_456\"")))
                                .andRespond(withSuccess(followUpJson, MediaType.APPLICATION_JSON));

                when(teamReportReadService.readTeamSummary(any(), any(), any(), any()))
                                .thenReturn(mock(TeamReportSummary.class));

                User admin = new User();
                admin.setId(1L);
                admin.setTelegramUserId(1002L);
                admin.setAdmin(true);
                when(userRepository.findByTelegramUserId(1002L)).thenReturn(java.util.Optional.of(admin));

                String result = service.answerUserQuery(123L, "Báo cáo 2 ngày", null, admin);
                assertEquals("Báo cáo hai ngày đều tốt", result);
                mockServer.verify();
        }

        @Test
        void answerUserQuery_unknownFunction_returnsErrorMsg() {
                String callJson = """
                                {
                                  "candidates": [{
                                    "content": {
                                      "parts": [{"functionCall": {"name": "unknown_function", "args": {}, "id": "call_456"}}]
                                    }
                                  }]
                                }
                                """;

                String followUpJson = """
                                {
                                  "candidates": [{
                                    "content": {
                                      "parts": [{"text": "Không hiểu"}]
                                    }
                                  }]
                                }
                                """;

                mockServer.expect(requestTo(
                                "https://generativelanguage.googleapis.com/v1beta/models/gemini-flash-latest:generateContent?key=test-api-key"))
                                .andRespond(withSuccess(callJson, MediaType.APPLICATION_JSON));

                mockServer.expect(requestTo(
                                "https://generativelanguage.googleapis.com/v1beta/models/gemini-flash-latest:generateContent?key=test-api-key"))
                                .andExpect(content()
                                                .string(org.hamcrest.Matchers.containsString("\"id\":\"call_456\"")))
                                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"role\":\"user\"")))
                                .andRespond(withSuccess(followUpJson, MediaType.APPLICATION_JSON));

                User user = new User();
                String result = service.answerUserQuery(123L, "Làm gì đó", null, user);
                assertEquals("Không hiểu", result);
        }

        @Test
        void executeFunction_returnsError_whenUserIsNull() {
                String result = (String) service.executeFunction("get_report_summary",
                                Map.of("date", LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh")).toString()), null, null);
                assertEquals("Không xác định được người dùng đang hoạt động.", result);
        }

        @Test
        void executeFunction_managerCannotViewOtherDept() {
                User manager = new User();
                manager.setTelegramUserId(101L);
                manager.setManager(true);
                manager.setDepartmentName("IT");
                when(userRepository.findByTelegramUserId(101L)).thenReturn(java.util.Optional.of(manager));

                String result = (String) service.executeFunction("get_report_summary", Map.of("date",
                                LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh")).toString(), "department", "HR"), manager,
                                null);
                assertEquals("Bạn không có quyền xem báo cáo của phòng này.", result);
        }

        @Test
        void executeFunction_managerCanViewOwnDept() {
                User manager = new User();
                manager.setTelegramUserId(102L);
                manager.setManager(true);
                manager.setDepartmentName("IT");
                when(userRepository.findByTelegramUserId(102L)).thenReturn(java.util.Optional.of(manager));

                TeamReportSummary summary = mock(TeamReportSummary.class);
                when(teamReportReadService.readTeamSummary(any(), any(), any(), any()))
                                .thenReturn(summary);

                Object result = service.executeFunction("get_report_summary", Map.of("date",
                                LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh")).toString(), "department", "IT"), manager,
                                null);
                assertEquals(summary, result);
        }

        @Test
        void executeFunction_managerWithoutDepartmentCannotReadTeamReports() {
                User manager = new User();
                manager.setTelegramUserId(106L);
                manager.setManager(true);
                manager.setDepartmentName("  ");
                when(userRepository.findByTelegramUserId(106L)).thenReturn(java.util.Optional.of(manager));

                Object result = service.executeFunction(
                                "get_report_summary",
                                Map.of("date", LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh")).toString()),
                                manager,
                                null);

                assertEquals("Bạn chưa được phân bổ vào phòng ban nên không thể xem báo cáo đội nhóm.", result);
                verifyNoInteractions(teamReportReadService);
        }

        @Test
        void executeFunction_employeeViewsOwnStatus() {
                User emp = new User();
                emp.setId(10L);
                emp.setTelegramUserId(103L);
                when(userRepository.findByTelegramUserId(103L)).thenReturn(java.util.Optional.of(emp));

                TeamMemberReportStatus status = mock(TeamMemberReportStatus.class);
                when(teamReportReadService.readUserStatus(anyLong(), any(), any(), any()))
                                .thenReturn(status);

                Object result = service.executeFunction("get_report_summary",
                                Map.of("date", LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh")).toString()), emp, null);
                assertEquals(status, result);
        }

        @Test
        void executeFunction_adminCanViewAnyDept() {
                User admin = new User();
                admin.setTelegramUserId(104L);
                admin.setAdmin(true);
                when(userRepository.findByTelegramUserId(104L)).thenReturn(java.util.Optional.of(admin));

                TeamReportSummary summary = mock(TeamReportSummary.class);
                when(teamReportReadService.readTeamSummary(any(), any(), any(), any()))
                                .thenReturn(summary);

                Object result = service.executeFunction("get_report_summary", Map.of("date",
                                LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh")).toString(), "department", "SALE"), admin,
                                null);
                assertEquals(summary, result);
        }

        @Test
        void executeFunction_adminViewsOrganizationWhenDeptIsMissing() {
                User admin = new User();
                admin.setTelegramUserId(105L);
                admin.setAdmin(true);
                when(userRepository.findByTelegramUserId(105L)).thenReturn(java.util.Optional.of(admin));

                TeamReportSummary summary = mock(TeamReportSummary.class);
                when(teamReportReadService.readOrganizationSummary(any(), any(), any()))
                                .thenReturn(summary);

                Object result = service.executeFunction("get_report_summary",
                                Map.of("date", LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh")).toString()), admin, null);
                assertEquals(summary, result);
        }

        @Test
        void executeFunction_updateDepartment_usesExactTargetAndSecuredService() {
                User admin = new User();
                admin.setTelegramUserId(201L);
                admin.setAdmin(true);

                User targetUser = new User();
                targetUser.setId(2L);
                targetUser.setTelegramUserId(202L);
                targetUser.setEmployeeCode("EMP-002");
                targetUser.setFullName("Nguyen Van An");
                targetUser.setDepartmentName("IT");
                targetUser.setUnitName("Platform");

                when(userRepository.findByTelegramUserId(201L)).thenReturn(java.util.Optional.of(admin));
                when(userRepository.findByEmployeeCode("EMP-002")).thenReturn(java.util.Optional.of(targetUser));
                when(identityAdministrationService.changeDepartment(201L, 202L, "Finance", "Platform",
                                "Cập nhật qua AI Chat"))
                                .thenReturn(new IdentityAdminResult(IdentityAdminStatus.UPDATED, targetUser));

                Object adminResult = service.executeFunction("update_user_department",
                                Map.of("employee_identifier", "emp-002", "new_department", "Finance"), admin,
                                "Chuyển emp-002 sang Finance");
                assertTrue(((String) adminResult).startsWith("Thành công"));
                verify(identityAdministrationService).changeDepartment(
                                201L, 202L, "Finance", "Platform", "Cập nhật qua AI Chat");
        }

        @Test
        void executeFunction_updateDepartment_rejectsAmbiguousName() {
                User admin = new User();
                admin.setTelegramUserId(203L);
                admin.setAdmin(true);
                User first = new User();
                first.setFullName("Nguyen Van An");
                User second = new User();
                second.setFullName("Nguyen Van An");

                when(userRepository.findByTelegramUserId(203L)).thenReturn(java.util.Optional.of(admin));
                when(userRepository.findAllByFullNameIgnoreCase("Nguyen Van An"))
                                .thenReturn(java.util.List.of(first, second));

                Object result = service.executeFunction(
                                "update_user_department",
                                Map.of("employee_identifier", "Nguyen Van An", "new_department", "Finance"),
                                admin,
                                "Chuyển Nguyen Van An sang Finance");

                assertEquals("Có nhiều nhân viên trùng tên. Vui lòng dùng mã nhân viên để tránh cập nhật nhầm.",
                                result);
                verifyNoInteractions(identityAdministrationService);
        }

        @Test
        void executeFunction_updateDepartment_rejectsInferredDestination() {
                User admin = new User();
                admin.setTelegramUserId(204L);
                admin.setAdmin(true);
                when(userRepository.findByTelegramUserId(204L)).thenReturn(java.util.Optional.of(admin));

                Object result = service.executeFunction(
                                "update_user_department",
                                Map.of("employee_identifier", "EMP-002", "new_department", "Finance"),
                                admin,
                                "Tôi có thể chuyển EMP-002 sang phòng khác không?");

                assertEquals(
                                "Để tránh cập nhật nhầm, vui lòng nêu rõ mã nhân viên hoặc họ tên đầy đủ và phòng ban đích trong cùng yêu cầu.",
                                result);
                verifyNoInteractions(identityAdministrationService);
        }

        @Test
        void executeFunction_changeStatus_delegatesRbacToSecuredService() {
                User targetUser = new User();
                targetUser.setId(3L);
                targetUser.setTelegramUserId(302L);
                targetUser.setFullName("Le Thi B");
                targetUser.setDepartmentName("IT");

                User manager = new User();
                manager.setTelegramUserId(301L);
                manager.setManager(true);
                manager.setDepartmentName("IT");

                when(userRepository.findByTelegramUserId(301L)).thenReturn(java.util.Optional.of(manager));
                when(userRepository.findAllByFullNameIgnoreCase("Le Thi B")).thenReturn(java.util.List.of(targetUser));
                when(identityAdministrationService.changeStatus(301L, 302L,
                                com.example.dailyreportbot.entity.UserStatus.INACTIVE, "Cập nhật qua AI Chat"))
                                .thenReturn(new IdentityAdminResult(IdentityAdminStatus.DEACTIVATED, targetUser));

                Object result = service.executeFunction(
                                "change_user_status",
                                Map.of("employee_identifier", "Le Thi B", "status", "inactive"),
                                manager,
                                "Đổi trạng thái Le Thi B thành inactive");

                assertTrue(((String) result).startsWith("Thành công"));
                verify(identityAdministrationService).changeStatus(
                                301L, 302L, com.example.dailyreportbot.entity.UserStatus.INACTIVE,
                                "Cập nhật qua AI Chat");
        }

        @Test
        void executeFunction_changeStatus_rejectsInferredStatus() {
                User manager = new User();
                manager.setTelegramUserId(303L);
                manager.setManager(true);
                manager.setDepartmentName("IT");
                when(userRepository.findByTelegramUserId(303L)).thenReturn(java.util.Optional.of(manager));

                Object result = service.executeFunction(
                                "change_user_status",
                                Map.of("employee_identifier", "EMP-002", "status", "INACTIVE"),
                                manager,
                                "Hãy xử lý tài khoản EMP-002");

                assertEquals(
                                "Để tránh cập nhật nhầm, vui lòng nêu rõ mã nhân viên hoặc họ tên đầy đủ và trạng thái ACTIVE/INACTIVE trong cùng yêu cầu.",
                                result);
                verifyNoInteractions(identityAdministrationService);
        }

        @Test
        void executeFunction_rejectsActiveEmployeeAdministration() {
                User employee = new User();
                employee.setTelegramUserId(304L);
                when(userRepository.findByTelegramUserId(304L)).thenReturn(java.util.Optional.of(employee));

                Object result = service.executeFunction(
                                "change_user_status",
                                Map.of("employee_identifier", "EMP-002", "status", "INACTIVE"),
                                employee,
                                "Đổi EMP-002 thành INACTIVE");

                assertEquals("Bạn không có quyền quản trị.", result);
                verifyNoInteractions(identityAdministrationService);
        }

        @Test
        void executeFunction_rechecksCurrentActorBeforeMutation() {
                User staleAdmin = new User();
                staleAdmin.setTelegramUserId(401L);
                staleAdmin.setAdmin(true);
                User inactiveCurrentUser = new User();
                inactiveCurrentUser.setTelegramUserId(401L);
                inactiveCurrentUser.setAdmin(true);
                inactiveCurrentUser.setStatus(com.example.dailyreportbot.entity.UserStatus.INACTIVE);
                when(userRepository.findByTelegramUserId(401L)).thenReturn(java.util.Optional.of(inactiveCurrentUser));

                Object result = service.executeFunction(
                                "update_user_department",
                                Map.of("employee_identifier", "EMP-002", "new_department", "Finance"),
                                staleAdmin,
                                "Chuyển EMP-002 sang Finance");

                assertEquals("Bạn không có quyền chuyển phòng ban cho nhân viên.", result);
                verifyNoInteractions(identityAdministrationService);
        }
}
