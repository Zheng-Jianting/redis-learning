## 复制

在 Redis 中，用户可以通过执行 _SLAVEOF_ 命令或者设置 slaveof 选项，让一个服务器去复制 ( replicate ) 另一个服务器，我们称呼被复制的服务器为主服务器 ( master )，而对主服务器进行复制的服务器则被称为从服务器 ( slave )

<div align="center"> <img width="26%" src="https://api.zhengjianting.com/picture?name=replication_15-1"/> </div> <br>

假设现在有两个 Redis 服务器，地址分别为 127.0.0.1:6379 和 127.0.0.1:12345，如果我们向服务器 127.0.0.1:12345 发送以下命令：

```shell
127.0.0.1:12345> SLAVEOF 127.0.0.1 6379
```

那么服务器 127.0.0.1:12345 将成为 127.0.0.1:6379 的从服务器，而服务器 127.0.0.1:6379 则会成为 127.0.0.1:12345 的主服务器

进行复制中的主从服务器双方的数据库将保存相同的数据，概念上将这种现象称作 "数据库状态一致"，或者简称 "一致"

**1. 旧版复制功能的实现**

Redis 的复制功能分为同步 ( sync ) 和命令传播 ( command propagate ) 两个操作：

- 同步操作用于将从服务器的数据库状态更新至主服务器当前所处的数据库状态
- 命令传播则作用于在主服务器的状态被修改，导致主从服务器的数据库状态出现不一致时，让主从服务器的数据库重新回到一致状态

**1.1 同步**

当客户端向从服务器发送 _SLAVEOF_ 命令，要求从服务器复制主服务器时，从服务器首先需要执行同步操作，也就是将从服务器的数据库状态更新至主服务器当前所处的数据库状态

从服务器对主服务器的同步操作需要通过向主服务器发送 _SYNC_ 命令来完成，以下是 _SYNC_ 命令的执行步骤：

- 从服务器向主服务器发送 _SYNC_ 命令
- 收到 _SYNC_ 命令的主服务器执行 _BGSAVE_ 命令，在后台生成一个 RDB 文件，并使用一个缓冲区记录从现在开始执行的所有写命令
- 当主服务器的 _BGSAVE_ 命令执行完毕时，主服务器会将 _BGSAVE_ 命令生成的 RDB 文件发送给从服务器，从服务器接收并载入这个 RDB 文件，将自己的数据库状态更新至主服务器执行 _BGSAVE_ 命令时的数据库状态
- 主服务器将记录在缓冲区里面的所有写命令发送给从服务器，从服务器执行这些写命令，将自己的数据库状态更新至主服务器数据库当前所处的状态

<div align="center"> <img width="42%" src="https://api.zhengjianting.com/picture?name=replication_15-2"/> </div> <br>

<div align="center"> <img width="67%" src="https://api.zhengjianting.com/picture?name=replication_table_15-1"/> </div> <br>

**1.2 命令传播**

在同步操作执行完毕之后，主从服务器两者的数据库将达到一致状态，但这种一致并不是一成不变的，每当主服务器执行客户端发送的写命令时，主服务器的数据库就有可能被修改，并导致主从服务器状态不再一致

为了让主从服务器再次回到一致状态，主服务器需要对从服务器执行命令传播操作：主服务器会将自己执行的写命令，也就是造成主从服务器不一致的那条写命令，发送给从服务器执行，当从服务器执行了相同的写命令之后，主从服务器将再次回到一致状态

**2. 旧版复制功能的缺陷**

在 Redis 中，从服务器对主服务器的复制可以分为以下两种情况：

- 初次复制：从服务器以前没有复制过任何主服务器，或者从服务器当前要复制的主服务器和上一次复制的主服务器不同
- 断线后重复制：处于命令传播阶段的主从服务器因为网络原因而中断了复制，但从服务器通过自动重连接重新连上了主服务器，并继续复制主服务器

对于初次复制来说，旧版复制功能能够很好地完成任务，但对于断线后重复制来说，旧版复制功能虽然也能让主从服务器重新回到一致状态，但效率非常低，例如：

<div align="center"> <img width="67%" src="https://api.zhengjianting.com/picture?name=replication_table_15-2"/> </div> <br>

