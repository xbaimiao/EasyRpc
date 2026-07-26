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
- `RpcTarget.tag(tag)`: 拥有指定 tag 的所有 client 处理，调用端使用 `callAll` 收集响应。
- `RpcTarget.allClients()`: 所有 client 处理，调用端使用 `callAll` 收集响应。
- `RpcTarget.all()`: service 和所有 client 都处理，调用端使用 `callAll` 收集响应。

## 编码

- 外层 RPC frame 使用 protobuf：`easy-rpc-core/src/main/proto/easy_rpc.proto`。
- 内层业务参数由 `RpcCodec` 控制。
- 业务对象可以使用 `RpcCodecs.protobuf(...)` 继续走 protobuf。

## 鉴权

共享 token 方案，已实现：

- service 配置 `auth-token`，client 在 HELLO 的 `auth_token` 字段带上同一个值。
- 校验按连接进行，通过后记在 handler 状态里，后续 frame 不再重复携带 token。
- 比较走 `RpcAuth.matches`（`MessageDigest.isEqual`），避免 `==` 短路比较导致的时序侧信道。
- 未握手的连接不允许路由任何 frame；连上不发 HELLO 的连接 10 秒后断开。
- 失败时回 `unauthorized` ERROR 并断开，client 停止自动重连，等运维改配置后 `retryAuth`。
- token 为空表示关闭鉴权，仅用于本机开发，启动时打 WARNING。

明确不在这一层解决的：传输加密、按节点的细粒度授权、重放攻击。
这些要靠 mTLS 或 HMAC 挑战握手，目前没做。

## 后续扩展

- 增加心跳和自动重连。
- 增加服务发现，把中心 router 替换成多 router 集群。
- 增加 request traceId，便于日志排查。
