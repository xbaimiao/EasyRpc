# EasyRpc 实现计划

## 目标

实现一套类似 dispatcher 项目 RPC DSL 的轻量 RPC：

- RPC 定义集中声明。
- 客户端使用 `.args(...).call(client)`。
- 服务端使用 `.listen(endpoint) { ... }`。
- service 是普通 JVM 进程，不做成 Bukkit 插件。
- client 分成普通 SDK 和 Paper 插件包装两份。

## 模块边界

### easy-rpc-core

负责稳定协议和 DSL：

- `RpcCodec<T>`: 基础编码接口。
- `RpcCodecs`: String、Int、Double、List、Nullable、protobuf 等内置 codec。
- `RpcGroup` / `RpcBuilder` / `RpcMethod`: Kotlin DSL。
- `RpcEndpoint`: handler 注册、pending request、超时、错误响应。
- `RpcFrame`: 二进制请求/响应/错误帧。
- `TcpPacketIo`: TCP length-prefix 包读写。

### easy-rpc-service

普通 JVM 服务端：

- `TcpRpcServer`: 基于 `ServerSocket` 的 TCP RPC 服务端。
- `ExampleService`: 最小 ping 示例。

### easy-rpc-client-sdk

普通 JVM 客户端：

- `TcpRpcClient`: TCP 连接、读循环、实现 `RpcCaller`。

### easy-rpc-client-plugin

Paper 插件客户端包装：

- `EasyRpcClientPlugin`: Bukkit 生命周期内创建/关闭 `TcpRpcClient`。
- 其它插件通过 `EasyRpcClientPlugin.client()` 获取客户端。

## 调用链

1. 协议模块中定义：`object DemoRpc : RpcGroup("demo")`。
2. 服务端启动 `TcpRpcServer`，对方法调用 `.listen(server.endpoint)`。
3. 客户端启动 `TcpRpcClient(...).connect()`。
4. 调用端执行 `DemoRpc.PING.args("hello").call(client)`。
5. `RpcEndpoint` 序列化请求帧，TCP 发送。
6. 服务端 `RpcEndpoint` 找 handler、执行、序列化响应。
7. 客户端按 requestId 完成对应 `CompletableFuture`。

## 后续扩展

- 新增 Redis transport：保留 `RpcEndpoint`，替换连接发送/接收层。
- 新增 Netty transport：替换 `TcpRpcServer/TcpRpcClient` 的 Socket 实现。
- 新增 proto 模块：专门放 `.proto` 和生成类，业务 service/sdk/plugin 共同依赖。
- 新增鉴权：在 `RpcFrame` 增加 token 或在连接建立后做 hello/auth RPC。
- 新增服务发现：client-sdk 外层增加 service registry，不改 DSL。
