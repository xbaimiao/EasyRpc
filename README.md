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
val client = NettyRpcClient("127.0.0.1", 29090, nodeId = "lobby-1").connect()

val result = DemoRpc.PING
    .args("hello")
    .call(client, RpcTarget.service())
    .get()
```

`NettyRpcClient.connect()` 会启动连接生命周期。service 临时不可用或连接中断时，client 会自动重连；
只有手动调用 `client.close()` 才会停止重连并释放 endpoint、handler 线程池和 Netty event loop。

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

## 目标类型

```kotlin
RpcTarget.service()        // 调 service 节点
RpcTarget.node("client-b") // 调指定节点
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

## protobuf payload

RPC frame 本身已经是 protobuf。业务参数默认用 `RpcCodec` 编码；如果业务 payload 也想用 protobuf：

```kotlin
val ANCHOR = RpcCodecs.protobuf(MyProto.Anchor::parseFrom)

val QUERY = rpc("query")
    .param(RpcCodecs.STRING)
    .returnsNullable(ANCHOR)
```

## 一键测试项目

```powershell
gradle :easy-rpc-test:run
```

测试会启动一个 service 和两个 client，覆盖：

- client -> service
- client -> 指定 client
- client -> allClients
- client -> all
- service 错误返回

预期输出类似：

```text
service started on 127.0.0.1:29190
SERVICE => service:pong:hello
CLIENT_B => client-b:echo:direct
ALL_CLIENTS => client-a:echo:broadcast-client, client-b:echo:broadcast-client
ALL => client-a:echo:broadcast-all, client-b:echo:broadcast-all, service:echo:broadcast-all
ADD => 42
FAIL => test_error:this is expected
```

