# EasyRpc 设计计划

## 当前设计

EasyRpc 是中心路由式 RPC：

- `NettyRpcServer` 是普通 JVM service，同时也是 router。
- `NettyRpcClient` 连接到 service，并用 HELLO frame 注册自己的 `nodeId`。
- service 和 client 都拥有 `RpcEndpoint`，都可以注册 handler。
- 请求 frame 带 `RpcTarget`，router 根据 target 转发。

## 为什么 listen 需要 endpoint

`RpcMethod` 只是协议定义，不保存运行时状态。handler 必须挂到某个运行时节点上，例如 service endpoint 或某个 client endpoint。

为了使用更顺手，运行时节点提供了便捷方法：

```kotlin
server.listen(MyRpc.PING) { ... }
client.listen(MyRpc.PING) { ... }
```

底层仍然是注册到对应 endpoint。

## 目标路由

- `RpcTarget.service()`: service 本机处理。
- `RpcTarget.node(id)`: 指定 node 处理，可以是 service 或某个 client。
- `RpcTarget.allClients()`: 所有 client 处理，调用端使用 `callAll` 收集响应。
- `RpcTarget.all()`: service 和所有 client 都处理，调用端使用 `callAll` 收集响应。

## 编码

- 外层 RPC frame 使用 protobuf：`easy-rpc-core/src/main/proto/easy_rpc.proto`。
- 内层业务参数由 `RpcCodec` 控制。
- 业务对象可以使用 `RpcCodecs.protobuf(...)` 继续走 protobuf。

## 后续扩展

- 增加认证 token，放在 HELLO 或 frame metadata 中。
- 增加心跳和自动重连。
- 增加服务发现，把中心 router 替换成多 router 集群。
- 增加 request traceId，便于日志排查。
