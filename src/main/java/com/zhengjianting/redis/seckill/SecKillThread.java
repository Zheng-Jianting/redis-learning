package com.zhengjianting.redis.seckill;

import redis.clients.jedis.Jedis;

import java.util.concurrent.CountDownLatch;

public class SecKillThread implements Runnable {
    private final String userId;
    private final String productId;
    private final CountDownLatch latch;

    public SecKillThread(String userId, String productId, CountDownLatch latch) {
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

        jedis.sadd(userSetKey, userId);
        jedis.decr(quantityKey);

        System.out.println("秒杀成功");
        jedis.close();
    }
}