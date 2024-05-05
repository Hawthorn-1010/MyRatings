package com.hmdp.utils;

/**
 * User: hzy
 * Date: 2024/5/5
 * Time: 17:12
 * Description:
 */
public interface ILock {
    boolean tryLock(long timeoutSeconds);
    void unlock();
}
