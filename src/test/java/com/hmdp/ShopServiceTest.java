package com.hmdp;

import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.RedisIdGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * User: hzy
 * Date: 2024/5/4
 * Time: 10:19
 * Description:
 */
@SpringBootTest
public class ShopServiceTest {
    @Resource
    private ShopServiceImpl shopService;

    @Resource
    private RedisIdGenerator idGenerator;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private ExecutorService executorService = Executors.newFixedThreadPool(500);

    @Test
    void TestShopService() {
        shopService.saveShopToRedis(1L, 10L);
    }

    @Test
    void TestIdGenerator() throws InterruptedException {
        CountDownLatch countDownLatch = new CountDownLatch(300);
        Runnable task = () -> {
            for (int i = 0; i < 100; i++) {
                long id = idGenerator.nextId("order");
                System.out.println(id);
            }
            countDownLatch.countDown();
        };
        long begin = System.currentTimeMillis();
        for (int i = 0; i < 300; i++) {
            executorService.submit(task);
        }
        countDownLatch.await();
        long end = System.currentTimeMillis();
        System.out.println(end - begin);
    }

    @Test
    void TestHyperLogLog() {
        String[] users = new String[1000];
        int index = 0;
        for (int i = 0; i < 1000000; i++) {
            users[index] = "user_" + i;
            index++;
            if (index == 1000) {
                index = 0;
                stringRedisTemplate.opsForHyperLogLog().add("keykey", users);
            }
        }
        System.out.println(stringRedisTemplate.opsForHyperLogLog().size("keykey"));
    }

    @Test
    void TestThreadId() {
        long threadId = Thread.currentThread().getId();
        System.out.println(threadId);
    }
}
