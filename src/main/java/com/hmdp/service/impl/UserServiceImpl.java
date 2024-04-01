package com.hmdp.service.impl;

import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;

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
        session.setAttribute("code", code);

        // 5. Send code
        log.debug("send code successfully: " + code);
        return Result.ok();
    }
}
