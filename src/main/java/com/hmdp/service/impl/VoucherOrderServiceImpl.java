package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdGenerator;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import org.springframework.aop.framework.AopContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdGenerator redisIdGenerator;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result seckillVoucher(Long voucherId) {
        // 1. 查询优惠券
        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
        // 2. 判断秒杀是否已开始
        if (seckillVoucher.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.fail("秒杀尚未开始！");
        }
        // 3. 判断秒杀是否已结束
        if (seckillVoucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("秒杀已经结束！");
        }
        // 4. 判断库存是否充足
        if (seckillVoucher.getStock() <= 0) {
            return Result.fail("秒杀优惠券库存不足！");
        }

        Long userId = UserHolder.getUser().getId();
        // 因为是一人一单，只需限制一个用户的下单情况，所以可以加上userId
        SimpleRedisLock simpleRedisLock = new SimpleRedisLock("order:" + userId + ":", stringRedisTemplate);
        boolean success = simpleRedisLock.tryLock(5);

        // 如果获取锁失败
        if (!success) {
            return Result.fail("不允许重复下单！");
        }

        try {
            // 获取代理对象（事务）
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        } finally {
            simpleRedisLock.unlock();
        }

    }

    public synchronized Result createVoucherOrder(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        // 4.5 一人一单
        long count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if (count > 0) {
            return Result.fail("您已购买过该优惠券！");
        }

        // 5. 扣减库存，在扣减库存时，判断stock要大于0，从而防止超卖
        boolean success = seckillVoucherService.update().setSql("stock = stock - 1").eq("voucher_id", voucherId).gt("stock", 0).update();
        if (!success) {
            return Result.fail("秒杀优惠券库存不足！");
        }

        // 6. 创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        long voucherOrderId = redisIdGenerator.nextId("order");
        voucherOrder.setId(voucherOrderId);
        voucherOrder.setUserId(UserHolder.getUser().getId());
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);

        // 7. 返回订单id
        return Result.ok(voucherOrderId);
    }

}
