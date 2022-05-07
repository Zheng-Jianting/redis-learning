## RDB 持久化

Redis 是一个键值对数据库服务器，服务器中通常包含着任意个非空数据库，而每个非空数据库又可以包含任意个键值对，为了方便起见，我们将服务器中的非空数据库以及它们的键值对称为数据库状态

<img src="C:\Users\zjt\AppData\Roaming\Typora\typora-user-images\image-20220501151814653.png" alt="image-20220501151814653" style="zoom:80%;" />

因为 Redis 是内存数据库，它将自己的数据库状态存储在内存里面，所以如果不想办法将存储在内存中的数据库状态保存到磁盘里面，那么一旦服务器进程退出，服务器中的数据库状态也会消失不见

为了解决这个问题，Redis 提供了 RDB 持久化功能 ( Redis DataBase )，这个功能可以将 Redis 在内存中的数据库状态保存到磁盘里面，避免数据意外丢失，RDB 持久化功能所生成的 RDB 文件是一个经过压缩的二进制文件，通过该文件可以还原生成 RDB 文件时的数据库状态

<img src="C:\Users\zjt\AppData\Roaming\Typora\typora-user-images\image-20220501152244123.png" alt="image-20220501152244123" style="zoom:80%;" />

RDB 的相关配置在配置文件 redis.conf 中的 SNAPSHOTTING 部分

**1. RDB 文件的创建与载入**

有两个 Redis 命令可以用于生成 RDB 文件，一个是 _SAVE_，另一个是 _BGSAVE_

_SAVE_ 命令会阻塞 Redis 服务器进程，直到 RDB 文件创建完毕为止，在服务器进程阻塞期间，服务器不能处理任何命令请求

_BGSAVE_ 命令会派生出一个子进程，然后由子进程负责创建 RDB 文件，服务器进程 ( 父进程 ) 继续处理命令请求

创建 RDB 文件的实际工作由 rdb.c / rdbSave 函数完成，_SAVE_ 命令和 _BGSAVE_ 命令会以不同的方式调用这个函数：

```python
def SAVE():
	# 创建 RDB 文件
	rdbSave()

def BGSAVE():
    # 创建子进程, 父进程返回子进程的 pid (非负整数), 子进程返回 0
    pid = fork()
    if pid == 0:
        # 子进程负责创建 RDB 文件
        rdbSave()
        # 完成之后向父进程发送信号
        signal_parent()
    elif pid > 0:
        # 父进程继续处理命令请求, 并通过轮询等待子进程的信号
        handle_request_and_wait_signal()
    else:
        # 处理异常
        handle_fork_error()
```

和使用 _SAVE_ 命令或者 _BGSAVE_ 命令创建 RDB 文件不同，RDB 文件的载入工作是在服务器启动时自动执行的，所以 Redis 并没有专门用于载入 RDB 文件的命令，只要 Redis 服务器在启动时检测到 RDB 文件存在，它就会自动载入 RDB 文件

另外值得一提的是，因为 AOF 文件的更新频率通常比 RDB 文件的更新频率高，所以：

- 如果服务器开启了 AOF 持久化功能，那么服务器会优先使用 AOF 文件来还原数据库状态
- 只有在 AOF 持久化功能处于关闭状态时，服务器才会使用 RDB 文件来还原数据库状态

<img src="C:\Users\zjt\AppData\Roaming\Typora\typora-user-images\image-20220501153956478.png" alt="image-20220501153956478" style="zoom:80%;" />

**1.1 SAVE 命令执行时的服务器状态**

当 _SAVE_ 命令执行时，Redis 服务器会被阻塞，所以当 _SAVE_ 命令正在执行时，客户端发送的所以命令请求都会被拒绝

**1.2 BGSAVE 命令执行时的服务器状态**

