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
 *    [0]  seq      bits0-3 发送序号 0..15，发送后 (x+1) and 0x0F，初值 0
 *    [1]  command  命令码（见 [BtCommand]）
 *    [2]  type     1=请求 2=响应 3=通知
 *    [3]  chunk    高 4 位 = (总包数 - 1)，低 4 位 = 当前包序号
 *    [4]  len      本包 payload 长度（0..255）
 *   [5..] payload  载荷
 *
 *  - 整帧长度 = 5 + len
 *  - **无 CRC / 无校验 / 无转义**
 *  - 大数据分包：默认每包 payload 15 字节（类 `e.a` 初值），
 *    实际值可由 `INFO_MAX_PACKET_SIZE(0xFF)` 查询覆盖
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
     * 分包时所有帧共用同一序号（SDK 行为：序号按"消息"递增而非按帧）。
     */
    fun encode(command: Int, type: Int, payload: Bytes): List<Bytes> {
        val safePayload = if (payload.isEmpty()) IntArray(0) else payload
        val chunkSize = maxChunkSize.coerceIn(1, 255)
        val totalChunks = if (safePayload.isEmpty()) 1
        else ((safePayload.size + chunkSize - 1) / chunkSize)

        val currentSeq = seq
        // 序号按消息推进
        seq = (seq + 1) and 0x0F

        val frames = ArrayList<Bytes>(totalChunks)
        for (i in 0 until totalChunks) {
            val start = i * chunkSize
            val end = minOf(start + chunkSize, safePayload.size)
            val slice = if (end > start) safePayload.copyOfRange(start, end) else IntArray(0)
            frames.add(buildFrame(currentSeq, command, type, totalChunks, i, slice))
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

    /** 分片重组缓冲：key = command，value = 已收到的 payload 片段。 */
    private val partial = HashMap<Int, MutableList<Bytes>>()
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

        // 防御：异常数据导致缓冲无限增长
        if (buffer.size > MAX_BUFFER) {
            buffer = buffer.copyOfRange(buffer.size - MAX_BUFFER, buffer.size)
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
     */
    private fun reassemble(frame: Frame): Frame? {
        if (frame.totalChunks <= 1) return frame
        if (frame.chunkIndex >= frame.totalChunks) return null

        val parts = partial.getOrPut(frame.command) { MutableList(frame.totalChunks) { IntArray(0) } }
        // 容量不足时扩容（防御异常 totalChunks）
        while (parts.size < frame.totalChunks) parts.add(IntArray(0))
        parts[frame.chunkIndex] = frame.payload

        val complete = (0 until frame.totalChunks).all { parts[it].isNotEmpty() } ||
            (frame.totalChunks == 1)
        if (!complete) return null

        var merged = IntArray(0)
        for (i in 0 until frame.totalChunks) merged = merged + parts[i]
        partial.remove(frame.command)
        return frame.copy(chunkIndex = 0, totalChunks = 1, payload = merged)
    }

    fun reset() {
        buffer = IntArray(0)
        partial.clear()
        seq = 0
    }

    /** 缓冲中未解析字节数（调试用）。 */
    val bufferedBytes: Int get() = buffer.size

    companion object {
        /** SDK 默认分包大小（类 `e.a` 初值）。 */
        const val DEFAULT_CHUNK_SIZE = 15

        /** 帧头固定长度。 */
        const val HEADER_SIZE = 5

        private const val MAX_BUFFER = 4096
    }
}
