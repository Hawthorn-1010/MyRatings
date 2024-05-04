package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
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
//        Shop shop = queryByIdWithPassThrough(id);

        // 使用互斥锁解决缓存击穿
        Shop shop = queryByIdWithMutex(id);
        if (shop == null) {
            return Result.fail("店铺不存在！");
        }
        return Result.ok(shop);
    }

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
        try {
            // 缓存重建
            // 获取锁
            boolean lockFlag = tryLock(lockKey);
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
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.MINUTES);
        return BooleanUtil.isTrue(flag);
    }

    private boolean unlock(String key) {
        Boolean flag = stringRedisTemplate.delete(key);
        return BooleanUtil.isTrue(flag);
    }

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
