package com.zhengjianting.redis.jedis;

import redis.clients.jedis.Jedis;

public class JedisClient {
    public static void connectRedis() {
        Jedis jedis = new Jedis("192.168.10.151", 6379);
        System.out.println(jedis.ping());
        jedis.close();
    }

    public static void main(String[] args) {
        connectRedis();
    }
}