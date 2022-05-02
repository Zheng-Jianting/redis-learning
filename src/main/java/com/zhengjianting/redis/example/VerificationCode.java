package com.zhengjianting.redis.example;

import redis.clients.jedis.Jedis;

import java.util.Random;

public class VerificationCode {
    public static boolean verify(String phone, String verificationCode) {
        String codeKey = "VerificationCode_" + phone + "_Code";
        Jedis jedis = new Jedis("192.168.10.151", 6379);
        boolean rst = jedis.exists(codeKey) && jedis.get(codeKey).equals(verificationCode);
        jedis.close();
        return rst;
    }

    public static String sendVerificationCode(String phone) {
        String codeKey = "VerificationCode_" + phone + "_Code";
        String countKey = "VerificationCode_" + phone + "_Count";

        Jedis jedis = new Jedis("192.168.10.151", 6379);
        if (jedis.exists(countKey) && Integer.parseInt(jedis.get(countKey)) >= 3) {
            System.out.println("每天发送次数已达 3 次");
            return null;
        }

        if (!jedis.exists(countKey))
            jedis.setex(countKey, 24 * 60 * 60, "0");
        jedis.incr(countKey);

        String verificationCode = getVerificationCode();
        jedis.setex(codeKey, 60, verificationCode);

        jedis.close();

        return verificationCode;
    }

    public static String getVerificationCode() {
        StringBuilder sb = new StringBuilder();
        Random random = new Random();
        for (int i = 0; i < 6; i++)
            sb.append(random.nextInt(10));
        return sb.toString();
    }

    public static void main(String[] args) {
        String verificationCode = sendVerificationCode("12345678910");
        if (verificationCode != null) {
            if (verify("12345678910", verificationCode))
                System.out.println("success");
            else
                System.out.println("fail");
        }
    }
}