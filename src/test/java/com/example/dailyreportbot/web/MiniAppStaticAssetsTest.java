package com.example.dailyreportbot.web;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class MiniAppStaticAssetsTest {

    @Test
    void providesAccessibleMemoryOnlyPreviewAndLocksConfirmationBeforeSubmitting() throws IOException {
        String html = read("index.html");
        String javascript = read("app.js");

        assertThat(html).contains(
                "<html lang=\"vi\">",
                "<meta charset=\"utf-8\">",
                "<label for=\"title\">",
                "<label for=\"collaborators\">",
                "<label for=\"content\">",
                "id=\"title\" name=\"title\" maxlength=\"200\" required",
                "id=\"collaborators\" name=\"collaborators\" maxlength=\"500\" required",
                "id=\"content\" name=\"content\" maxlength=\"3000\" required",
                "id=\"preview\" hidden",
                "id=\"edit\"",
                "id=\"confirm\"",
                "role=\"status\"",
                "aria-live=\"polite\"");
        assertThat(html.toLowerCase(Locale.ROOT)).doesNotContain("react");

        assertThat(javascript)
                .contains(
                        "collaboratorsInput.value = \"Không có\";",
                        "editButton.addEventListener(\"click\", showEdit);",
                        "fetch(\"/api/miniapp/reports\"",
                        "method: \"POST\"",
                        "\"X-Telegram-Init-Data\": telegram?.initData || \"\"")
                .doesNotContain(
                        "localStorage",
                        "sessionStorage",
                        "indexedDB",
                        "React",
                        "sendData",
                        "initDataUnsafe",
                        ".reset(",
                        "setTimeout");
        assertThat(javascript.indexOf("fetch(")).isEqualTo(javascript.lastIndexOf("fetch("));

        int guard = javascript.indexOf("if (submitting || !draft) return;");
        int markSubmitting = javascript.indexOf("submitting = true;", guard);
        int disableConfirm = javascript.indexOf("confirmButton.disabled = true;", markSubmitting);
        int disableEdit = javascript.indexOf("editButton.disabled = true;", disableConfirm);
        int disableMainButton = javascript.indexOf("mainButton.disable();", disableEdit);
        int request = javascript.indexOf("fetch(\"/api/miniapp/reports\"", disableMainButton);

        assertThat(guard).isGreaterThanOrEqualTo(0);
        assertThat(markSubmitting).isGreaterThan(guard);
        assertThat(disableConfirm).isGreaterThan(markSubmitting);
        assertThat(disableEdit).isGreaterThan(disableConfirm);
        assertThat(disableMainButton).isGreaterThan(disableEdit);
        assertThat(request).isGreaterThan(disableMainButton);
    }

    private static String read(String file) throws IOException {
        return Files.readString(
                Path.of("src/main/resources/static/miniapp", file),
                StandardCharsets.UTF_8);
    }
}
