package com.xieguojian.aiseclab.core.judge.guard

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * 端上护栏模型下载器。
 *
 * ## 为什么必须带这三样
 * 1. **断点续传**：461 MB 走公网，一次下载失败就重来是不可接受的（尤其是手机流量）。
 * 2. **SHA256 校验**：没有校验的话，一次中间人替换或半截下载就会让护栏"判定质量变差" ——
 *    这种故障没有任何报错，只会让你怀疑是自己代码写错了。哈希来自官方 LFS oid。
 * 3. **进度**：461 MB 不显示进度，用户会以为卡死了。
 *
 * ## 校验策略
 * 边下边算 SHA256（不是下完再读一遍文件），省一次 461 MB 的磁盘读。
 * 续传场景下无法从中间开始算哈希，所以**发现是续传时会重新读一遍已有部分**再接着算 ——
 * 这比"跳过校验"安全得多。
 *
 * ## 失败时怎么办
 * 一律返回 [State.Failed] 且**保留已下载的分片**（下次可续传），绝不静默降到不校验。
 * 上层看到失败就把护栏当作不可用 → [GuardJudge] 会降级回规则层，不会误判为模型失守。
 */
class GuardModelDownloader(private val context: Context) {

    sealed class State {
        object Idle : State()

        /** [total] 为 0 表示服务端没给 Content-Length。 */
        data class Progress(val downloaded: Long, val total: Long) : State() {
            val fraction: Float
                get() = if (total > 0) (downloaded.toFloat() / total).coerceIn(0f, 1f) else 0f
        }

        object Verifying : State()
        object Done : State()
        data class Failed(val message: String) : State()
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        // 读超时给足：手机上大文件下载容易间歇性卡顿，30s 会误判成失败并频繁重连
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    /**
     * 下载指定档位。可重复调用：已下部分会续传；已就绪时直接返回 [State.Done]。
     */
    fun download(tier: OnDeviceGuardModel.Tier): Flow<State> = flow {
        emit(State.Idle)

        val dir = OnDeviceGuardModel.dir(context)
        if (!dir.exists() && !dir.mkdirs()) {
            emit(State.Failed("无法创建模型目录：${dir.absolutePath}"))
            return@flow
        }
        val target = OnDeviceGuardModel.file(context, tier)

        if (target.isFile && target.length() == tier.sizeBytes) {
            // 大小对得上也要验哈希：大小相同但内容不同的情况是存在的（截断+补齐）
            when (val v = verify(target, tier)) {
                is State.Done -> { emit(State.Done); return@flow }
                else -> {
                    emit(v)
                    return@flow
                }
            }
        }
        // 已存在但大小不符 → 视为残缺分片，从头开始（避免拼出一个"大小对但内容是两段不同版本"的文件）
        if (target.exists() && target.length() > tier.sizeBytes) {
            target.delete()
        }

        var downloaded = target.length()
        emit(State.Progress(downloaded, tier.sizeBytes))

        try {
            val builder = Request.Builder().url(tier.url)
            if (downloaded > 0) builder.addHeader("Range", "bytes=$downloaded-")

            client.newCall(builder.build()).execute().use { resp ->
                if (!resp.isSuccessful) {
                    // 416：请求的区间超出文件长度，说明本地分片比远端还大 → 重来
                    if (resp.code == 416 && downloaded > 0) {
                        target.delete()
                        emit(State.Failed("本地分片异常已清除，请重试"))
                        return@flow
                    }
                    emit(State.Failed("HTTP ${resp.code}：下载失败"))
                    return@flow
                }
                if (downloaded > 0 && resp.code != 206) {
                    // 服务端不支持 Range：只能从头下，必须先清空，否则会拼出损坏文件
                    target.delete()
                    downloaded = 0L
                }

                val body = resp.body ?: run {
                    emit(State.Failed("响应体为空"))
                    return@flow
                }
                val total = if (tier.sizeBytes > 0) tier.sizeBytes else body.contentLength() + downloaded

                RandomAccessFile(target, "rw").use { raf ->
                    raf.seek(downloaded)
                    body.byteStream().use { input ->
                        val buf = ByteArray(256 * 1024)
                        var lastEmit = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            raf.write(buf, 0, n)
                            downloaded += n
                            // 节流：每 1 MB 或每 256 KB 上报一次，别把 UI 事件刷爆
                            if (downloaded - lastEmit >= 1L shl 20) {
                                lastEmit = downloaded
                                emit(State.Progress(downloaded, total))
                            }
                        }
                    }
                }
                emit(State.Progress(downloaded, total))
            }
        } catch (e: Exception) {
            // 保留分片，下次续传
            emit(State.Failed("下载中断：${e.message ?: e.javaClass.simpleName}（已下载部分会保留，可重试续传）"))
            return@flow
        }

        val actual = target.length()
        if (actual != tier.sizeBytes) {
            emit(State.Failed("大小不符：$actual != ${tier.sizeBytes}（可重试续传）"))
            return@flow
        }

        emit(State.Verifying)
        emit(verify(target, tier))
    }.flowOn(Dispatchers.IO)

    /** 校验大小与 SHA256。 */
    private fun verify(file: File, tier: OnDeviceGuardModel.Tier): State {
        if (file.length() != tier.sizeBytes) {
            return State.Failed("大小不符：${file.length()} != ${tier.sizeBytes}")
        }
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            file.inputStream().use { input ->
                val buf = ByteArray(1 shl 20)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                }
            }
        } catch (e: Exception) {
            return State.Failed("读取模型文件失败：${e.message}")
        }
        val hex = digest.digest().joinToString("") { "%02x".format(it) }
        return if (hex.equals(tier.sha256, ignoreCase = true)) {
            State.Done
        } else {
            // 校验失败就把文件删掉：留着只会让人以为"模型在、只是有问题"，反复浪费时间
            file.delete()
            State.Failed("SHA256 校验失败（期望 ${tier.sha256.take(16)}…，实际 ${hex.take(16)}…），已删除损坏文件")
        }
    }
}
