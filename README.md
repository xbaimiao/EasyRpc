# EasyRpc

EasyRpc 是一套轻量 Kotlin RPC 骨架，目标是复刻 dispatcher 项目里集中声明 RPC、调用端 `.args(...).call(...)`、服务端 `.listen { ... }` 的使用体验。

## 模块

- `easy-rpc-core`: RPC DSL、Codec、请求响应帧、超时和错误模型。
- `easy-rpc-service`: 普通 JVM TCP 服务端，不是 Bukkit 插件。
- `easy-rpc-client-sdk`: 普通 JVM 客户端 SDK。
- `easy-rpc-client-plugin`: Paper 插件版客户端，启动时连接 RPC 服务。

## 定义协议

```kotlin
import com.xbaimiao.easyrpc.codec.RpcCodecs
import com.xbaimiao.easyrpc.dsl.RpcGroup

object DemoRpc : RpcGroup("demo") {
    val PING = rpc("ping")
        .param(RpcCodecs.STRING)
        .returns(RpcCodecs.STRING)

    val CREATE_ANCHOR = rpc("create_anchor")
        .param(RpcCodecs.STRING)
        .param(RpcCodecs.DOUBLE)
        .param(RpcCodecs.list(RpcCodecs.STRING))
        .returns(RpcCodecs.INT)
}
```

## 服务端

```kotlin
val server = TcpRpcServer(serviceName = "demo", port = 29090)

DemoRpc.PING.listen(server.endpoint) { text ->
    "pong: $text"
}

DemoRpc.CREATE_ANCHOR.listen(server.endpoint) { domain, score, players ->
    println("create anchor domain=$domain score=$score players=$players")
    1
}

server.start()
server.awaitClose()
```

## SDK 客户端

```kotlin
val client = TcpRpcClient("127.0.0.1", 29090).connect()
val result = DemoRpc.PING.args("hello").call(client).get()
println(result)
```

## Paper 插件客户端

安装 `easy-rpc-client-plugin` 后，其它插件可以直接取客户端：

```kotlin
val client = EasyRpcClientPlugin.client()
DemoRpc.PING.args("hello").call(client).thenAccept { result ->
    println(result)
}
```

`easy-rpc-client-plugin/src/main/resources/config.yml`:

```yaml
host: 127.0.0.1
port: 29090
node-name: bukkit-client
connect-on-enable: true
```

## protobuf

core 内置了 protobuf codec：

```kotlin
val ANCHOR = RpcCodecs.protobuf(MyProto.Anchor::parseFrom)

val QUERY = rpc("query")
    .param(RpcCodecs.STRING)
    .returnsNullable(ANCHOR)
```

注意 proto 字段号发布后不要修改，不再使用的字段号也不要复用。

## 错误

服务端可以抛 `RpcException`，客户端会收到同样的 classifier：

```kotlin
throw RpcException("duplicate", "playerA playerB")
```
## 一键测试项目

`easy-rpc-test` 会在同一个 JVM 里启动一个本地 `TcpRpcServer`，然后创建 `TcpRpcClient` 调用 `PING`、`ADD` 和错误返回示例：

```bash
gradle :easy-rpc-test:run
```

Windows 下：

```powershell
gradle :easy-rpc-test:run
```

预期输出类似：

```text
service started on 127.0.0.1:29190
PING => pong:hello
ADD => 42
FAIL => test_error:this is expected
```


