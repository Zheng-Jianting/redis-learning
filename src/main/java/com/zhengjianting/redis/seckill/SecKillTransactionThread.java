package com.zhengjianting.redis.seckill;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.Transaction;

import java.util.List;
import java.util.concurrent.CountDownLatch;

public class SecKillTransactionThread implements Runnable {
    private final String userId;
    private final String productId;
    private final CountDownLatch latch;

    public SecKillTransactionThread(String userId, String productId, CountDownLatch latch) {
        this.userId = userId;
        this.productId = productId;
        this.latch = latch;
    }

    @Override
    public void run() {
        try {
            latch.await();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        String userSetKey = productId + ":user";
        String quantityKey = productId + ":quantity";

        Jedis jedis = new Jedis("192.168.10.151", 6379);

        jedis.watch(quantityKey); // watch 乐观锁, 在事务开启前监视库存数量

        if (!jedis.exists(quantityKey)) {
            System.out.println("秒杀还未开始");
            jedis.close();
            return;
        }

        if (Integer.parseInt(jedis.get(quantityKey)) <= 0) {
            System.out.println("秒杀已经结束");
            jedis.close();
            return;
        }

        if (jedis.sismember(userSetKey, userId)) {
            System.out.println("不能重复进行秒杀");
            jedis.close();
            return;
        }

        // 将秒杀操作封装进一个事务
        Transaction transaction = jedis.multi();
        transaction.sadd(userSetKey, userId);
        transaction.decr(quantityKey);
        List<Object> results = transaction.exec();
        if (results == null || results.size() == 0) {
            jedis.close();
            return; // 当 watch 命令监视的键被修改, 执行事务时 Redis 服务器会向客户端返回空回复 (nil)
        }

        System.out.println("秒杀成功");
        jedis.close();
    }
}