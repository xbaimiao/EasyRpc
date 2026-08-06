package com.xbaimiao.easyrpc.plugin

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import java.util.Collections
import java.util.concurrent.CompletableFuture

/**
 * 在本服控制台执行命令，并把命令产生的输出收集回来。
 *
 * Bukkit 的命令分发不是线程安全的，必须回主线程执行，所以这里返回 future。
 *
 * 输出捕获使用 Paper 提供的反馈转发 sender。Paper 的命令分发器会保留这个专用 sender，
 * 并把 Bukkit 命令和原版命令发送给执行者的消息统一交给回调。
 */
object ConsoleCommandRunner {
    /** 单条命令的输出上限，避免某个命令刷屏把 RPC payload 撑爆。 */
    private const val MAX_OUTPUT_LINES = 200

    /**
     * 执行一条命令。
     *
     * @param command 不带前导斜杠的命令
     * @param captureOutput 是否使用 Paper 的反馈转发 sender 捕获输出；关闭后直接使用真实控制台。
     */
    fun run(
        plugin: EasyRpcClientPlugin,
        command: String,
        captureOutput: Boolean,
    ): CompletableFuture<String> {
        val future = CompletableFuture<String>()
        val task = Runnable {
            runCatching { dispatch(command, captureOutput) }
                .onSuccess { future.complete(it) }
                .onFailure { future.complete("命令执行异常: ${it::class.java.simpleName}: ${it.message}") }
        }
        if (Bukkit.isPrimaryThread()) {
            task.run()
        } else {
            Bukkit.getScheduler().runTask(plugin, task)
        }
        return future
    }

    private fun dispatch(command: String, captureOutput: Boolean): String {
        val console = Bukkit.getConsoleSender()
        if (!captureOutput) {
            val ok = Bukkit.dispatchCommand(console, command)
            return if (ok) "已执行（未开启输出捕获）" else "命令未找到或执行失败: $command"
        }

        val collected = Collections.synchronizedList(mutableListOf<String>())
        // 这个 sender 是 Paper 命令分发器原生支持的反馈通道，权限与真实控制台一致。
        val sender = Bukkit.createCommandSender { message -> captureComponent(message, collected) }
        val ok = Bukkit.dispatchCommand(sender, command)
        return formatResult(command, ok, collected.toList())
    }

    /** 格式化单个节点的命令结果，并限制回传行数。 */
    private fun formatResult(command: String, ok: Boolean, lines: List<String>): String {
        val body = when {
            lines.isEmpty() -> "（无输出）"
            lines.size > MAX_OUTPUT_LINES ->
                lines.take(MAX_OUTPUT_LINES).joinToString("\n") + "\n（输出过长，已截断 ${lines.size - MAX_OUTPUT_LINES} 行）"
            else -> lines.joinToString("\n")
        }
        return if (ok) body else "命令未找到或执行失败: $command\n$body"
    }

    /** 将 Adventure 消息转成 RPC 返回使用的纯文本，并按真实换行拆分。 */
    private fun captureComponent(message: Component, sink: MutableList<String>) {
        val text = PlainTextComponentSerializer.plainText().serialize(message)
        sink.addAll(text.lineSequence().toList())
    }
}
