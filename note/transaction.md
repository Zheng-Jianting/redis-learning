## Redis 事务

Redis 通过 _MULTI_、_EXEC_、_DISCARD_、_WATCH_ 等命令来实现事务功能。事务提供了一种将多个命令请求打包，然后一次性、按顺序地执行多个命令的机制，并且在事务执行期间，服务器不会中断事务而改去执行其他客户端的命令请求，它会将事务中的所有命令执行完毕，然后才去处理其他客户端的命令请求。

**1. 事务的实现**

一个事务从开始到结束通常会经历以下三个阶段：事务开始、命令入队、事务执行

**1.1 事务开始**

_MULTI_ 命令标记着事务的开始，执行 _MULTI_ 命令的客户端会从非事务状态切换至事务状态，这一切换是通过在客户端状态的 flags 属性中打开 REDIS_MULTI 标识来完成的：

```shell
client.flags |= REDIS_MULTI # 打开客户端的事务标识
```

**1.2 命令入队**

当一个客户端处于非事务状态时，这个客户端发送的命令会立即被服务器执行。与此不同的是，当一个客户端切换到事务状态之后，服务器会根据这个客户端发来的不同命令执行不同的操作：

- 如果客户端发送的命令为 _MULTI_、_EXEC_、_DISCARD_、_WATCH_ 四个命令的其中一个，那么服务器立即执行这个命令
- 与此相反，如果客户端发送的命令是 _MULTI_、_EXEC_、_DISCARD_、_WATCH_ 四个命令以外的其他命令，那么服务器并不立即执行这个命令，而是将这个命令放入一个事务队列里面，然后向客户端返回 QUEUED 回复

<img src="C:\Users\zjt\AppData\Roaming\Typora\typora-user-images\image-20220428151611095.png" alt="image-20220428151611095" style="zoom:80%;" />

**1.3 事务队列**

每个 Redis 客户端都有自己的事务状态：

```c
typedef struct redisClient {
    // ...
    multiState mstate; // 事务状态
    // ...
}
```

事务状态包含一个事务队列，以及一个已入队命令的计数器（ 即事务队列的长度 ）：

```c
typedef struct multiState {
    mutilCmd *commands; // 事务队列, FIFO
    int count; // 已入队命令数量
}
```

事务队列是一个 multiCmd 类型的数组，数组中的每个 multiCmd 结构都保存了一个已入队命令的相关信息：

```c
typedef struct multiCmd {
    robj **argv; // 参数
    int argc; // 参数数量
    struct redisCommand *cmd; // 指向命令实现函数的指针
}
```

<img src="C:\Users\zjt\AppData\Roaming\Typora\typora-user-images\image-20220428152706826.png" alt="image-20220428152706826" style="zoom:80%;" />

**1.4 执行事务**

当一个处于事务状态的客户端向服务器发送 _EXEC_ 命令时，这个 _EXEC_ 命令将立即被服务器执行。服务器会遍历这个客户端的事务队列，执行队列中保存的所有命令，最后将执行命令所得的结果全部返回给客户端



**2. WATCH命令的实现**

WATCH 命令是一个乐观锁（ optimistic locking ），它可以在 EXEC 命令执行之前，监视任意数量的数据库键，并在 EXEC 命令执行时，检查被监视的键是否至少有一个已经被修改过了，如果是的话，服务器将拒绝执行事务，并向客户端返回事务执行失败的空回复 （ nil ）

<img src="C:\Users\zjt\AppData\Roaming\Typora\typora-user-images\image-20220428154627666.png" alt="image-20220428154627666" style="zoom:80%;" />

在时间 T4，客户端 B 修改了 "name" 键的值，当客户端 A 在 T5 执行 EXEC 命令时，服务器会发现 WATCH 监视的键 "name" 已经被修改，因此服务器拒绝执行客户端 A 的事务，并向客户端 A 返回空回复

**2.1 使用 WATCH 命令监视数据库键**

每个 Redis 数据库都保存着一个 watched_keys 字典，这个字典的键是某个被 WATCH 命令监视的数据库键，而字典的值则是一个链表，链表中记录了所有监视相应数据库键的客户端：

```c
typedef struct redisDb {
    // ...
    dict *watched_keys; // key 为正在被 WATCH 命令监视的键, value 记录了所有监视相应键的客户端
    // ...
}
```