因为 _BGSAVE_ 命令的保存工作是由子进程执行的，所以在子进程创建 RDB 文件的过程中，Redis 服务器仍然可以继续处理客户端的命令请求，但是，在 _BGSAVE_ 命令执行期间，服务器处理 _SAVE_、_BGSAVE_、_BGREWRITEAOF_ 三个命令的方式会和平时有所不同

- _SAVE_：在 _BGSAVE_ 命令执行期间，客户端发送的 _SAVE_ 命令会被服务器拒绝，服务器禁止 _SAVE_ 命令和 _BGSAVE_ 命令同时执行是为了避免父进程 ( 服务器进程 ) 和子进程同时执行两个 rdbSave 调用，防止产生竞争条件
- _BGSAVE_：在 _BGSAVE_ 命令执行期间，客户端发送的 _BGSAVE_ 命令也会被服务器拒绝，因为同时执行两个 _BGSAVE_ 命令也会产生竞争条件
- _BGREWRITEAOF_：_BGSAVE_ 命令和 _BGREWRITEAOF_ 命令不能同时执行，这两个命令的实际工作都由子进程执行，所以这两个命令在操作方面并没有什么冲突的地方，不能同时执行它们只是一个性能方面的考虑——并发出两个子进程，并且这两个子进程都同时执行大量的磁盘写入操作，这怎么想都不会是一个好主意

**1.3 RDB 文件载入时的服务器状态**

服务器在载入 RDB 文件期间，会一直处于阻塞状态，直到载入工作完成为止

**2. 自动间隔保存**

Redis 允许用户通过设置服务器配置的 save 选项，让服务器每隔一段时间自动执行一次 _BGSAVE_ 命令，用户可以通过 save 选项设置多个保存条件，但只要其中任意一个条件被满足，服务器就会执行 _BGSAVE_ 命令

<img src="C:\Users\zjt\AppData\Roaming\Typora\typora-user-images\image-20220501160751747.png" alt="image-20220501160751747" style="zoom:80%;" />

例如在 redis.conf 中提供以下配置：

```shell
save 900 1
save 300 10
save 60 10000
```

那么只要满足以下三个条件中的任意一个，_BGSAVE_ 命令就会被执行：

- 服务器在 900 秒之内，对数据库进行了至少 1 次修改
- 服务器在 300 秒之内，对数据库进行了至少 10 次修改
- 服务器在 60 秒之内，对数据库进行了至少 10000 次修改

在接下来的内容中，我们将介绍 Redis 服务器是如何根据 save 选项设置的保存条件，自动执行 _BGSAVE_ 命令的

**2.1 设置保存条件**

当 Redis 服务器启动时，服务器程序会根据 save 选项所设置的保存条件，设置服务器状态 redisServer 结构的 saveparams 属性：

```c
struct redisServer {
    // ...
    struct saveparam *saveparams; // 记录了保存条件的数组
    // ...
}
```

saveparams 属性是一个数组，数组中的每个元素都是一个 saveparam 结构，每个 saveparam 结构都保存了一个 save 选项设置的保存条件：

```c
struct saveparam {
    time_t seconds; // 秒数
    int changes; // 修改数
}
```

比如说，如果 save 选项设置的值为以下条件：

```shell
save 900 1
save 300 10
save 60 10000
```

那么服务器状态中的 saveparams 数组将会是下图所示的样子：

<img src="C:\Users\zjt\AppData\Roaming\Typora\typora-user-images\image-20220501163717345.png" alt="image-20220501163717345" style="zoom:80%;" />

**2.2 dirty 计数器和 lastsave 属性**

除了 saveparams 数组之外，服务器状态还维持着一个 dirty 计数器，以及一个 lastsave 属性：

- dirty 计数器记录距离上一次成功执行 _SAVE_ 命令或者 _BGSAVE_ 命令之后，服务器对数据库状态 ( 服务器中的所有数据库 ) 进行了多少次修改 ( 包括写入、删除、更新等操作 )
- lastsave 属性是一个 UNIX 时间戳，记录了服务器上一次成功执行 _SAVE_ 命令或者 _BGSAVE_ 命令的时间