在时间 T10091，从服务器终于重新连接上主服务器，因为这时主从服务器的状态已经不再一致，所以从服务器将向主服务器发送 _SYNC_ 命令，而主服务器会将包含键 k1 至键 k10089 的 RDB 文件发送欸从服务器，从服务器通过接收和载入这个 RDB 文件来将自己的数据库更新至主服务器数据库当前所处的状态

虽然再次发送 _SYNC_ 命令可以让主服务器重新回到一致状态，但如果我们仔细研究这个断线重复制过程，就会发现传送 RDB 文件这一步实际上并不是非做不可的：

- 主从服务器在时间 T0 至时间 T10086 中一直处于一致状态，这两个服务器保存的数据大部分都是相同的
- 从服务器想要将自己更新至主服务器当前所处的状态，正在需要的是主从服务器连接中断期间，主服务器新添加的 k10087、k10088、k10089 三个键的数据
- 可惜的是，旧版复制功能并没有利用以上列举的两点条件，而是继续让主服务器生成并向从服务器发送包含键 k1 至键 10089 的 RDB 文件，但实际上 RDB 文件包含的键 k1 至键 k10086 的数据对于从服务器来说都是不必要的

**3. 新版复制功能的实现**

为了解决旧版复制功能在处理断线重复制情况时的低效问题，Redis 从 2.8 版本开始，使用 _PSYNC_ 命令代替 _SYNC_ 命令来执行复制时的同步操作

_PSYNC_ 命令具有完整重同步 ( full resynchronization ) 和部分重同步 ( partial resynchronization ) 两种模式：

- 其中完整重同步用于处理初次复制的情况：完整重同步的执行步骤和 _SYNC_ 命令的执行步骤基本一样，它们都是通过让主服务器创建并发送 RDB 文件，以及向从服务器发送保存在缓冲区里面的写命令来进行同步
- 而部分重同步则用于处理断线后重复制情况：当从服务器在断线后重新连接主服务器时，如果条件允许，主服务器可以将主从服务器连接断开期间执行的写命令发送给从服务器，从服务器只要接收并执行这些写命令，就可以将数据库更新至主服务器当前所处的状态

_PSYNC_ 命令的部分重同步模式解决了旧版复制功能在处理断线后重复制时出现的低效情况，下表展示了如何使用 _PSYNC_ 命令高效地处理上一节展示的断线后复制情况：

<div align="center"> <img width="67%" src="https://api.zhengjianting.com/picture?name=replication_table_15-3"/> </div> <br>

<div align="center"> <img width="30%" src="https://api.zhengjianting.com/picture?name=replication_15-6"/> </div> <br>

对比一下 _SYNC_ 命令和 _PSYNC_  命令处理断线重复制的方法，不难看出，虽然 _SYNC_ 命令和 _PSYNC_ 命令都可以让断线的主从服务器重新回到一致状态，但执行部分重同步所需的资源比起执行 _SYNC_ 命令所需的资源要少得多，完成同步的速度也快得多。执行 _SYNC_ 命令需要生成、传送和载入整个 RDB 文件，而部分重同步只需要将从服务器缺少的写命令发送给从服务器执行就可以了

**4. 部分重同步的实现**

部分重同步功能由以下三个部分构成：

- 主服务器的复制偏移量 ( replication offset ) 和从服务器的复制偏移量
- 主服务器的复制积压缓冲区 ( replication backlog )
- 服务器的运行 ID ( run ID )

**4.1 复制偏移量**

执行复制的双方——主服务器和从服务器会分别维护一个复制偏移量：

- 主服务器每次向从服务器传播 N 个字节的数据时，就将自己的复制偏移量的值加上 N
- 从服务器每次收到主服务器传播来的 N 个字节的数据时，就将自己的复制偏移量的值加上 N

<div align="center"> <img width="30%" src="https://api.zhengjianting.com/picture?name=replication_15-7"/> </div> <br>

如果这时主服务器向三个从服务器传播长度为 33 字节的数据，那么主服务器的复制偏移量将更新为 10086 + 33 = 10119，而三个从服务器在接收到主服务器传播的数据之后，也会将复制偏移量更新为 10119：

