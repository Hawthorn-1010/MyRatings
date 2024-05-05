package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.StrUtil;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.TimeUnit;

/**
 * User: hzy
 * Date: 2024/5/5
 * Time: 17:13
 * Description:
 */
public class SimpleRedisLock implements ILock{

    private String name;
    private StringRedisTemplate stringRedisTemplate;

    private static final String KEY_PREFIX = "lock:";

    private static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-";

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean tryLock(long timeoutSeconds) {
        // 获取线程标识
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        // 获取锁
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(KEY_PREFIX + name, threadId, timeoutSeconds, TimeUnit.SECONDS);
        // 防止自动拆箱带来的空指针风险
        return Boolean.TRUE.equals(success);
    }

    @Override
    public void unlock() {
        // 获取线程标识
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        String lockThreadId = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
        if (StrUtil.equals(threadId, lockThreadId)) {
            stringRedisTemplate.delete(KEY_PREFIX + name);
        }
    }
}