通过 watched_keys 字典，服务器可以清楚地知道哪些数据库键正在被监视，以及哪些客户端正在监视这些数据库键

<img src="C:\Users\zjt\AppData\Roaming\Typora\typora-user-images\image-20220428155802895.png" alt="image-20220428155802895" style="zoom:80%;" />

**2.2 监视机制的触发**

所有对数据库进行修改的命令，比如 _SET_、_LPUSH_、_SADD_、_ZREM_、_DEL_、_FLUSHDB_ 等，在执行之后都会调用 multi.c / touchWatchKey 函数对 watched_keys 字典进行检查，查看是否有客户端正在监视刚刚被命令修改过的数据库键，如果有的话，那么 touchWatchKey 函数会将监视被修改键的客户端的 REDIS_DIRTY_CAS 标识打开，表示该客户端的事务安全性已经被破坏

```python
def touchWatchKey(db, key):
	# 如果键 key 存在于数据库的 watched_keys 字典中
    # 那么说明至少有一个客户端在监视这个 key
	if key in db.watched_keys:
        
        # 遍历所有监视键 key 的客户端
        for client in db.watched_keys[key]:
            
            # 打开标识
            client.flags |= REDIS_DIRTY_CAS
```

举个例子，对于图 19-5 所示的 watched_keys 字典来说：

- 如果键 "name" 被修改，那么 c1、c2、c10086 三个客户端的 REDIS_DIRTY_CAS 标识被打开
- 如果键 "age" 被修改，那么 c3 和 c10086 两个客户端的 REDIS_DIRTY_CAS 标识被打开
- 如果键 "address" 被修改，那么 c2 和 c4 两个客户端的 REDIS_DIRTY_CAS 标识被打开

<img src="C:\Users\zjt\AppData\Roaming\Typora\typora-user-images\image-20220428161132455.png" alt="image-20220428161132455" style="zoom:80%;" />

**2.3 判断事务是否安全**

当服务器收到一个客户端发来的 EXEC 命令时，服务器会根据这个客户端是否打开了 REDIS_DIRTY_CAS 标识来决定是否执行事务：

- 如果客户端的 REDIS_DIRTY_CAS 标识已经被打开，那么说明客户端所监视的键当中，至少有一个键已经被修改过了，在这种情况下，客户端提交的事务已经不再安全，所有服务器会拒绝执行客户端提交的事务
- 如果客户端的 REDIS_DIRTY_CAS 标识没有被打开，那么说明客户端所监视的所有键都没有被修改过（或者客户端没有监视任何键），事务仍然是安全的，服务器将执行客户端提交的这个事务

<img src="C:\Users\zjt\AppData\Roaming\Typora\typora-user-images\image-20220428161658474.png" alt="image-20220428161658474" style="zoom:80%;" />



**3. 事务的 ACID 性质**

**3.1 原子性 ( Atomicity )**

事务具有原子性指的是，数据库将事务中的多个操作当作一个整体来执行，服务器要么就执行事务中的所有操作，要么就一个操作也不执行

对于 Redis 的事务功能来说，事务队列中的命令要么就全部都执行，要么就一个都不执行，因此，Redis 的事务是具有原子性的

**命令入队阶段出错**

<img src="C:\Users\zjt\AppData\Roaming\Typora\typora-user-images\image-20220429143056233.png" alt="image-20220429143056233" style="zoom:80%;" />

**执行事务阶段出错**

<img src="C:\Users\zjt\AppData\Roaming\Typora\typora-user-images\image-20220429143449964.png" alt="image-20220429143449964" style="zoom:80%;" />

Redis 的作者在事务功能的文档中解释说，不支持事务回滚是因为这种复杂的功能和 Redis 追求简单高效的设计主旨不相符，并且他认为，Redis 事务的执行时错误通常都是编程错误产生的，这种错误通常只会出现在开发环境中，而很少会在实际的生成环境中出现，所以他认为没有必要为 Redis 开发事务回滚功能

**3.2 一致性 ( consistency )**

事务具有一致性指的是，如果数据库在执行事务之前是一致的，那么在事务执行之后，无论事务是否执行成功，数据库也应该仍然是一致的，"一致" 指的是数据符合数据库本身的定义和要求，没有包含非法或者无效的错误数据

**3.3 隔离性 ( isolation )**

事务的隔离性指的是，即使数据库中多个事务并发地执行，各个事务之间也不会互相影响，并且在并发状态下执行的事务和串行执行的事务产生的结果完全相同

