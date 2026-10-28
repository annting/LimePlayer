package app.myplayer.source.smb

import java.io.BufferedOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * 本机 HTTP 桥：把 SMB 文件通过 `http://127.0.0.1:<port>/<token>` 暴露给 ExoPlayer。
 *
 * ## 为什么必须有它
 * ExoPlayer 只认 http / file / content 等 scheme，**不能直接读 `smb://`**；
 * 而 `SmbRandomAccessFile` 支持 seek，正好能实现 HTTP Range —— 播放器拖进度条时
 * 只需要从新位置读，不用把前面的几百 MB 重读一遍。
 *
 * ## 协议范围
 * 只实现播放器真正用到的子集：`GET` / `HEAD` + 单段 `Range: bytes=a-b`。
 * 不支持多段 Range（ExoPlayer 不会发），不支持 keep-alive（每次响应 `Connection: close`）。
 *
 * ## 安全
 * 只绑 `127.0.0.1`，token 是随机 UUID，进程退出即失效；不对外网暴露。
 */
class SmbStreamServer(
    /** 相对路径 → 实际文件（大小 / MIME）；返回 null 时回 404。 */
    private val targetFactory: (path: String) -> StreamTarget?,
) {
    data class StreamTarget(
        val client: SmbClient,
        val path: String,
        val sizeBytes: Long,
        val mimeType: String,
    )

    private var serverSocket: ServerSocket? = null
    private val running = AtomicBoolean(false)
    /** 固定线程池：拖动进度条会不断断开/重连，按连接开线程会短时间堆出几十个线程。 */
    @Volatile
    private var workers: ExecutorService? = null
    private val tokens = ConcurrentHashMap<String, String>()   // token -> path（复用同一文件的 token）

    /** 监听端口；0 表示尚未启动。 */
    @Volatile
    var port: Int = 0
        private set

    /** 登记一个文件并返回它的本机播放地址（同一路径复用同一个 token）。 */
    fun urlFor(path: String): String {
        val token = tokens.entries.firstOrNull { it.value == path }?.key
            ?: java.util.UUID.randomUUID().toString().also { tokens[it] = path }
        return "http://127.0.0.1:$port/$token"
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        serverSocket = socket
        port = socket.localPort
        val pool = Executors.newFixedThreadPool(MAX_CONCURRENT_CONNECTIONS) { r ->
            Thread(r, "smb-stream-conn").apply { isDaemon = true }
        }
        workers = pool
        thread(name = "smb-stream-accept", isDaemon = true) {
            while (running.get()) {
                val client = try {
                    socket.accept()
                } catch (e: IOException) {
                    break                      // stop() 关掉 socket 时走到这里
                }
                // 池已满时 execute 进队列而非新建线程；stop() 后再到的连接直接关掉
                runCatching { pool.execute { handle(client) } }
                    .onFailure { runCatching { client.close() } }
            }
        }
    }

    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
        workers?.shutdownNow()
        workers = null
    }

    private fun handle(socket: Socket) {
        socket.use { s ->
            s.soTimeout = 20_000
            val input = s.getInputStream().bufferedReader()
            val output = BufferedOutputStream(s.getOutputStream())

            val requestLine = input.readLine() ?: return
            val parts = requestLine.split(' ')
            if (parts.size < 2) return
            val method = parts[0]
            val token = parts[1].trimStart('/').substringBefore('?')

            // 读完请求头（我们只关心 Range，其余丢弃）
            var rangeHeader: String? = null
            while (true) {
                val line = input.readLine() ?: break
                if (line.isEmpty()) break
                if (line.startsWith("Range:", ignoreCase = true)) rangeHeader = line.substringAfter(':').trim()
            }

            val target = tokens[token]?.let(targetFactory)
            if (target == null) {
                writeStatus(output, 404, "Not Found")
                return
            }
            if (method != "GET" && method != "HEAD") {
                writeStatus(output, 405, "Method Not Allowed")
                return
            }

            val total = target.sizeBytes.coerceAtLeast(0L)
            val range = parseRange(rangeHeader, total)
            val start = range?.first ?: 0L
            val endInclusive = range?.second ?: (total - 1).coerceAtLeast(0L)
            val length = if (total == 0L) 0L else (endInclusive - start + 1).coerceAtLeast(0L)

            val header = StringBuilder()
            header.append(if (range != null) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
            header.append("Content-Type: ${target.mimeType}\r\n")
            header.append("Accept-Ranges: bytes\r\n")
            header.append("Content-Length: $length\r\n")
            if (range != null) header.append("Content-Range: bytes $start-$endInclusive/$total\r\n")
            header.append("Connection: close\r\n\r\n")
            output.write(header.toString().toByteArray())
            output.flush()
            if (method == "HEAD" || length == 0L) return

            val file = runCatching { target.client.openRandom(target.path) }.getOrNull() ?: return
            try {
                file.seek(start)
                val buffer = ByteArray(64 * 1024)
                var remaining = length
                while (remaining > 0 && running.get()) {
                    val want = minOf(buffer.size.toLong(), remaining).toInt()
                    val read = file.read(buffer, 0, want)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                    remaining -= read
                }
                output.flush()
            } catch (_: IOException) {
                // 播放器拖动进度条会主动断开连接，属正常现象
            } finally {
                runCatching { file.close() }
            }
        }
    }

    private fun writeStatus(output: BufferedOutputStream, code: Int, text: String) {
        runCatching {
            output.write("HTTP/1.1 $code $text\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            output.flush()
        }
    }

    private companion object {
        /** 同时处理的连接数：单播放器 seek 时最多 2~3 路，留一点余量即可。 */
        const val MAX_CONCURRENT_CONNECTIONS = 4
    }
}

/**
 * 解析单段 Range：`bytes=0-`、`bytes=100-200`、`bytes=-500`（后缀）。
 * 解析失败 / 单位不对 / 越界时返回 null（按整文件 200 处理，符合 RFC 7233 的「可忽略」）。
 */
internal fun parseRange(header: String?, total: Long): Pair<Long, Long>? {
    if (header.isNullOrBlank() || total <= 0L) return null
    val spec = header.removePrefix("bytes=").trim()
    if (!spec.contains('-')) return null
    val (rawStart, rawEnd) = spec.split('-', limit = 2)
    return runCatching {
        if (rawStart.isBlank()) {
            // 后缀范围：最后 N 字节
            val suffix = rawEnd.toLong()
            if (suffix <= 0) return null
            val start = (total - suffix).coerceAtLeast(0)
            start to (total - 1)
        } else {
            val start = rawStart.toLong()
            if (start < 0 || start >= total) return null
            val end = if (rawEnd.isBlank()) total - 1 else rawEnd.toLong().coerceAtMost(total - 1)
            if (end < start) return null
            start to end
        }
    }.getOrNull()
}

/** 扩展名 → MIME（ExoPlayer 主要靠容器嗅探，这里只做兜底）。 */
internal fun mimeTypeOf(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
    "mp4", "m4v" -> "video/mp4"
    "mkv" -> "video/x-matroska"
    "webm" -> "video/webm"
    "avi" -> "video/x-msvideo"
    "mov" -> "video/quicktime"
    "ts", "m2ts" -> "video/mp2t"
    "mpg", "mpeg" -> "video/mpeg"
    "wmv" -> "video/x-ms-wmv"
    "flv" -> "video/x-flv"
    else -> "application/octet-stream"
}
