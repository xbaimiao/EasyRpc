package com.xbaimiao.easyrpc.core

import com.google.protobuf.ByteString
import com.xbaimiao.easyrpc.protocol.EasyRpcProto

/** RPC frame 类型，对应 easy_rpc.proto 里的 FrameType。 */
enum class RpcFrameType {
    /** 普通请求。 */
    REQUEST,
    /** 正常响应。 */
    RESPONSE,
    /** 错误响应，payload 为空，错误信息在 classifier/message 中。 */
    ERROR,
    /** client 连接 service 后发送的注册包，用于声明自己的 nodeId。 */
    HELLO,
    /** service 推送在线 client 列表。 */
    CLIENTS_SYNC,
}

/**
 * 外层 RPC frame 的 Kotlin 模型。
 *
 * 这个对象会通过 [RpcFrameCodec] 转成 protobuf bytes 后交给 Netty 发送。
 * 业务参数不直接放字段里，而是先用 RpcCodec 编码成 [payload]。
 */
data class RpcFrame(
    val type: RpcFrameType,
    val requestId: Long,
    val sourceNode: String,
    val sourceKind: RpcNodeKind,
    val target: RpcTarget,
    val group: String = "",
    val method: String = "",
    val payload: ByteArray = ByteArray(0),
    val errorClassifier: String = "",
    val errorMessage: String = "",
    val sourceTags: Set<String> = emptySet(),
    val onlineClients: List<RpcClientInfo> = emptyList(),
)

/** protobuf frame 编解码。 */
object RpcFrameCodec {
    fun encode(frame: RpcFrame): ByteArray = frame.toProto().toByteArray()

    fun decode(bytes: ByteArray): RpcFrame = EasyRpcProto.RpcFrame.parseFrom(bytes).toModel()

    private fun RpcFrame.toProto(): EasyRpcProto.RpcFrame {
        return EasyRpcProto.RpcFrame.newBuilder()
            .setType(type.toProto())
            .setRequestId(requestId)
            .setSourceNode(sourceNode)
            .setSourceKind(sourceKind.toProto())
            .setTarget(target.toProto())
            .setGroup(group)
            .setMethod(method)
            .setPayload(ByteString.copyFrom(payload))
            .setErrorClassifier(errorClassifier)
            .setErrorMessage(errorMessage)
            .addAllSourceTags(sourceTags)
            .addAllOnlineClients(onlineClients.map { it.toProto() })
            .build()
    }

    private fun EasyRpcProto.RpcFrame.toModel(): RpcFrame {
        return RpcFrame(
            type = type.toModel(),
            requestId = requestId,
            sourceNode = sourceNode,
            sourceKind = sourceKind.toModel(),
            target = target.toModel(),
            group = group,
            method = method,
            payload = payload.toByteArray(),
            errorClassifier = errorClassifier,
            errorMessage = errorMessage,
            sourceTags = sourceTagsList.toSet(),
            onlineClients = onlineClientsList.map { it.toModel() },
        )
    }

    private fun RpcFrameType.toProto(): EasyRpcProto.FrameType = when (this) {
        RpcFrameType.REQUEST -> EasyRpcProto.FrameType.REQUEST
        RpcFrameType.RESPONSE -> EasyRpcProto.FrameType.RESPONSE
        RpcFrameType.ERROR -> EasyRpcProto.FrameType.ERROR
        RpcFrameType.HELLO -> EasyRpcProto.FrameType.HELLO
        RpcFrameType.CLIENTS_SYNC -> EasyRpcProto.FrameType.CLIENTS_SYNC
    }

    private fun EasyRpcProto.FrameType.toModel(): RpcFrameType = when (this) {
        EasyRpcProto.FrameType.REQUEST -> RpcFrameType.REQUEST
        EasyRpcProto.FrameType.RESPONSE -> RpcFrameType.RESPONSE
        EasyRpcProto.FrameType.ERROR -> RpcFrameType.ERROR
        EasyRpcProto.FrameType.HELLO -> RpcFrameType.HELLO
        EasyRpcProto.FrameType.CLIENTS_SYNC -> RpcFrameType.CLIENTS_SYNC
        else -> error("Unsupported RPC frame type: $this")
    }

    private fun RpcNodeKind.toProto(): EasyRpcProto.NodeKind = when (this) {
        RpcNodeKind.SERVICE -> EasyRpcProto.NodeKind.SERVICE
        RpcNodeKind.CLIENT -> EasyRpcProto.NodeKind.CLIENT
    }

    private fun EasyRpcProto.NodeKind.toModel(): RpcNodeKind = when (this) {
        EasyRpcProto.NodeKind.SERVICE -> RpcNodeKind.SERVICE
        EasyRpcProto.NodeKind.CLIENT -> RpcNodeKind.CLIENT
        else -> RpcNodeKind.CLIENT
    }

    private fun RpcTarget.toProto(): EasyRpcProto.RpcTarget {
        val builder = EasyRpcProto.RpcTarget.newBuilder()
        when (this) {
            RpcTarget.Service -> builder.setType(EasyRpcProto.TargetType.TARGET_SERVICE)
            is RpcTarget.Node -> {
                builder.setType(EasyRpcProto.TargetType.TARGET_NODE)
                builder.setNodeId(nodeId)
            }
            RpcTarget.All -> builder.setType(EasyRpcProto.TargetType.TARGET_ALL)
            RpcTarget.AllClients -> builder.setType(EasyRpcProto.TargetType.TARGET_ALL_CLIENTS)
            is RpcTarget.Tag -> {
                builder.setType(EasyRpcProto.TargetType.TARGET_TAG)
                builder.setTag(tag)
            }
        }
        return builder.build()
    }

    private fun EasyRpcProto.RpcTarget.toModel(): RpcTarget = when (type) {
        EasyRpcProto.TargetType.TARGET_SERVICE -> RpcTarget.Service
        EasyRpcProto.TargetType.TARGET_NODE -> RpcTarget.Node(nodeId)
        EasyRpcProto.TargetType.TARGET_ALL -> RpcTarget.All
        EasyRpcProto.TargetType.TARGET_ALL_CLIENTS -> RpcTarget.AllClients
        EasyRpcProto.TargetType.TARGET_TAG -> RpcTarget.Tag(tag)
        else -> RpcTarget.Service
    }

    private fun RpcClientInfo.toProto(): EasyRpcProto.RpcClientInfo {
        return EasyRpcProto.RpcClientInfo.newBuilder()
            .setNodeId(nodeId)
            .addAllTags(tags)
            .build()
    }

    private fun EasyRpcProto.RpcClientInfo.toModel(): RpcClientInfo {
        return RpcClientInfo(
            nodeId = nodeId,
            tags = tagsList.toSet(),
        )
    }
}
