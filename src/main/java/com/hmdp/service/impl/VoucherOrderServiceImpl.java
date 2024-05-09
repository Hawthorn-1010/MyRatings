package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdGenerator;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.Collections;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdGenerator redisIdGenerator;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    private static final ExecutorService seckillExecutorService = Executors.newSingleThreadExecutor();

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    @PostConstruct
    private void init() {
        seckillExecutorService.submit(new voucherHandler());
    }

    private class voucherHandler implements Runnable {

        @Override
        public void run() {
            while (true) {
                try {
                    VoucherOrder order = orderTasks.take();
                    handlerVoucherOrder(order);
                } catch (InterruptedException e) {
                    log.error("处理订单异常", e);
                }

            }

        }
    }

    // 已经做了阻塞队列，其实没有必要加锁，只是以防万一做了兜底
    private void handlerVoucherOrder(VoucherOrder order) {
        Long userId = UserHolder.getUser().getId();
        RLock lock = redissonClient.getLock("lock:order:" + userId + ":");
        // 有多个参数，可以指定在一段时间内尝试获取锁，不指定的话，获取失败直接返回
        boolean success = lock.tryLock();

        // 如果获取锁失败
        if (!success) {
            log.error("不允许重复下单！");
            return;
        }

        try {
            // 获取代理对象（事务） 因为目前的是线程池对象，不是主线程，获取不到
            proxy.createVoucherOrder(order);
        } finally {
            lock.unlock();
        }
    }

    private IVoucherOrderService proxy;
    @Override
    public Result seckillVoucher(Long voucherId) {
        // 1. 执行lua脚本
        Long userId = UserHolder.getUser().getId();
        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT, Collections.emptyList(), voucherId.toString(), userId.toString());
        // 2. 判断结果是否为0
        // 2.1 不为0，没有购买资格
        if (result == 1L) {
            return Result.fail("库存不足！");
        } else if (result == 2L) {
            return Result.fail("用户重复下单！");
        }
        // 2.2 为0，有购买资格，把下单信息保存到阻塞队列
        VoucherOrder voucherOrder = new VoucherOrder();
        long voucherOrderId = redisIdGenerator.nextId("order");
        voucherOrder.setId(voucherOrderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);

        orderTasks.add(voucherOrder);

        // 获取代理对象（事务）
        proxy = (IVoucherOrderService) AopContext.currentProxy();

        return Result.ok(voucherOrderId);
    }


//    @Override
//    public Result seckillVoucher(Long voucherId) {
//        // 1. 查询优惠券
//        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
//        // 2. 判断秒杀是否已开始
//        if (seckillVoucher.getBeginTime().isAfter(LocalDateTime.now())) {
//            return Result.fail("秒杀尚未开始！");
//        }
//        // 3. 判断秒杀是否已结束
//        if (seckillVoucher.getEndTime().isBefore(LocalDateTime.now())) {
//            return Result.fail("秒杀已经结束！");
//        }
//        // 4. 判断库存是否充足
//        if (seckillVoucher.getStock() <= 0) {
//            return Result.fail("秒杀优惠券库存不足！");
//        }
//
//        Long userId = UserHolder.getUser().getId();
//        // 因为是一人一单，只需限制一个用户的下单情况，所以可以加上userId
////        SimpleRedisLock simpleRedisLock = new SimpleRedisLock("order:" + userId + ":", stringRedisTemplate);
////        boolean success = simpleRedisLock.tryLock(5);
//
//        RLock lock = redissonClient.getLock("lock:order:" + userId + ":");
//        // 有多个参数，可以指定在一段时间内尝试获取锁，不指定的话，获取失败直接返回
//        boolean success = lock.tryLock();
//
//        // 如果获取锁失败
//        if (!success) {
//            return Result.fail("不允许重复下单！");
//        }
//
//        try {
//            // 获取代理对象（事务）
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);
//        } finally {
//            lock.unlock();
//        }
//    }

//    public synchronized Result createVoucherOrder(Long voucherId) {
//        Long userId = UserHolder.getUser().getId();
//        // 4.5 一人一单
//        long count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
//        if (count > 0) {
//            return Result.fail("您已购买过该优惠券！");
//        }
//
//        // 5. 扣减库存，在扣减库存时，判断stock要大于0，从而防止超卖
//        boolean success = seckillVoucherService.update().setSql("stock = stock - 1").eq("voucher_id", voucherId).gt("stock", 0).update();
//        if (!success) {
//            return Result.fail("秒杀优惠券库存不足！");
//        }
//
//        // 6. 创建订单
//        VoucherOrder voucherOrder = new VoucherOrder();
//        long voucherOrderId = redisIdGenerator.nextId("order");
//        voucherOrder.setId(voucherOrderId);
//        voucherOrder.setUserId(UserHolder.getUser().getId());
//        voucherOrder.setVoucherId(voucherId);
//        save(voucherOrder);
//
//        // 7. 返回订单id
//        return Result.ok(voucherOrderId);
//    }

    public void createVoucherOrder(VoucherOrder order) {
        Long userId = UserHolder.getUser().getId();
        // 4.5 一人一单
        long count = query().eq("user_id", userId).eq("voucher_id", order.getVoucherId()).count();
        if (count > 0) {
            return;
        }

        // 5. 扣减库存，在扣减库存时，判断stock要大于0，从而防止超卖
        boolean success = seckillVoucherService.update().setSql("stock = stock - 1").eq("voucher_id", order.getVoucherId()).gt("stock", 0).update();
        if (!success) {
            return;
        }

        // 6. 创建订单
        save(order);

        // 7. 返回
        return;
    }

}
