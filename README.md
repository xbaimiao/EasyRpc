# EasyRpc

EasyRpc 是一套轻量 Kotlin RPC。核心目标是：RPC 集中声明，调用时能选择目标，service 和 client 都能注册 handler。

底层现在使用：

- Netty 作为网络 transport。
- protobuf 作为 RPC frame 编码。
- service 作为中心 router，负责把请求转发给 service 自己、指定 client、所有节点或所有 client。

## 模块

- `easy-rpc-core`: RPC DSL、Codec、protobuf frame、目标路由模型、endpoint。
- `easy-rpc-service`: `NettyRpcServer`，普通 JVM 服务端，同时也是路由中心。
- `easy-rpc-client-sdk`: `NettyRpcClient`，普通 JVM 客户端 SDK。
- `easy-rpc-client-plugin`: Paper 插件版客户端。
- `easy-rpc-test`: 可运行测试项目。

## 定义 RPC

```kotlin
object DemoRpc : RpcGroup("demo") {
    val PING = rpc("ping")
        .param(RpcCodecs.STRING)
        .returns(RpcCodecs.STRING)

    val ADD = rpc("add")
        .param(RpcCodecs.INT)
        .param(RpcCodecs.INT)
        .returns(RpcCodecs.INT)
}
```

## service

```kotlin
val server = NettyRpcServer(host = "0.0.0.0", port = 29090, nodeId = "service")

DemoRpc.PING.listen(server) { text ->
    "pong:$text"
}

server.start()
server.awaitClose()
```

## client

```kotlin
val client = NettyRpcClient(
    "127.0.0.1",
    29090,
    nodeId = "lobby-1",
    tags = setOf("lobby"),
    displayName = "大厅一号",
    metadata = mapOf("version" to "1.20.4"),
).connect()

val result = DemoRpc.PING
    .args("hello")
    .call(client, RpcTarget.service())
    .get()
```

`NettyRpcClient.connect()` 会启动连接生命周期。service 临时不可用或连接中断时，client 会自动重连；
只有手动调用 `client.close()` 才会停止重连并释放 endpoint、handler 线程池和 Netty event loop。

## 节点元数据

每个 client 有四份自我描述，都会在 HELLO 里上报给 service：

| 字段 | 说明 |
| --- | --- |
| `nodeId` | 唯一 ID，路由用的就是它 |
| `displayName` | 给人看的名字，没声明时回退成 `nodeId` |
| `tags` | 标签集合，可以用 `RpcTarget.tag()` 批量调用 |
| `metadata` | 自定义键值，含义由业务自己约定 |

service 会保存所有在线 client 的这些字段，并通过 `CLIENTS_SYNC` 同步给每个 client。
所以任意 client 和 service 都能查到全量信息：

```kotlin
val clients = client.onlineClients()              // List<RpcClientInfo>
val nodeIds = client.onlineNodeIds()
val lobbyClients = client.onlineClientsByTag("lobby")

val info = client.onlineClient("lobby-1")
info?.nodeId
info?.displayName
info?.tags
info?.metadata("version")

// 单字段快捷读取
client.displayNameOf("lobby-1")
client.tagsOf("lobby-1")
client.metadataOf("lobby-1", "version")
```

service 侧是同一套 API：

```kotlin
val clients = server.onlineClients()
val lobbyClients = server.onlineClientsByTag("lobby")
server.displayNameOf("lobby-1")
server.metadataOf("lobby-1", "version")
```

client 自己的元数据用 `selfInfo` 读，用 `updateSelfInfo` / `updateMetadata` 改。
改完会立刻重发 HELLO，service 和其它 client 都会收到新值；断线期间调用不报错，新值会在下次重连时带上：

```kotlin
client.selfInfo          // RpcClientInfo(nodeId, tags, displayName, metadata)
client.displayName
client.tags
client.metadata

client.updateSelfInfo(displayName = "大厅一号(维护中)")
client.updateMetadata("players", "42")
client.updateMetadata("players", null)   // 传 null 删除这个键
```

handler 里可以用 `listenAsync` 拿到 `RpcSource`，直接读调用方的这些字段，不需要再查注册表：

```kotlin
DemoRpc.PING.listenAsync(server) { source, text ->
    println("${source.nodeId} / ${source.displayName} / ${source.tags} / ${source.metadata}")
    CompletableFuture.completedFuture("pong:$text")
}
```

## client 注册 RPC

client 也可以 listen，所以另一个 client 能调它：

```kotlin
DemoRpc.PING.listen(clientB) { text ->
    "client-b:$text"
}

val result = DemoRpc.PING
    .args("hello")
    .call(clientA, RpcTarget.node("client-b"))
    .get()
```

## Paper 插件配置

`easy-rpc-client-plugin` 的 `config.yml`：

