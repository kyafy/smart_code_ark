package com.smartark.gateway.service;

import com.smartark.gateway.common.exception.BusinessException;
import com.smartark.gateway.common.exception.ErrorCodes;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.concurrent.TimeUnit;

@Service
public class SmsCodeService {
    private static final Duration CODE_TTL = Duration.ofMinutes(5);
    private static final Duration COOLDOWN = Duration.ofSeconds(60);
    private static final int DAILY_LIMIT_PER_PHONE = 10;
    private static final int HOURLY_LIMIT_PER_IP = 100;
    private static final int MAX_VERIFY_FAILS = 5;

    private final StringRedisTemplate redis;
    private final SecureRandom random = new SecureRandom();

    public SmsCodeService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public String sendCode(String phone, String scene, String clientIp) {
        String normalizedScene = (scene == null || scene.isBlank()) ? "login" : scene.trim();
        String cooldownKey = "sms:cooldown:" + normalizedScene + ":" + phone;
        Boolean ok = redis.opsForValue().setIfAbsent(cooldownKey, "1", COOLDOWN);
        if (ok == null || !ok) {
            throw new BusinessException(ErrorCodes.TOO_MANY_REQUESTS, "请求过于频繁");
        }

        String dayKey = "sms:daily:" + phone + ":" + LocalDate.now(ZoneOffset.UTC);
        Long dayCount = redis.opsForValue().increment(dayKey);
        if (dayCount != null && dayCount == 1) {
            long seconds = secondsUntilTomorrowUtc();
            redis.expire(dayKey, seconds, TimeUnit.SECONDS);
        }
        if (dayCount != null && dayCount > DAILY_LIMIT_PER_PHONE) {
            throw new BusinessException(ErrorCodes.TOO_MANY_REQUESTS, "今日验证码发送次数已达上限");
        }

        String hourKey = "sms:ip:" + (clientIp == null ? "unknown" : clientIp) + ":" + currentHourBucketUtc();
        Long hourCount = redis.opsForValue().increment(hourKey);
        if (hourCount != null && hourCount == 1) {
            redis.expire(hourKey, 3600, TimeUnit.SECONDS);
        }
        if (hourCount != null && hourCount > HOURLY_LIMIT_PER_IP) {
            throw new BusinessException(ErrorCodes.TOO_MANY_REQUESTS, "请求过于频繁");
        }

        String code = generate6Digits();
        String codeKey = "sms:code:" + normalizedScene + ":" + phone;
        redis.opsForValue().set(codeKey, code, CODE_TTL);

        String failKey = "sms:verify_fail:" + normalizedScene + ":" + phone;
        redis.delete(failKey);

        return code;
    }

    public void verifyCode(String phone, String scene, String captcha) {
        String normalizedScene = (scene == null || scene.isBlank()) ? "login" : scene.trim();
        String failKey = "sms:verify_fail:" + normalizedScene + ":" + phone;
        Long fails = redis.opsForValue().increment(failKey);
        if (fails != null && fails == 1) {
            redis.expire(failKey, CODE_TTL);
        }
        if (fails != null && fails > MAX_VERIFY_FAILS) {
            throw new BusinessException(ErrorCodes.TOO_MANY_REQUESTS, "验证码错误次数过多，请稍后再试");
        }

        String codeKey = "sms:code:" + normalizedScene + ":" + phone;
        String expected = redis.opsForValue().get(codeKey);
        if (expected == null) {
            throw new BusinessException(ErrorCodes.UNAUTHORIZED, "验证码已过期或不存在");
        }
        if (!expected.equals(captcha)) {
            throw new BusinessException(ErrorCodes.UNAUTHORIZED, "验证码错误");
        }

        redis.delete(codeKey);
        redis.delete(failKey);
    }

    private String generate6Digits() {
        int n = random.nextInt(1_000_000);
        return String.format("%06d", n);
    }

    private long secondsUntilTomorrowUtc() {
        long now = System.currentTimeMillis();
        long tomorrow = LocalDate.now(ZoneOffset.UTC).plusDays(1).atStartOfDay().toEpochSecond(ZoneOffset.UTC) * 1000L;
        long diff = tomorrow - now;
        return Math.max(1, diff / 1000L);
    }

    private String currentHourBucketUtc() {
        long hour = System.currentTimeMillis() / 1000L / 3600L;
        return String.valueOf(hour);
    }
}
