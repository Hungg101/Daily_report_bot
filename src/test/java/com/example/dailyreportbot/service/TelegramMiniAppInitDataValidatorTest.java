package com.example.dailyreportbot.service;

import com.example.dailyreportbot.config.TelegramBotProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class TelegramMiniAppInitDataValidatorTest {

    private static final String BOT_TOKEN = "fixture-bot-token";
    private static final String HMAC_SHA_256 = "HmacSHA256";
    private static final Instant NOW = Instant.parse("2026-07-16T12:00:00Z");
    private static final long TELEGRAM_USER_ID = 7_654_321L;

    private TelegramMiniAppInitDataValidator validator;

    @BeforeEach
    void setUp() {
        TelegramBotProperties properties = new TelegramBotProperties();
        properties.setToken(BOT_TOKEN);
        validator = new TelegramMiniAppInitDataValidator(
                properties,
                new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void shouldReturnUserIdForValidSignedInitData() throws Exception {
        assertThat(validator.validate(signedInitData(NOW.getEpochSecond(), userJson(TELEGRAM_USER_ID))))
                .hasValue(TELEGRAM_USER_ID);
    }

    @Test
    void shouldAcceptTheExactThirtyMinuteFreshnessBoundary() throws Exception {
        assertThat(validator.validate(signedInitData(
                NOW.minusSeconds(1_800).getEpochSecond(),
                userJson(TELEGRAM_USER_ID)
        ))).hasValue(TELEGRAM_USER_ID);
    }

    @Test
    void shouldRejectAlteredInitData() throws Exception {
        String initData = signedInitData(NOW.getEpochSecond(), userJson(TELEGRAM_USER_ID));

        assertThat(validator.validate(initData.replace("query_id=fixture", "query_id=altered")))
                .isEmpty();
    }

    @Test
    void shouldRejectExpiredAndFutureInitData() throws Exception {
        assertThat(validator.validate(signedInitData(
                NOW.minusSeconds(1_801).getEpochSecond(),
                userJson(TELEGRAM_USER_ID)
        ))).isEmpty();
        assertThat(validator.validate(signedInitData(
                NOW.plusSeconds(1).getEpochSecond(),
                userJson(TELEGRAM_USER_ID)
        ))).isEmpty();
    }

    @Test
    void shouldRejectMalformedAndOversizedInitData() {
        for (String initData : List.of(" ", "auth_date", "auth_date=%ZZ", "hash=00")) {
            assertThat(validator.validate(initData)).as("init data: %s", initData).isEmpty();
        }
        assertThat(validator.validate(null)).isEmpty();
        assertThat(validator.validate("x".repeat(8_193))).isEmpty();
    }

    @Test
    void shouldRejectDuplicateKeys() throws Exception {
        String initData = signedInitData(NOW.getEpochSecond(), userJson(TELEGRAM_USER_ID));

        assertThat(validator.validate(initData + "&auth_date=" + NOW.getEpochSecond())).isEmpty();
    }

    @Test
    void shouldRejectSignedInitDataWithoutUser() throws Exception {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("query_id", "fixture");
        fields.put("auth_date", Long.toString(NOW.getEpochSecond()));

        assertThat(validator.validate(sign(fields))).isEmpty();
    }

    @Test
    void shouldRejectInvalidUserIds() throws Exception {
        for (String userJson : List.of(
                "",
                "not-json",
                "{}",
                "{\"id\":0}",
                "{\"id\":-1}",
                "{\"id\":1.5}",
                "{\"id\":\"42\"}",
                "{\"id\":9223372036854775808}"
        )) {
            assertThat(validator.validate(signedInitData(NOW.getEpochSecond(), userJson)))
                    .as("user JSON: %s", userJson)
                    .isEmpty();
        }
    }

    private String signedInitData(long authDate, String userJson) throws Exception {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("user", userJson);
        fields.put("query_id", "fixture");
        fields.put("auth_date", Long.toString(authDate));
        return sign(fields);
    }

    private String sign(Map<String, String> fields) throws Exception {
        String dataCheckString = fields.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining("\n"));
        byte[] secret = hmac("WebAppData".getBytes(StandardCharsets.UTF_8), BOT_TOKEN);
        String hash = HexFormat.of().formatHex(hmac(secret, dataCheckString));

        Map<String, String> signedFields = new LinkedHashMap<>(fields);
        signedFields.put("hash", hash);
        return signedFields.entrySet().stream()
                .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .collect(Collectors.joining("&"));
    }

    private byte[] hmac(byte[] key, String value) throws Exception {
        Mac mac = Mac.getInstance(HMAC_SHA_256);
        mac.init(new SecretKeySpec(key, HMAC_SHA_256));
        return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String userJson(long id) {
        return "{\"id\":" + id + "}";
    }
}