<div align="center"> <img width="45%" src="https://api.zhengjianting.com/picture?name=replication_15-8"/> </div> <br>

通过对比主从服务器的复制偏移量，程序可以很容易地直到主从服务器是否处于一致状态：

- 如果主从服务器处于一致状态，那么主从服务器两者的偏移量总是相同的
- 相反，如果主从服务器两者的偏移量并不相同，那么说明主从服务器并未处于一致状态

例如，主从服务器当前的复制偏移量都为 10086，但是就在主服务器要向从服务器传播长度为 33 字节的数据之前，从服务器 A 断线了，那么主服务器传播的数据将只有从服务器 B 和从服务器 C 能收到，在这之后，主服务器、从服务器 B 和从服务器 C 三个服务器的复制偏移量都将更新为 10119，而断线的从服务器 A 的复制偏移量仍然停留在 10086，这说明从服务器 A 与主服务器并不一致：

<div align="center"> <img width="45%" src="https://api.zhengjianting.com/picture?name=replication_15-9"/> </div> <br>

假设从服务器 A 在断线之后就立即重新连接主服务器，并且成功，那么接下来，从服务器将向主服务器发送 _PSYNC_ 命令，报告从服务器 A 当前的复制偏移量为 10086，那么这时，主服务器应该对从服务器执行完整重同步还是部分重同步呢？如果执行部分重同步的话，主服务器又如何补偿从服务器 A 在断线期间丢失的那部分数据呢？以上问题的答案都和复制积压缓冲区有关

**4.2 复制积压缓冲区**

复制积压缓冲区是由主服务器维护的一个固定长度 ( fixed-size ) 先进先出 ( FIFO ) 队列，默认大小为 1MB

<div align="center"> <img width="65%" src="https://api.zhengjianting.com/picture?name=replication_other_15-1"/> </div> <br>

当主服务器进行命令传播时，它不仅会将写命令发送给所有从服务器，还会将写命令入队到复制积压缓冲区里面，如图所示：

<div align="center"> <img width="47%" src="https://api.zhengjianting.com/picture?name=replication_15-10"/> </div> <br>

因此，主服务器的复制积压缓冲区里面会保存着一部分最近传播的写命令，并且复制积压缓冲区会为队列中的每个字节记录相应的复制偏移量，如下表所示：

<div align="center"> <img width="67%" src="https://api.zhengjianting.com/picture?name=replication_table_15-4"/> </div> <br>

当从服务器重新连上主服务器时，从服务器会通过 _PSYNC_ 命令将自己的复制偏移量 offset 发送给主服务器，主服务器会根据这个复制偏移量来决定对从服务器执行何种同步操作：

- 如果 offset 偏移量之后的数据 ( 也就是偏移量 offset + 1 开始的数据 ) 仍然存在于复制积压缓冲区里面，那么主服务器将对从服务器执行部分重同步操作
- 相反，如果 offset 偏移量之后的数据已经不存在于复制积压缓冲区，那么主服务器将对从服务器执行完整重同步操作

回到之前图 15-9 展示的断线后重连接例子：

- 当从服务器 A 断线之后，它立即重新连接主服务器，并向主服务器发送 _PSYNC_ 命令，报告自己的复制偏移量为 10086
- 主服务器收到从服务器发来的 _PSYNC_ 命令以及偏移量 10086 之后，主服务器将检查偏移量 10086 之后的数据是否存在于复制积压缓冲区里面，结果发现这些数据仍然存在，于是主服务器向从服务器发送 +CONTINUE 回复，表示数据同步将以部分重同步模式来进行
- 接着主服务器会将复制积压缓冲区 10086 偏移量之后的所有数据 ( 偏移量为 10087 至 10119 ) 都发送给从服务器
- 从服务器只要接收这 33 字节的缺失数据，就可以回到与主服务器一致的状态，如图所示：

<div align="center"> <img width="42%" src="https://api.zhengjianting.com/picture?name=replication_15-11"/> </div> <br>

<div align="center"> <img width="67%" src="https://api.zhengjianting.com/picture?name=replication_other_15-2"/> </div> <br>

**4.3 服务器运行 ID**

