package com.example.dailyreportbot.service;

import com.example.dailyreportbot.config.TelegramBotProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Map;
import java.util.OptionalLong;
import java.util.TreeMap;
import java.util.stream.Collectors;

@Service
public class TelegramMiniAppInitDataValidator {

    private static final String HMAC_SHA_256 = "HmacSHA256";
    private static final int MAX_INIT_DATA_LENGTH = 8_192;
    private static final long MAX_AGE_SECONDS = 30 * 60;

    private final TelegramBotProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public TelegramMiniAppInitDataValidator(
            TelegramBotProperties properties,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public OptionalLong validate(String initData) {
        String botToken = properties.getToken();
        if (initData == null
                || initData.isBlank()
                || initData.length() > MAX_INIT_DATA_LENGTH
                || botToken == null
                || botToken.isBlank()) {
            return OptionalLong.empty();
        }

        try {
            Map<String, String> fields = parse(initData);
            String hash = fields.get("hash");
            String authDateValue = fields.get("auth_date");
            String userValue = fields.get("user");
            if (hash == null || hash.length() != 64 || authDateValue == null || userValue == null) {
                return OptionalLong.empty();
            }

            byte[] suppliedHash = HexFormat.of().parseHex(hash);
            String dataCheckString = fields.entrySet().stream()
                    .filter(entry -> !entry.getKey().equals("hash"))
                    .map(entry -> entry.getKey() + "=" + entry.getValue())
                    .collect(Collectors.joining("\n"));
            byte[] secret = hmac("WebAppData".getBytes(StandardCharsets.UTF_8), botToken);
            byte[] expectedHash = hmac(secret, dataCheckString);
            if (!MessageDigest.isEqual(expectedHash, suppliedHash)) {
                return OptionalLong.empty();
            }

            long ageSeconds = Math.subtractExact(
                    clock.instant().getEpochSecond(),
                    Long.parseLong(authDateValue)
            );
            if (ageSeconds < 0 || ageSeconds > MAX_AGE_SECONDS) {
                return OptionalLong.empty();
            }

            JsonNode user = objectMapper.readTree(userValue);
            if (user == null) {
                return OptionalLong.empty();
            }
            JsonNode id = user.path("id");
            if (!id.isIntegralNumber() || !id.canConvertToLong() || id.longValue() <= 0) {
                return OptionalLong.empty();
            }
            return OptionalLong.of(id.longValue());
        } catch (GeneralSecurityException
                 | JsonProcessingException
                 | IllegalArgumentException
                 | ArithmeticException exception) {
            return OptionalLong.empty();
        }
    }

    private Map<String, String> parse(String initData) {
        Map<String, String> fields = new TreeMap<>();
        for (String pair : initData.split("&", -1)) {
            int separator = pair.indexOf('=');
            if (separator <= 0) {
                throw new IllegalArgumentException("Malformed init data");
            }
            String key = URLDecoder.decode(pair.substring(0, separator), StandardCharsets.UTF_8);
            String value = URLDecoder.decode(pair.substring(separator + 1), StandardCharsets.UTF_8);
            if (key.isEmpty() || fields.putIfAbsent(key, value) != null) {
                throw new IllegalArgumentException("Malformed init data");
            }
        }
        return fields;
    }

    private byte[] hmac(byte[] key, String value) throws GeneralSecurityException {
        Mac mac = Mac.getInstance(HMAC_SHA_256);
        mac.init(new SecretKeySpec(key, HMAC_SHA_256));
        return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
    }
}