```c
struct redisServer {
    // ...
    long long dirty; // 计数器
    time_t lastsave; // 上一次执行保存的时间
    // ...
}
```

**2.3 检查保存条件是否满足**

Redis 的服务器周期性操作函数 serverCron 默认每隔 100ms 就会执行一次，该函数用于对正在运行的服务器进行维护，它的其中一项工作就是检查 save 选项所设置的保存条件是否已经满足，如果满足的话，就执行 _BGSAVE_ 命令

```python
def serverCron():
    # ...
    # 遍历所有保存条件
    for saveparam in server.saveparams:
        # 计算距离上次执行保存操作由多少秒
        save_interval = unixtime_now() - server.lastsave
        
        # 如果数据库状态的修改次数超过保存条件所设置的次数
        # 并且距离上次执行保存操作的时间超过保存条件所设置的时间
        # 那么执行保存操作
        if server.dirty >= savaparam.changes and save_interval > saveparam.seconds:
            BGSAVE()
    # ...
```

**3. RDB 文件结构**

<img src="C:\Users\zjt\AppData\Roaming\Typora\typora-user-images\image-20220501171027870.png" alt="image-20220501171027870" style="zoom:80%;" />

RDB 文件的最开头是 REDIS 部分，这个部分的长度为 5 字节，保存着 "REDIS" 五个字符，通过这五个字符，程序可以在载入文件时，快速检查所载入的文件是否 RDB 文件

db_version 长度为 4 字节，它的值是一个字符串表示的整数，这个整数记录了 RDB 文件的版本号，比如 "0006" 就代表 RDB 文件的版本为第六版，本章只介绍第六版 RDB 文件的结构

databases 部分包含零个或任意多个数据库，以及各个数据库中的键值对数据

- 如果服务器的数据库状态为空 ( 所有数据库都是空的 )，那么这个部分也为空，长度为 0 字节

  <img src="C:\Users\zjt\AppData\Roaming\Typora\typora-user-images\image-20220501171932376.png" alt="image-20220501171932376" style="zoom:80%;" />

- 如果服务器的数据状态为非空 ( 有至少一个数据库非空 )，那么这个部分也为非空，根据数据库所保存键值对的数量、类型和内容不同，这个部分的长度也会有所不同

  例如，如果服务器的 0 号数据库和 3 号数据库非空，那么服务器将创建一个如下图所示的 RDB 文件，图中的 database 0 代表 0 号数据库中的所有键值对数据，而 database 3 则代表 3 号数据库中的所有键值对数据

  <img src="C:\Users\zjt\AppData\Roaming\Typora\typora-user-images\image-20220501172708734.png" alt="image-20220501172708734" style="zoom:80%;" />

  每个非空数据库在 RDB 文件中都可以保存为 SELECTDB、db_number、key_value_pairs 三个部分，如图所示

  <img src="C:\Users\zjt\AppData\Roaming\Typora\typora-user-images\image-20220501172831246.png" alt="image-20220501172831246" style="zoom:80%;" />

EOF 常量的长度为 1 字节，这个常量标志着 RDB 文件正文内容的结束，当读入程序遇到这个值的时候，它知道所有数据库的所有键值对都已经载入完毕了

check_sum 是一个 8 字节长的无符号整数，保存着一个校验和，这个校验和是程序通过对 REDIS、db_version、databases、EOF 四个部分的内容进行计算得出的。服务器在载入 RDB 文件时，会将载入数据所计算出的校验和与 check_sum 所记录的校验和进行对比，以此来检查 RDB 文件是否有出错或者损坏的情况出现

**4. 分析 RDB 文件**

```shell
od -c dump.rdb # 以 ASCII 编码显示文件内容
od -cx dump.rdb # 以 ASCII 编码和十六进制格式打印 RDB 文件
```