除了复制偏移量和复制积压缓冲区之外，实现部分重同步还需要用到服务器运行 ID ( run ID )：

- 每个 Redis 服务器，不论主服务器还是从服务器，都会有自己的运行 ID
- 运行 ID 在服务器启动时自动生成，由 40 个随机的十六进制字符组成

当从服务器对主服务器进行初次复制时，主服务器会将自己的运行 ID 传送给从服务器，而从服务器则会将这个运行 ID 保存起来

当从服务器断线并重新连上一个主服务器时，从服务器将向当前连接的主服务器发送之前保存的运行 ID：

- 如果从服务器保存的运行 ID 和当前连接的主服务器的运行 ID 相同，那么说明从服务器断线之前复制的就是当前连接的这个主服务器，主服务器可以继续尝试执行部分重同步操作
- 相反，主服务器将对从服务器执行完整重同步操作

**5. PSYNC 命令的实现**

_PSYNC_ 命令的调用方式有两种：

- 如果从服务器以前没有复制过任何主服务器，或者之前执行过 SLAVEOF no one 命令，那么从服务器在开始一次新的复制时将向主服务器发送 PSYNC ？ -1 命令，主动请求主服务器进行完整重同步 ( 因为这时不可能执行部分重同步 )
- 相反地，如果从服务器已经复制过某个主服务器，那么从服务器在开始一次新的复制时将向主服务器发送 PSYNC \<runid\> \<offset\> 命令：其中 runid 是上一次复制的主服务器的运行 ID，而 offset 则是从服务器当前的复制偏移量，接收到这个命令的主服务器会通过这两个参数来判断应该对从服务器执行哪种同步操作

根据情况，接收到 _PSYNC_ 命令的主服务器会向从服务器返回以下三种回复的其中一种：

- 如果主服务器返回 +FULLRESYNC \<runid\> \<offset\> 回复，那么表示主服务器将与从服务器执行完整重同步操作：其中 runid 是这个主服务器的运行 ID，从服务器会将这个 ID 保存起来，在下一次发送 _PSYNC_ 命令时使用；而 offset 则是主服务器当前的复制偏移量，从服务器会将这个值作为自己的初始化偏移量
- 如果主服务器返回 +CONTINUE 回复，那么表示主服务器将与从服务器执行部分重同步操作，从服务器只要等着主服务器将自己缺少的那部分数据发送过来就可以了
- 如果主服务器返回 -ERR 回复，那么表示主服务器的版本低于 Redis 2.8，它识别不了 _PSYNC_ 命令，从服务器将向主服务器发送 _SYNC_ 命令，并与主服务器执行完整同步操作

以下流程图总结了 _PSYNC_ 命令执行完整重同步和部分重同步时可能遇上的情况：

<div align="center"> <img width="47%" src="https://api.zhengjianting.com/picture?name=replication_15-12"/> </div> <br>

- 主服务器返回 +CONTINUE 的情况：runid 是从服务器之前复制的主服务器运行 ID，并且 offset 之后的数据都还存在于该主服务器的复制积压缓冲区中
- 主服务器返回 +FULLRESYNC 的情况：
  - runid 不是从服务器之前复制的主服务器运行 ID
  - runid 是从服务器之前复制的主服务器运行 ID，但是 offset 之后的数据已经不在该主服务器的复制积压缓冲区中

**6. 复制的实现**

通过向从服务器发送 _SLAVEOF_ 命令，我们可以让一个从服务器去复制一个主服务器

```shell
SLAVEOF <master_ip> <master_port>
```

**6.1 步骤 1：设置主服务器的地址和端口**

当客户端向从服务器发送以下命令时：SLAVEOF 127.0.0.1 6379

从服务器首先要做的就是将客户端给定的主服务器 IP 地址 127.0.0.1 以及端口 6379 保存到服务器状态的 masterhost 属性和 masterport 属性里面：

```c
struct redisServer {
    // ...
    char *masterhost; // 主服务器的地址
    int masterport; // 主服务器的端口
    // ...
}
```

