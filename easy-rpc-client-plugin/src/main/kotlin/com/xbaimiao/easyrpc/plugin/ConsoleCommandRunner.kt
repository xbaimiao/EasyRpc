package com.xbaimiao.easyrpc.plugin

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import net.md_5.bungee.api.chat.BaseComponent
import org.bukkit.Bukkit
import org.bukkit.command.ConsoleCommandSender
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.concurrent.CompletableFuture

/**
 * 在本服控制台执行命令，并把命令产生的输出收集回来。
 *
 * Bukkit 的命令分发不是线程安全的，必须回主线程执行，所以这里返回 future。
 *
 * 输出捕获用动态代理包一层 [ConsoleCommandSender]：拦下 sendMessage 收集文本，
 * 其它方法全部转发给真正的 console sender。这样不依赖 NMS，跨版本也能用。
 */
object ConsoleCommandRunner {
    /** 单条命令的输出上限，避免某个命令刷屏把 RPC payload 撑爆。 */
    private const val MAX_OUTPUT_LINES = 200

    /**
     * 执行一条命令。
     *
     * @param command 不带前导斜杠的命令
     * @param captureOutput 是否用代理捕获输出。关掉就直接用真实 console sender，
     *        兼容性最好但拿不到回显。
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
        val proxy = newCapturingSender(console, collected)
        // 代理可能被某些插件强转成实现类而抛错，这时退回真实 sender 重试一次，
        // 保证命令至少被执行到，只是拿不到回显。
        val ok = runCatching { Bukkit.dispatchCommand(proxy, command) }.getOrElse {
            collected.clear()
            collected += "（输出捕获失败，已用原始 console 重试: ${it::class.java.simpleName}）"
            Bukkit.dispatchCommand(console, command)
        }

        val lines = collected.toList()
        val body = when {
            lines.isEmpty() -> "（无输出）"
            lines.size > MAX_OUTPUT_LINES ->
                lines.take(MAX_OUTPUT_LINES).joinToString("\n") + "\n（输出过长，已截断 ${lines.size - MAX_OUTPUT_LINES} 行）"
            else -> lines.joinToString("\n")
        }
        return if (ok) body else "命令未找到或执行失败: $command\n$body"
    }

    /** 造一个转发所有调用、但把 sendMessage 内容抄下来的 ConsoleCommandSender 代理。 */
    private fun newCapturingSender(
        delegate: ConsoleCommandSender,
        sink: MutableList<String>,
    ): ConsoleCommandSender {
        val handler = InvocationHandler { _, method: Method, args: Array<Any?>? ->
            if (method.name == "sendMessage") {
                // 重载形态很多：sendMessage(String)、(String[])、(Component)、
                // (BaseComponent)、(UUID, String) 等。UUID 那类首参不是内容，要跳过。
                args?.filterNot { it is java.util.UUID }?.forEach { sink.addAll(flatten(it)) }
                return@InvocationHandler null
            }
            // spigot() 返回的对象上也有 sendMessage，但那条路很少用，这里不额外代理，
            // 直接转发即可，最坏情况是那部分输出抓不到。
            method.invoke(delegate, *(args ?: emptyArray()))
        }
        return Proxy.newProxyInstance(
            ConsoleCommandSender::class.java.classLoader,
            arrayOf(ConsoleCommandSender::class.java),
            handler,
        ) as ConsoleCommandSender
    }

    /** 把各种 sendMessage 入参统一拍平成文本行。 */
    private fun flatten(value: Any?): List<String> = when (value) {
        null -> emptyList()
        is String -> listOf(value)
        is Array<*> -> value.flatMap { flatten(it) }
        is Iterable<*> -> value.flatMap { flatten(it) }
        is BaseComponent -> listOf(value.toLegacyText())
        is Component -> listOf(PlainTextComponentSerializer.plainText().serialize(value))
        else -> listOf(value.toString())
    }
}
