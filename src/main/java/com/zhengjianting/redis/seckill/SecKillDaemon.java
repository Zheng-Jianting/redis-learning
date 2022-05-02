package com.zhengjianting.redis.seckill;

import java.util.Random;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 使用线程池结合 CountDownLatch 模拟并发操作
 * 需要注意的是, MAX_POOL_SIZE + QUEUE_CAPACITY 需要大于等于 clientCount
 * 否则由于采用的拒绝策略是 CallerRunsPolicy, 多余的任务会放到 SecKillDaemon 的主线程中执行
 * 而主线程一旦执行 SecKillThread.run(), 就会阻塞在 latch.await(), 因此不能继续执行 latch.countDown()
 * 从而线程池中的任务和 SecKillDaemon 的主线程都在等待 latch 减为 0, 造成无限期等待
 */
public class SecKillDaemon {
    private static final int CORE_POOL_SIZE = 50;
    private static final int MAX_POOL_SIZE = 100;
    private static final int QUEUE_CAPACITY = 300;
    private static final long KEEP_ALIVE_TIME = 1L;

    public static void main(String[] args) throws InterruptedException {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                CORE_POOL_SIZE,
                MAX_POOL_SIZE,
                KEEP_ALIVE_TIME,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );

        final int clientCount = 300;
        final CountDownLatch latch = new CountDownLatch(clientCount);

        for (int i = 0; i < clientCount; i++) {
            String userId = String.valueOf(new Random().nextInt(10000));
            String productId = "product_1";
            // executor.execute(new SecKillThread(userId, productId, latch));
            executor.execute(new SecKillTransactionThread(userId, productId, latch));
            latch.countDown();
        }

        synchronized (SecKillDaemon.class) {
            SecKillDaemon.class.wait();
        }
    }
}