```yaml
host: 127.0.0.1
port: 29090

# 共享鉴权 token，必须和 service 的 auth-token 一致
auth-token: 'K7vQ2mX...'

# 本节点唯一 ID，路由靠它，整个集群不能重复
node-id: bukkit-client

# 显示名称，给人看的，留空则回退成 node-id
display-name: '大厅一号'

# 标签，可以用 RpcTarget.tag("lobby") 一次调用所有同 tag 的节点
tags:
  - lobby
  - hub

# 自定义元数据。键值含义完全由你自己约定
metadata:
  version: '1.20.4'
  region: cn-east
  max-players: 200
  whitelist: false

connect-on-enable: true
```

`metadata` 的值统一按字符串处理，写数字或 `true`/`false` 也会转成字符串。
键名随便定，`region`、`max-players` 这些都只是示例。

## PlaceholderAPI 变量

装了 PlaceholderAPI 之后，插件会自动注册 `easyrpc` expansion。没装就安静跳过，不影响 RPC。

本机节点：

```text
%easyrpc_node_id%          bukkit-client
%easyrpc_display_name%     大厅一号
%easyrpc_tags%             lobby, hub
%easyrpc_meta_region%      cn-east
%easyrpc_meta_max-players% 200
%easyrpc_connected%        true
%easyrpc_has_tag_lobby%    true
```

查其它节点：在变量后面用 `:` 加目标 nodeId。用 `:` 而不是 `_`，避免和 nodeId 里的下划线混在一起：

```text
%easyrpc_display_name:lobby-1%
%easyrpc_tags:lobby-1%
%easyrpc_meta_version:lobby-1%
%easyrpc_has_tag_lobby:lobby-1%
%easyrpc_online:lobby-1%          目标是否在线，true / false
```

在线列表：

```text
%easyrpc_online_count%            在线 client 数量
%easyrpc_online_nodes%            在线 nodeId，逗号分隔
%easyrpc_online_names%            在线显示名称，逗号分隔
%easyrpc_online_count_tag:lobby%  带 lobby tag 的在线数量
%easyrpc_online_nodes_tag:lobby%  带 lobby tag 的在线 nodeId
```

节点不在线或 metadata 键不存在时返回空字符串，可以直接拼进消息里。

## 目标类型

```kotlin
RpcTarget.service()        // 调 service 节点
RpcTarget.node("client-b") // 调指定节点
RpcTarget.tag("lobby")     // 调所有带 lobby tag 的 client，需要 callAll
RpcTarget.allClients()     // 调所有 client，需要 callAll
RpcTarget.all()            // 调 service + 所有 client，需要 callAll
```

广播调用使用 `callAll`：

```kotlin
val replies = DemoRpc.PING
    .args("broadcast")
    .callAll(client, RpcTarget.allClients(), Duration.ofMillis(500))
    .get()
```

按 tag 广播：

```kotlin
val replies = DemoRpc.PING
    .args("lobby-broadcast")
    .callAll(client, RpcTarget.tag("lobby"), Duration.ofMillis(500))
    .get()
```

## protobuf payload

RPC frame 本身已经是 protobuf。业务参数默认用 `RpcCodec` 编码；如果业务 payload 也想用 protobuf：

```kotlin
val ANCHOR = RpcCodecs.protobuf(MyProto.Anchor::parseFrom)

val QUERY = rpc("query")
    .param(RpcCodecs.STRING)
    .returnsNullable(ANCHOR)
```

## 鉴权

service 和所有 client 共用一个 token。client 在 HELLO 里带上它，service 校验通过才会把这条连接接入路由。

**默认是开启的**：service 首次启动生成 `easyrpc-service.yml` 时会随机写入一个 token，
把它复制到每个 client 的配置里即可。

service 侧：

```yaml
# easyrpc-service.yml
auth-token: 'K7vQ2mX...'    # 首次启动自动生成
```

client 侧（Paper 插件 `config.yml`）：

```yaml
auth-token: 'K7vQ2mX...'    # 和 service 完全一致
```

SDK 直接传构造参数：

```kotlin
val client = NettyRpcClient(
    "127.0.0.1", 29090,
    nodeId = "lobby-1",
    authToken = "K7vQ2mX...",
).connect()
```

也可以用环境变量 `EASYRPC_AUTH_TOKEN` 覆盖 service 的配置文件。
需要新 token 时用 `RpcAuth.generateToken()` 生成。

### token 不匹配会怎样

service 回一个 classifier 为 `unauthorized` 的 ERROR frame，然后断开连接。client 收到后：

- **停止自动重连** —— 错 token 重试一万次还是错的，与其无限刷日志不如停下来等人改配置。
- 触发 `onAuthFailure` 回调，插件会把原因打进控制台。
- 之后调用 `call` / `callAll` 会直接抛异常，而不是等到超时。

改好配置后不需要重启服务器：

```text
/easyrpc reconnect
```

这条命令会重新读 `config.yml` 的 token 并重连，**已经注册的 listen handler 全部保留**
（内部走 `retryAuth` 而不是重建 client，否则其它插件挂在 endpoint 上的 handler 会全部丢掉）。

SDK 侧对应 `client.retryAuth(newToken)`，用 `client.isAuthRejected()` 判断当前是不是被拒状态。

### 关掉鉴权

