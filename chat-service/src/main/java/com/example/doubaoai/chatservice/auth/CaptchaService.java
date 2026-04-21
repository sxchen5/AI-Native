package com.example.doubaoai.chatservice.auth;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

/**
 * 简易图片验证码：内存存储，5 分钟过期。
 */
@Service
public class CaptchaService {

    private static final int TTL_SECONDS = 300;
    private static final String CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private final SecureRandom random = new SecureRandom();

    private record Entry(String code, Instant expiresAt) {
    }

    private final Map<String, Entry> store = new ConcurrentHashMap<>();

    public String createCode() {
        StringBuilder sb = new StringBuilder(4);
        for (int i = 0; i < 4; i++) {
            sb.append(CHARS.charAt(random.nextInt(CHARS.length())));
        }
        return sb.toString();
    }

    public void save(String id, String code) {
        store.put(id, new Entry(code.toLowerCase(), Instant.now().plusSeconds(TTL_SECONDS)));
    }

    public void validate(String id, String userInput) {
        if (id == null || id.isBlank() || userInput == null || userInput.isBlank()) {
            throw new IllegalArgumentException("验证码不能为空");
        }
        Entry e = store.remove(id);
        if (e == null) {
            throw new IllegalArgumentException("验证码无效或已过期");
        }
        if (Instant.now().isAfter(e.expiresAt)) {
            throw new IllegalArgumentException("验证码已过期");
        }
        if (!e.code().equals(userInput.strip().toLowerCase())) {
            throw new IllegalArgumentException("验证码错误");
        }
    }
}
