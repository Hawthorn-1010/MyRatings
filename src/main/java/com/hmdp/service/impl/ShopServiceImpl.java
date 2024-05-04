package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryById(Long id) {
        // 缓存空值解决缓存穿透问题
        // Shop shop = queryByIdWithPassThrough(id);

        // 使用互斥锁解决缓存击穿
        // Shop shop = queryByIdWithMutex(id);

        // 使用逻辑过期解决缓存击穿
        Shop shop = queryByIdWithLogicalExpired(id);
        if (shop == null) {
            return Result.fail("店铺不存在！");
        }
        return Result.ok(shop);
    }

    // 使用互斥锁解决缓存击穿
    public Shop queryByIdWithMutex(Long id) {
        // 1. 从redis查询缓存
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 2. 判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            // 3. 缓存中存在，直接返回
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        // 如果缓存值为空（防止缓存穿透设置）
        if ("".equals(shopJson)) {
            return null;
        }

        Shop shop = null;
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        // 缓存重建
        // 获取锁
        boolean lockFlag = tryLock(lockKey);
        try {
            // 获取锁失败，休眠并重试
            if (!lockFlag) {
                Thread.sleep(50);
                return queryByIdWithMutex(id);
            }
            // 4. 不存在，查数据库
            shop = getById(id);
            // 5. 数据库不存在，返回错误
            if (shop == null) {
                // 防止缓存穿透
                stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            // 6. 数据库存在，写回缓存
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            unlock(lockKey);
        }

        return shop;
    }

    // 使用逻辑过期解决缓存击穿
    public Shop queryByIdWithLogicalExpired(Long id) {
        // 1. 从redis查询缓存
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 2. 判断是否存在
        if (StrUtil.isBlank(shopJson)) {
            // 3. 缓存中不存在，直接返回空
            return null;
        }
        // 4. 命中，将json反序列化为对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        // 获取到的是一个Object，但其实是JSONObject，可以进行强转
        JSONObject data = (JSONObject) redisData.getData();
        Shop shop = JSONUtil.toBean(data, Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();

        // 5. 判断是否过期
        // 5.1 未过期，直接返回
        if (expireTime.isAfter(LocalDateTime.now())) {
            return shop;
        }
        // 5.2 已过期，缓存重建
        // 6. 缓存重建
        // 6.1 获取互斥锁
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        // 缓存重建
        // 获取锁
        boolean lockFlag = tryLock(lockKey);
        if (lockFlag) {
            // 6.2 获取成功，开启独立线程，进行缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    // 缓存重建
                    saveShopToRedis(id, 20L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    // 释放锁
                    unlock(lockKey);
                }
            });
        }

        // 6.3 获取失败，返回过期的缓存数据(or 暂时返回过期的数据)
        return shop;
    }

    // 使用缓存空值解决缓存穿透
    public Shop queryByIdWithPassThrough(Long id) {
        // 1. 从redis查询缓存
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 2. 判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            // 3. 缓存中存在，直接返回
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        // 如果缓存值为空（防止缓存穿透设置）
        if ("".equals(shopJson)) {
            return null;
        }
        // 4. 不存在，查数据库
        Shop shop = getById(id);
        // 5. 数据库不存在，返回错误
        if (shop == null) {
            // 防止缓存穿透
            stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        // 6. 数据库存在，写回缓存
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);

        return shop;
    }

    // 使用互斥锁解决缓存击穿
    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", RedisConstants.LOCK_SHOP_TTL, TimeUnit.MINUTES);
        return BooleanUtil.isTrue(flag);
    }

    private boolean unlock(String key) {
        Boolean flag = stringRedisTemplate.delete(key);
        return BooleanUtil.isTrue(flag);
    }

    // 设置逻辑过期防止缓存击穿
    public void saveShopToRedis(Long id, Long expiredSeconds) {
        // 1. 查询店铺数据
        Shop shop = getById(id);
        // Thread.sleep(200);
        // 2. 存入redisData，设置逻辑过期
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expiredSeconds));
        // 3. 存入redis，不设置redis的物理过期时间
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不能为空");
        }
        // 1. 更新数据库
        updateById(shop);
        // 2. 删除缓存
        stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY + id);
        return Result.ok();
    }
}