_SLAVEOF_ 命令是一个异步命令，在完成 masterhost 属性和 masterport 属性的设置工作之后，从服务器将向发送 _SLAVEOF_ 命令的客户端返回 OK，表示复制指令已经被接收，而实际的复制工作将在 OK 返回之后才真正开始执行

**6.2 步骤 2：建立套接字连接**

在 _SLAVEOF_ 命令执行之后，从服务器将根据命令所设置的 IP 地址和端口，创建连向主服务器的套接字连接，如图所示：

<div align="center"> <img width="32%" src="https://api.zhengjianting.com/picture?name=replication_15-14"/> </div> <br>

如果从服务器创建的套接字能成功连接 ( connect ) 到主服务器，那么从服务器将为这个套接字关联一个专门用于处理复制工作的文件事件处理器，这个处理器将负责执行后续的复制工作，比如接收 RDB 文件，以及接收主服务器传播来的写命令，诸如此类

而主服务器在接受 ( accept ) 从服务器的套接字连接之后，将为该套接字创建相应的客户端状态，并将从服务器看作是一个连接到主服务器的客户端来对待，这时从服务器将具有服务器 ( server ) 和客户端 ( client ) 两个身份：从服务器可以向主服务器发送命令请求，而主服务器则会向从服务器返回命令回复，如图所示：

<div align="center"> <img width="36%" src="https://api.zhengjianting.com/picture?name=replication_15-15"/> </div> <br>

因为复制工作接下来的几个步骤都会以从服务器向主服务器发送命令请求的形式来进行，所以理解 "从服务器是主服务器的客户端" 这一点非常重要

**6.3 步骤 3：发送 PING 命令**

从服务器成为主服务器的客户端之后，做的第一件事就是向主服务器发送一个 _PING_ 命令，这个 _PING_ 命令有两个作用：

- 检查套接字的读写状态是否正常
- 检查主服务器能否正常处理命令请求

以下流程图总结了从服务器在发送 _PING_ 命令时可能遇到的情况，以及各个情况的处理方式：

<div align="center"> <img width="37%" src="https://api.zhengjianting.com/picture?name=replication_15-17"/> </div> <br>

**6.4 步骤 4：身份验证**

从服务器在收到主服务器发送的 "PONG" 回复之后，下一步要做的就是决定是否进行身份验证：

- 如果从服务器设置了 masterauth 选项，那么进行身份验证
- 如果从服务器没有设置 masterauth 选项，那么不进行身份验证

在需要进行身份验证的情况下，从服务器将向主服务器发送一条 _AUTH_ 命令，命令的参数为从服务器 masterauth 选项的值，例如从服务器 masterauth 选项的值为 10086，那么从服务器将向主服务器发送命令 AUTH 10086：

<div align="center"> <img width="32%" src="https://api.zhengjianting.com/picture?name=replication_15-18"/> </div> <br>

主服务器通过设置 requirepass 选项设置密码，从服务器通过设置 masterauth 选项设置密码

以下流程图总结了从服务器在身份验证阶段可能遇到的情况，以及各个情况的处理方式：

<div align="center"> <img width="48%" src="https://api.zhengjianting.com/picture?name=replication_15-19"/> </div> <br>

**6.5 步骤 5：发送端口信息**

在身份验证步骤之后，从服务器将执行命令 REPLCONF listening-port \<port-number\>，向主服务器发送从服务器的监听端口号

例如，从服务器的监听端口为 12345，那么从服务器将向主服务器发送命令 REPLCONF listening-port 12345，如图所示：

<div align="center"> <img width="43%" src="https://api.zhengjianting.com/picture?name=replication_15-20"/> </div> <br>

主服务器在接收到这个命令之后，会将端口号记录在从服务器所对应的客户端状态的 slave_listening_port 属性中：

```c
typedef struct redisClient {
    // ...
    int slave_listening_port; // 从服务器的监听端口号
    // ...
}
```

slave_listening_port 属性目前唯一的作用就是在主服务器执行 INFO replication 命令时打印出从服务器的端口号

**6.6 步骤 6：同步**

在这一步，从服务器将向主服务器发送 _PSYNC_ 命令，执行同步操作，并将自己的数据库更新至主服务器数据库当前所处的状态

