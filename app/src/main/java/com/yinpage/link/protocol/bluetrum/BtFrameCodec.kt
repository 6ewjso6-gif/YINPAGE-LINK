package com.yinpage.link.protocol.bluetrum

import com.yinpage.link.protocol.Bytes

/**
 * ============================================================================
 *  中科蓝讯 AB 系 SPP 帧编解码器（已确证格式）
 * ============================================================================
 *  帧结构（发送方向与接收方向逐指令对齐确证）：
 *
 *   偏移  字段     说明
 *   ----  -------  --------------------------------------------------
 *    [0]  seq      bits0-3 发送序号 0..15，**每个物理帧**发送后 (x+1) and 0x0F
 *    [1]  command  命令码（见 [BtCommand]）
 *    [2]  type     1=请求 2=响应 3=通知
 *    [3]  chunk    高 4 位 = (总包数 - 1)，低 4 位 = 当前包序号
 *    [4]  len      本包 payload 长度（0..255）
 *   [5..] payload  载荷
 *
 *  - 整帧长度 = 5 + len
 *  - **无 CRC / 无校验 / 无转义**
 *  - 大数据分包：框架默认 15 字节（SDK 类 `e.a` 初值），但官方连上后
 *    先 `setMaxPacketSize(255)` → payload 250，再由 `INFO_MAX_PACKET_SIZE(0xFF)`
 *    响应回填为 `值 - 5`；本项目由 [BluetrumCodec] 传入 250 并在握手后回填
 *  - 官方 SDK 在 `DeviceCommManager.a()` 的分包循环里对**每个物理帧**都执行
 *    `seq = (seq + 1) & 0x0F`（一条消息拆 N 帧时 seq 各不相同），接收端
 *    `f.b()` 逐帧校验 `Expected seq`，所以这里必须按帧递增，不能按消息递增。
 * ============================================================================
 */
