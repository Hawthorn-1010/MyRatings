package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IFollowService followService;

    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog ->{
            addUserInfo(blog);
            addBlogLikeInfo(blog);
        });
//        records.forEach(this::addUserInfo);
        return Result.ok(records);
    }

    @Override
    public Result queryBlogById(Long id) {
        Blog blog = getById(id);
        if (blog == null) {
            return Result.fail("笔记不存在！");
        }
        // 添加用户信息
        addUserInfo(blog);
        // 查询blog是否被点赞，在前端高亮
        addBlogLikeInfo(blog);
        return Result.ok(blog);
    }

    @Override
    public Result likeBlog(Long id) {
        String key = RedisConstants.BLOG_LIKED_KEY + id;
        Long userId = UserHolder.getUser().getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        // 防止自动拆箱空指针
        if (score != null) {
            // 如果已点赞，取消点赞，数据库总点赞量-1
            boolean success = update().setSql("liked = liked - 1").eq("id", id).update();
            if (success) {
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            }
        } else {
            // 点赞，数据库总点赞量+1
            boolean success = update().setSql("liked = liked + 1").eq("id", id).update();
            if (success) {
                stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
            }
        }
        return Result.ok();
    }

    @Override
    public Result queryBlogLikes(Long id) {
        // 1. 查询top5的点赞用户 zrange key 0 4
        Set<String> users = stringRedisTemplate.opsForZSet().range(RedisConstants.BLOG_LIKED_KEY + id, 0, 4);
        if (users == null || users.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        // 2. 解析用户id
        List<Long> ids = users.stream().map(Long::valueOf).collect(Collectors.toList());
        String idStr = StrUtil.join(",", ids);
        // 3. 根据userid查询用户
        List<UserDTO> userDTOS = userService.query()
                .in("id", ids)
                .last("ORDER BY FIELD(id," + idStr + ")").list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(userDTOS);
    }

    @Override
    public Result saveBlog(Blog blog) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 保存探店博文
        boolean success = save(blog);
        if (!success) {
            return Result.fail("新增笔记失败！");
        }
        // 如果保存成功，推送到粉丝的收件箱
        //查询笔记作者的所有粉丝
        List<Follow> follows = followService.lambdaQuery()
                .eq(Follow::getFollowUserId, user.getId())
                .list();
        // 收件箱采用zset结构
        for (Follow follow : follows) {
            stringRedisTemplate.opsForZSet().add(RedisConstants.FEED_KEY + follow.getUserId(), blog.getId().toString(), System.currentTimeMillis());
        }
        return Result.ok(blog.getId());
    }

    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        //获取当前用户
        UserDTO user = UserHolder.getUser();
        //查询收件箱
        String key="feed:"+user.getId();
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, max, offset, 2);
        //非空判断
        if (typedTuples==null||typedTuples.isEmpty()){
            return Result.ok();
        }
        //解析数据 blogId minTime offset
        List<Long> ids=new ArrayList<>(typedTuples.size());
        long minTime =0;
        int os=1;
        for (ZSetOperations.TypedTuple<String> typedTuple : typedTuples) {
            //获取id
            String blogId = typedTuple.getValue();
            ids.add(Long.valueOf(blogId));
            long time = typedTuple.getScore().longValue();
            if (time==minTime){
                os++;
            }else {
                minTime = time;
                os=1;
            }
        }
        //根据 查询blog
        List<Blog> blogs=new ArrayList<>(ids.size());
        for (Long id : ids) {
            Blog blog = getById(id);
            blogs.add(blog);
        }
        blogs.forEach(this::addBlogLikeInfo);
        //封装 返回
        ScrollResult scrollResult = new ScrollResult();
        scrollResult.setList(blogs);
        scrollResult.setOffset(os);
        scrollResult.setMinTime(minTime);
        return Result.ok(scrollResult);
    }

    private void addUserInfo(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }

    private void addBlogLikeInfo(Blog blog) {
        String key = RedisConstants.BLOG_LIKED_KEY + blog.getId();
        // 因为/blog/hot接口没有限制登录才能访问，可能没有user信息
        if (UserHolder.getUser() == null) {
            return;
        }
        Long userId = UserHolder.getUser().getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if (score != null) {
            blog.setIsLike(true);
        } else {
            blog.setIsLike(false);
        }
    }
}