值得一提的是，在同步操作执行之前，只要从服务器是主服务器的客户端，但是在执行同步操作之后，主服务器也会成为从服务器的客户端：

- 如果 _PSYNC_ 命令执行的是完整重同步操作，那么主服务器需要成为从服务器的客户端，才能将保存在缓冲区里面的写命令发送给从服务器执行
- 如果 _PSYNC_ 命令执行的是部分重同步操作，那么主服务器需要成为从服务器的客户端，才能向从服务器发送保存在复制积压缓冲区里面的写命令

因此，在同步操作执行之后，主从服务器双方都是对方的客户端，它们可以互相向对方发送命令请求，或者互相向对方返回命令回复，如图所示：

<div align="center"> <img width="42%" src="https://api.zhengjianting.com/picture?name=replication_15-22"/> </div> <br>

正因为主服务器成为了从服务器的客户端，所以主服务器才可以通过发送写命令来改变从服务器的数据库状态，不仅同步操作需要用到这一点，这也是主服务器对从服务器执行命令传播操作的基础

**6.7 步骤 7：命令传播**

当完成了同步之后，主从服务器就会进入命令传播阶段，这时主服务器只要一直将自己执行的写命令发送给从服务器，而从服务器只要一直接收并执行主服务器发来的写命令，就可以保证主从服务器一直保持一致了

**7. 心跳检测**

在命令传播阶段，从服务器默认会以每秒一次的频率，向主服务器发送命令：

```shell
REPLCONF ACK <replication_offset>
```

其中 replication_offset 是从服务器当前的复制偏移量

发送 _REPLCONF ACK_ 命令对于主从服务器有三个作用：

- 检测主从服务器的网络连接状态
- 辅助实现 min-slaves 选项
- 检测命令丢失

**7.1 检测主从服务器的网络连接状态**

主从服务器可以通过发送和接收 _REPLCONF ACK_ 命令来检查两者之间的网络连接是否正常：如果主服务器超过一秒钟没有收到从服务器发来的 _REPLCONF ACK_ 命令，那么主服务器就直到主从服务器之间的连接出现问题了

通过向主服务器发送 _INFO replication_ 命令，在列出的从服务器列表的 lag 一栏中，我们可以看到相应从服务器最后一次向主服务器发送 _REPLCONF ACK_ 命令距离现在过了多少秒

在一般情况下，lag 的值应该在 0 秒或者 1 秒之间跳动，如果超过 1 秒的话，那么说明主从服务器之间的连接出现了故障

**7.2 辅助实现 min-slaves 配置选项**

Redis 的 min-slaves-to-write 和 min-slaves-max-lag 两个选项可以防止主服务器在不安全的情况下执行写命令

例如，如果我们向主服务器提供以下配置：

```shell
min-slaves-to-write 3
min-slaves-max-lag 10
```

那么在从服务器的数量少于 3 个，或者 3 个从服务器的延迟 ( lag ) 值都大于或等于 10 秒时，主服务器将拒绝执行写命令，这里的延迟值就是上面提到的 _INFO replication_ 命令的 lag 值

**7.3 检测命令丢失**

如果因为网络故障，主服务器传播给从服务器的写命令在半路丢失，那么当从服务器向主服务器发送 _REPLCONF ACK_ 命令时，主服务器将发觉从服务器当前的复制偏移量少于自己的复制偏移量，然后主服务器就会根据从服务器提交的复制偏移量，在复制积压缓冲区里面找到从服务器缺少的数据，并将这些数据重新发送给从服务器

注意，主服务器向从服务器补发缺失数据这一操作的原理和部分重同步操作的原理非常相似，这两个操作的区别在于，补发缺失数据操作在主从服务器没有断线的情况下执行，而部分重同步操作则在主从服务器断线并重连之后执行



## 配置

在一台虚拟机中启动 3 个 redis 进程，其中运行在 6379 端口的 redis 进程作为主服务器，运行在 6380 和 6381 端口的 redis 进程作为从服务器

**1. 修改配置文件**