因为 Redis 使用单线程的方式来执行事务（ 以及事务队列中的命令 ），并且服务器保证，在执行事务期间不会对事务进行中断，因此，Redis 的事务总是以串行的方式运行的，并且事务也总是具有隔离性的

**3.4 持久性 ( durability )**

事务的持久性指的是，当一个事务执行完毕时，执行这个事务所得的结果已经被保存到永久性存储介质 ( 比如硬盘 ) 里面了，即使服务器在事务执行完毕之后停机，执行事务所得的结果也不会丢失



**4. WATCH 命令**

- 执行 _EXEC_ 命令后，会取消对所有数据库键的监视
- 执行 _DISCARD_ 命令后，放弃执行事务，同时会取消对所有数据库键的监视
- 在 _MULTI_ 命令执行之前，可以通过 _UNWATCH_ 命令取消对所有数据库键的监视



## Redis 秒杀案例

**1. 基本实现**

```java
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
            executor.execute(new SecKillThread(userId, productId, latch));
            latch.countDown();
        }

        synchronized (SecKillDaemon.class) {
            SecKillDaemon.class.wait();
        }
    }
}
```



```java
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
```



**2. 超卖问题**

在上述代码中使用多线程模拟并发操作，但是存在超卖问题，例如在对库存为 1 的商品进行秒杀时，可能有超过 1 个线程成功地进行秒杀，导致最后库存为负值

问题在于线程认为只要从 redis 读取的库存大于 0，便可以进行秒杀，但是在线程对库存进行减 1 时，此时的库存不一定还是大于 0，也就是说，之前读取的值 不一定是 在进行减操作时的值

|        |      Thread 1      |      Thread 2       |
| :----: | :----------------: | :-----------------: |
| Time 1 | read quantity = 1  |  read quantity = 1  |
| Time 2 | 秒杀，quantity = 0 |                     |
| Time 3 |                    | 秒杀，quantity = -1 |

如上表所示，在 Thread 2 进行秒杀时，库存其实已经为 0 了，并不等于在判断是否能进行秒杀时读取的 quantity = 1

**2.1 悲观锁解决超卖问题**

对库存加锁，将秒杀变成串行执行，便不会出现上述问题，但是缺点也很明显，效率很低

**2.2 乐观锁解决超卖问题**

乐观锁是指在对值进行修改之前，首先读取一个旧值，如果在进行修改时，这个值还等于之前读取的旧值 ( 说明从读取到修改期间，这个值没有被更改过 )，才允许将其更改为新值，即 CAS ( Compare And Swap ) 操作

ABA 问题：如果值从 A 更改为 B，再更改为 A，那么上述乐观锁会认为这个值没被更改过，解决方案：为值添加一个版本号

Redis 事务中的 _WATCH_ 命令就是一个乐观锁，因此可以把秒杀操作封装成 Redis 事务，结合 _WATCH_ 命令解决超卖问题

```java
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
```



**3. 库存遗留问题**

通过将秒杀操作封装进 Redis 事务并结合 _WATCH_ 命令解决了超卖问题，但是也导致了一个新问题：库存遗留问题，也就是说，秒杀结束后，库存仍然有剩余 ( 不是因为参与秒杀活动的人数不够 )，举个极端点的例子，如下表所示：

|        |             Thread 1              |                Thread 2                |                Thread 3                |              Thread 1000               |
| :----: | :-------------------------------: | :------------------------------------: | :------------------------------------: | :------------------------------------: |
| Time 1 | read quantity = 100，version = v1 |   read quantity = 100，version = v1    |   read quantity = 100，version = v1    |   read quantity = 100，version = v1    |
| Time 2 | 秒杀，quantity = 99，version = v2 |                                        |                                        |                                        |
| Time 3 |                                   | 之前读取的 quantity 被更改了，秒杀失败 | 之前读取的 quantity 被更改了，秒杀失败 | 之前读取的 quantity 被更改了，秒杀失败 |

有 1000 个线程对库存为 100 的商品进行秒杀，但结束后，库存还剩下 999，只有 Thread 1 成功进行了秒杀

解决方案：自旋 CAS，其它方案待学习



**4. 连接超时问题**

Redis 连接池 --- 待学习



我理解的原子操作是：多线程并发执行的结果和多线程串行执行的结果一致