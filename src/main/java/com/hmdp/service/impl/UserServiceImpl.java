package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        // 1. Check if the phone number is valid
        if (RegexUtils.isPhoneInvalid(phone)) {
            // 2. if not, error
            return Result.fail("The form of phone is wrong!");
        }

        // 3. Generate verification code
        String code = RandomUtil.randomString(6);
        // 4. Set in session
//        session.setAttribute("code", code);
        stringRedisTemplate.opsForValue().set(RedisConstants.LOGIN_CODE_KEY + phone, code, RedisConstants.LOGIN_CODE_TTL, TimeUnit.MINUTES);

        // 5. Send code
        log.debug("send code successfully: " + code);
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        // 1. check phone number
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("The form of phone is wrong!");
        }

        // 2. check verification code from redis
        String code = loginForm.getCode();
//        Object cacheCode = session.getAttribute("code");
        String redisCode = stringRedisTemplate.opsForValue().get(RedisConstants.LOGIN_CODE_KEY + phone);
        if (redisCode == null || !redisCode.equals(code)) {
            return Result.fail("verification code is wrong!");
        }

        // 3. check if this phone number is in database
        User user = query().eq("phone", phone).one();
        if (user == null) {
            // 4. if is null, register
            user = createUserWithPhone(phone);
        }

        // 5. return
//        session.setAttribute("user", BeanUtil.copyProperties(user, UserDTO.class));

        // 6. 保存用户信息到redis中
        // 6.1. 随机生成token，作为登录令牌
        String token = UUID.randomUUID().toString();

        // 6.2. 将User对象转换为Hash存储
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));

        // 6.3. 存储
        stringRedisTemplate.opsForHash().putAll(RedisConstants.LOGIN_USER_KEY + token, userMap);
        stringRedisTemplate.expire(RedisConstants.LOGIN_USER_KEY + token, RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);
        // 7. 返回token

        return Result.ok(token);
    }

    @Override
    public Result sign() {
        // 1. 获取当前用户
        Long userId = UserHolder.getUser().getId();
        // 2. 获取日期
        LocalDateTime now = LocalDateTime.now();
        // 3. 拼接key
        String key = RedisConstants.USER_SIGN_KEY + ":" + StrUtil.toString(userId) + ":" + now.format(DateTimeFormatter.ofPattern("yyyyMM")) + ":";
        // 4. 获取今天是本月第几天
        int dayOfMonth = now.getDayOfMonth();
        // 5. 写入redis
        stringRedisTemplate.opsForValue().setBit(key, dayOfMonth - 1, true);
        return null;
    }

    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        save(user);
        return user;
    }
}