class BtFrameCodec(
    /** 每包 payload 上限；默认 15 字节（SDK 初值）。 */
    var maxChunkSize: Int = DEFAULT_CHUNK_SIZE,
) {

    /** 发送序号，0..15 循环。 */
    private var seq: Int = 0

    /** 解码得到的一帧。 */
    data class Frame(
        val seq: Int,
        val command: Int,
        val type: Int,
        /** 总包数（由 chunk 高 4 位 + 1 推出） */
        val totalChunks: Int,
        /** 当前包序号（0 起） */
        val chunkIndex: Int,
        val payload: Bytes,
    ) {
        val isRequest: Boolean get() = type == BtCommand.TYPE_REQUEST
        val isResponse: Boolean get() = type == BtCommand.TYPE_RESPONSE
        val isNotify: Boolean get() = type == BtCommand.TYPE_NOTIFY
    }

    // ------------------------------------------------------------------ 编码

    /**
     * 把一条逻辑消息编码为若干物理帧（长度超过 maxChunkSize 时自动分包）。
     * 分包时**每个物理帧各自递增序号**（与官方 SDK `DeviceCommManager.a()` 一致）。
     */
    fun encode(command: Int, type: Int, payload: Bytes): List<Bytes> {
        val safePayload = if (payload.isEmpty()) IntArray(0) else payload
        // chunk 高 4 位最多表示 16 包；若按配置分包会超过 16 包，则自动放大每包大小，
        // 保证 totalChunks ≤ 16（帧同步正确性优先于"包更小"）。
        val configured = maxChunkSize.coerceIn(1, 255)
        val minChunkForFit = if (safePayload.isEmpty()) 1
        else ceilDiv(safePayload.size, MAX_TOTAL_CHUNKS)
        val chunkSize = maxOf(configured, minChunkForFit).coerceIn(1, 255)
        val totalChunks = if (safePayload.isEmpty()) 1
        else ceilDiv(safePayload.size, chunkSize)

        val frames = ArrayList<Bytes>(totalChunks)
        for (i in 0 until totalChunks) {
            val start = i * chunkSize
            val end = minOf(start + chunkSize, safePayload.size)
            val slice = if (end > start) safePayload.copyOfRange(start, end) else IntArray(0)
            // 每个物理帧发完即推进序号（官方 SDK 分包循环内同样逐帧递增）
            val frameSeq = seq
            seq = (seq + 1) and 0x0F
            frames.add(buildFrame(frameSeq, command, type, totalChunks, i, slice))
        }
        return frames
    }

    /** 构造单帧（不做分包）。 */
    fun buildFrame(
        seq: Int,
        command: Int,
        type: Int,
        totalChunks: Int,
        chunkIndex: Int,
        payload: Bytes,
    ): Bytes {
        val len = payload.size.coerceAtMost(255)
        val out = IntArray(5 + len)
        out[0] = seq and 0x0F
        out[1] = command and 0xFF
        out[2] = type and 0xFF
        // 高 4 位 = 总包数-1；低 4 位 = 当前包序号
        out[3] = ((((totalChunks - 1) and 0x0F) shl 4) or (chunkIndex and 0x0F)) and 0xFF
        out[4] = len and 0xFF
        for (i in 0 until len) out[5 + i] = payload[i] and 0xFF
        return out
    }

    /** 重置发送序号（重连后必须调用）。 */
    fun resetSequence() {
        seq = 0
    }

    val currentSequence: Int get() = seq

    // ------------------------------------------------------------------ 解码

    /** 分片重组缓冲：key = command，value = 正在重组的一整条多包消息。 */
    private val partial = HashMap<Int, PartialMessage>()

    /**
     * 正在重组的消息。
     *
     * 注意：由于 seq 是**按物理帧**递增的，分片之间 seq 必然不同，
     * 因此不能再用 seq 判断“是不是同一条消息”。改用「总包数 + 下一包序号」判定：
     * 首包（chunkIndex=0）或序号不连续，都视为新消息，丢弃旧分片重新开始。
     */
    private class PartialMessage(
        val totalChunks: Int,
        val parts: MutableList<Bytes>,
        val received: BooleanArray,
        /** 期望的下一个分片序号。 */
        var nextIndex: Int,
        /** 最近一次收到本消息分片的时刻，用于老化清理。 */
        var lastTouchedMs: Long,
    )

    private var buffer = IntArray(0)

    /**
     * 流式解码：喂入任意长度字节，产出所有已完整重组的逻辑消息。
     * 返回的 Frame 里 payload 是**重组后的完整载荷**。
     */
    fun decodeChunk(chunk: Bytes): List<Frame> {
        if (chunk.isEmpty()) return emptyList()
        buffer = buffer + chunk
        val done = ArrayList<Frame>()

        while (true) {
            if (buffer.size < 5) break
            val len = buffer[4] and 0xFF
            val total = 5 + len
            if (buffer.size < total) break

            val frame = buffer.copyOfRange(0, total)
            buffer = buffer.copyOfRange(total, buffer.size)

            val f = parseSingle(frame) ?: continue
            val reassembled = reassemble(f)
            if (reassembled != null) done.add(reassembled)
        }

        // 防御：异常数据导致缓冲无限增长。
        // 本协议无帧同步标记，若保留尾部（旧写法）会在帧中间截断 → 之后全部错位；
        // 溢出说明数据已不可信，直接丢弃整段缓冲并清空重组状态，等待重新对齐。
        if (buffer.size > MAX_BUFFER) {
            buffer = IntArray(0)
            partial.clear()
        }
        return done
    }

    private fun parseSingle(frame: Bytes): Frame? {
        if (frame.size < 5) return null
        val len = frame[4] and 0xFF
        if (frame.size < 5 + len) return null
        val chunkByte = frame[3] and 0xFF
        val payload = if (len > 0) frame.copyOfRange(5, 5 + len) else IntArray(0)
        return Frame(
            seq = frame[0] and 0x0F,
            command = frame[1] and 0xFF,
            type = frame[2] and 0xFF,
            totalChunks = ((chunkByte shr 4) and 0x0F) + 1,
            chunkIndex = chunkByte and 0x0F,
            payload = payload,
        )
    }

    /**
     * 分片重组。单包消息直接返回；
     * 多包消息按 command 累积，收齐后拼接并回调。
     *
     * 防御残留污染：一旦判定这是**一条新消息**（首包，或序号/总包数与正在
     * 重组的消息不一致——说明旧消息丢包了），就丢弃旧分片重新开始。
     */
    private fun reassemble(frame: Frame): Frame? {
        if (frame.totalChunks <= 1) return frame
        if (frame.chunkIndex >= frame.totalChunks) return null

        val now = System.currentTimeMillis()
        evictStalePartials(now)

        val existing = partial[frame.command]
        val isNewMessage = existing == null ||
            existing.totalChunks != frame.totalChunks ||
            frame.chunkIndex == 0 ||
            frame.chunkIndex != existing.nextIndex
        if (isNewMessage) {
            // 新消息开始：丢弃同 command 的残留片段，避免旧消息污染新消息
            if (existing != null) partial.remove(frame.command)
            partial[frame.command] = PartialMessage(
                totalChunks = frame.totalChunks,
                parts = MutableList(frame.totalChunks) { IntArray(0) },
                received = BooleanArray(frame.totalChunks),
                nextIndex = 1,
                lastTouchedMs = now,
            )
            // 防御：异常数据导致分片表无限膨胀
            if (partial.size > MAX_PARTIAL_COMMANDS) partial.clear()
        }

        val msg = partial.getValue(frame.command)
        msg.lastTouchedMs = now
        msg.parts[frame.chunkIndex] = frame.payload
        msg.received[frame.chunkIndex] = true
        msg.nextIndex = frame.chunkIndex + 1

        val complete = msg.received.all { it }
        if (!complete) return null

        var merged = IntArray(0)
        for (i in 0 until msg.totalChunks) merged = merged + msg.parts[i]
        partial.remove(frame.command)
        return frame.copy(chunkIndex = 0, totalChunks = 1, payload = merged)
    }

    /** 老化清理：丢弃超时仍未收齐的分片，避免永久占用内存并污染后续同 command 消息。 */
    private fun evictStalePartials(now: Long) {
        if (partial.isEmpty()) return
        val it = partial.entries.iterator()
        while (it.hasNext()) {
            if (now - it.next().value.lastTouchedMs > PARTIAL_TTL_MS) it.remove()
        }
    }

    fun reset() {
        buffer = IntArray(0)
        partial.clear()
        seq = 0
    }

    /** 缓冲中未解析字节数（调试用）。 */
    val bufferedBytes: Int get() = buffer.size

    companion object {
        /** 框架默认分包大小（SDK 类 `e.a` 初值）；实际使用值由调用方传入 250。 */
        const val DEFAULT_CHUNK_SIZE = 15

        /** 帧头固定长度。 */
        const val HEADER_SIZE = 5

        /** chunk 高 4 位能表示的最大总包数。 */
        private const val MAX_TOTAL_CHUNKS = 16

        private const val MAX_BUFFER = 4096

        /** 同时在途的多包消息上限（防御异常输入）。 */
        private const val MAX_PARTIAL_COMMANDS = 16

        /** 未收齐的分片保留时长，超时即老化清理。 */
        private const val PARTIAL_TTL_MS = 5_000L

        private fun ceilDiv(a: Int, b: Int): Int = (a + b - 1) / b
    }
}
