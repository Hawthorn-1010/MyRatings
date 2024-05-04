package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * User: hzy
 * Date: 2024/5/2
 * Time: 18:56
 * Description:
 */
@Component
public class RedisIdGenerator {
    private static final long BEGIN_TIMESTAMP = 1640995200L;
    private static final int COUNT_BITS = 32;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     *
     * @param keyPrefix 不同的业务，比如order，shop等
     * @return
     */
    public long nextId(String keyPrefix) {
        // 1. 获取时间戳
        LocalDateTime now = LocalDateTime.now();
        long nowTimeStamp = now.toEpochSecond(ZoneOffset.UTC) - BEGIN_TIMESTAMP;
        // 2. 获取序列号
        String date = now.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        // 序列号定的32位，redis自增支持2^64，加上date，为当日id，当日生成<2^32即可
        long serialNum = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + date + ":");
        long id = nowTimeStamp << COUNT_BITS | serialNum;
        return id;
    }

    public static void main(String[] args) {
        LocalDateTime time = LocalDateTime.of(2022, 1, 1, 0, 0, 0);
        long l = time.toEpochSecond(ZoneOffset.UTC);
        System.out.println(l);
    }
}