`auth-token` 留空表示不启用，任何能连上端口的节点都能接入。只建议本机开发时这么用。
service 启动时会打一条明显的 WARNING 提醒。

### 顺带收紧的一点

不管有没有配 token，**连接必须先用 HELLO 完成握手才会被路由**。
之前未握手的连接就能直接发 REQUEST 调用任意 RPC，现在会收到 `unauthorized` 并被断开。
连上但迟迟不发 HELLO 的连接会在 10 秒后被主动断开，避免占着 socket 不放。

### 这套机制不解决什么

- **不加密传输**，见 [网络和安全](#4-网络和安全)。
- **不区分节点权限**，token 对了就能调用所有已注册的 RPC，没有按 nodeId 或 tag 的细粒度授权。
- **不防重放**，token 是固定值而非一次性挑战，能抓到包就能拿 token 自己连上来。

真要抗这些，得上 mTLS 或 HMAC 挑战握手，目前没做。

## 部署

整套东西分两半：一个独立跑的 service 进程（路由中心），加 N 个接进来的 client。

### 1. 构建

```powershell
gradle build
```

产物：

| 文件 | 用途 |
| --- | --- |
| `easy-rpc-service/build/libs/easy-rpc-service.jar` | service 可执行 fat jar，依赖全打进去了 |
| `easy-rpc-client-plugin/build/libs/easy-rpc-client-plugin-<版本>.jar` | Paper 插件版 client |

只要 service 可以用 `gradle :easy-rpc-service:shadowJar`，只要插件用 `gradle :easy-rpc-client-plugin:shadowJar`。

### 2. 部署 service

放到一台各游戏服都能连到的机器上，直接跑：

```bash
java -jar easy-rpc-service.jar
```

首次启动会在工作目录生成 `easyrpc-service.yml`：

```yaml
host: 0.0.0.0
port: 29090
node-id: service
display-name: ''
```

环境变量优先级高于配置文件，容器里更方便：

```bash
EASYRPC_HOST=0.0.0.0 EASYRPC_PORT=29090 EASYRPC_NODE_ID=service \
EASYRPC_DISPLAY_NAME=生产路由 java -jar easy-rpc-service.jar
```

进程会阻塞在 `awaitClose()`，收到 SIGTERM 时走 shutdown hook 正常关闭，所以可以直接交给 systemd 托管：

```ini
[Unit]
Description=EasyRpc Service
After=network.target

[Service]
Type=simple
User=minecraft
WorkingDirectory=/opt/easyrpc
ExecStart=/usr/bin/java -jar /opt/easyrpc/easy-rpc-service.jar
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
```

### 3. 部署 client

Paper 服务器：把 `easy-rpc-client-plugin-<版本>.jar` 丢进 `plugins/`，启动一次生成 `plugins/EasyRpcClient/config.yml`，
按上面 [Paper 插件配置](#paper-插件配置) 填好 `host`、`port`、`node-id`，重启。

**每个节点的 `node-id` 必须唯一**，重复的话后连上的会把先连的踢下线（service 按 nodeId 覆盖注册表）。

普通 JVM 程序作为 client，就依赖 `easy-rpc-client-sdk` 自己 `new NettyRpcClient(...)`。

service 没起来或者中途挂了都不用管顺序，client 会按 1s、2s、4s ... 最大 30s 退避自动重连。

### 4. 网络和安全

service 支持共享 token 鉴权，见下面的 [鉴权](#鉴权) 章节。

⚠️ **鉴权只解决「谁能接入」，不提供传输加密。** token 和所有业务 payload 都是明文过线的，
抓包能看到全部内容，也能拿到 token 本身。所以：

- 不要把 29090 暴露到公网，只在内网或 VPN 里开放。
- 用防火墙按来源 IP 白名单限制，例如 `ufw allow from 10.0.0.0/24 to any port 29090`。
- 跨机房走 WireGuard 之类的隧道，不要裸奔。

也就是说，鉴权是「多一道锁」，不是「可以放心暴露到公网」的许可。

### 5. 版本一致性

service 和所有 client 应该用同一次构建的产物。frame 是 protobuf，新增字段向后兼容（老节点读到空值），
但业务侧的 `RpcGroup` 定义必须两边一致，否则会拿到 `not_found` 错误。

## 一键测试项目

```powershell
gradle :easy-rpc-test:run
```

测试会启动一个 service 和两个 client，覆盖：

- client -> service
- client -> 指定 client
- client -> allClients
- client -> all
- client -> tag
- service 错误返回

预期输出类似：

```text
service started on 127.0.0.1:29190
SERVICE => service:pong:hello
CLIENT_B => client-b:echo:direct
ALL_CLIENTS => client-a:echo:broadcast-client, client-b:echo:broadcast-client
ALL => client-a:echo:broadcast-all, client-b:echo:broadcast-all, service:echo:broadcast-all
TAG_LOBBY => client-a:echo:broadcast-lobby, client-b:echo:broadcast-lobby
ADD => 42
FAIL => test_error:this is expected
```