```shell
# 为 3 个 redis 进程分别创建一个目录
mkdir ~/redis_replication/redis_6379
mkdir ~/redis_replication/redis_6380
mkdir ~/redis_replication/redis_6381

# 为运行在 6379 端口的 redis 进程 (Master) 新建 redis.conf
vim ~/redis_replication/redis_6379/redis.conf
include /etc/redis.conf # 以 /etc/redis.conf 作为模板
port 6379
pidfile /var/run/redis_6379.pid # 当服务器运行多个 redis 进程时, 配置文件需要指定不同的 pidfile
logfile /home/zjt/redis_replication/redis_6379/redis_log
dbfilename dump_6379.rdb

# 为运行在 6380 端口的 redis 进程 (Slave1) 新建 redis.conf
vim ~/redis_replication/redis_6380/redis.conf
include /etc/redis.conf
port 6380
pidfile /var/run/redis_6380.pid
logfile /home/zjt/redis_replication/redis_6380/redis_log
dbfilename dump_6380.rdb

# 为运行在 6381 端口的 redis 进程 (Slave2) 新建 redis.conf
vim ~/redis_replication/redis_6381/redis.conf
include /etc/redis.conf
port 6381
pidfile /var/run/redis_6381.pid
logfile /home/zjt/redis_replication/redis_6381/redis_log
dbfilename dump_6381.rdb
```

**2. 启动 3 个 redis 进程**

```shell
cd ~/redis_replication/redis_6379
redis-server redis.conf

cd ~/redis_replication/redis_6380
redis-server redis.conf

cd ~/redis_replication/redis_6381
redis-server redis.conf
```

**3. 启动 3 个 redis 客户端**

```shell
cd ~/redis_replication/redis_6379
redis-cli -h 127.0.0.1 -p 6379

cd ~/redis_replication/redis_6380
redis-cli -h 127.0.0.1 -p 6380

cd ~/redis_replication/redis_6381
redis-cli -h 127.0.0.1 -p 6381
```

**4. 复制**

```shell
127.0.0.1:6380> slaveof 127.0.0.1 6379
127.0.0.1:6381> slaveof 127.0.0.1 6379
```

**5. 查看复制状态信息**

<div align="center"> <img width="46%" src="https://api.zhengjianting.com/picture?name=replication_info_1"/> </div> <br>

<div align="center"> <img width="46%" src="https://api.zhengjianting.com/picture?name=replication_info_2"/> </div> <br>

<div align="center"> <img width="46%" src="https://api.zhengjianting.com/picture?name=replication_info_3"/> </div> <br>

**6. 断开复制**

_slaveof_ 命令不但可以建立复制，还可以在从服务器执行 _slaveof no one_ 来断开与主服务器的复制关系，例如在 6380 节点上执行 _slaveof no one_ 来断开复制，如图所示：

<div align="center"> <img width="47%" src="https://api.zhengjianting.com/picture?name=replication_6-2"/> </div> <br>

断开复制主要流程：

- 断开与主节点复制关系
- 从节点晋升为主节点

从节点断开复制后并不会抛弃原有数据，只是无法再获取主节点上的数据变化

**7. 切换主节点**

通过 _slaveof_ 命令还可以实现切主操作，即把当前从节点对主节点的复制切换到另一个主节点，例如把 6380 节点从原来的复制 6379 节点变为复制 6381 节点，如图所示：

<div align="center"> <img width="46%" src="https://api.zhengjianting.com/picture?name=replication_6-3"/> </div> <br>

切主操作流程如下：

- 断开与旧主节点复制关系
- 与新主节点建立复制关系
- 删除从节点当前所有数据
- 对新主节点进行复制操作

此时 6380 节点复制 6381 节点，6381 节点复制 6379 节点，复制状态信息如下：

<div align="center"> <img width="46%" src="https://api.zhengjianting.com/picture?name=replication_info_4"/> </div> <br>

<div align="center"> <img width="46%" src="https://api.zhengjianting.com/picture?name=replication_info_5"/> </div> <br>

<div align="center"> <img width="46%" src="https://api.zhengjianting.com/picture?name=replication_info_6"/> </div> <br>

注意切主后从节点会清空之前所有的数据，线上人工操作时小写 _slaveof_ 在错误的节点上执行或者指向错误的主节点



## 参考

《 Redis 设计与实现 》

《 Redis 开发与运维 》