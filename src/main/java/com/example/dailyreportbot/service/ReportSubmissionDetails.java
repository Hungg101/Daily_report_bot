package com.example.dailyreportbot.service;

import com.example.dailyreportbot.entity.User;

public record ReportSubmissionDetails(String title, String collaborators, String content) {

    public static final int TITLE_LIMIT = 200;
    public static final int COLLABORATORS_LIMIT = 500;
    public static final int CONTENT_LIMIT = 3_000;
    public static final String SOLO_COLLABORATORS = "Không có";

    public ReportSubmissionDetails {
        title = normalize("title", title, TITLE_LIMIT);
        collaborators = normalize("collaborators", collaborators, COLLABORATORS_LIMIT);
        content = normalize("content", content, CONTENT_LIMIT);
    }

    public static String normalizeTitle(String value) {
        return normalize("title", value, TITLE_LIMIT);
    }

    public static String normalizeCollaborators(String value) {
        return normalize("collaborators", value, COLLABORATORS_LIMIT);
    }

    public static String normalizeContent(String value) {
        return normalize("content", value, CONTENT_LIMIT);
    }

    public String serialize(String primaryPerformer) {
        return "Tiêu đề: " + title
                + "\nNgười thực hiện: " + primaryPerformer
                + "\nNgười cùng thực hiện: " + collaborators
                + "\nNội dung:\n" + content;
    }

    public static String primaryPerformer(User user, Long telegramUserId) {
        if (hasText(user.getFullName())) {
            return user.getFullName().strip();
        }
        if (hasText(user.getFirstName())) {
            return user.getFirstName().strip();
        }
        if (hasText(user.getUsername())) {
            String username = user.getUsername().strip();
            return username.startsWith("@") ? username : "@" + username;
        }
        return "Telegram user ID " + telegramUserId;
    }

    private static String normalize(String field, String value, int limit) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isBlank()) {
            throw new ValidationException(field, false);
        }
        if (normalized.length() > limit) {
            throw new ValidationException(field, true);
        }
        return normalized;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public static final class ValidationException extends IllegalArgumentException {
        private final String field;
        private final boolean tooLong;

        private ValidationException(String field, boolean tooLong) {
            this.field = field;
            this.tooLong = tooLong;
        }

        public String field() {
            return field;
        }

        public boolean tooLong() {
            return tooLong;
        }
    }
}
