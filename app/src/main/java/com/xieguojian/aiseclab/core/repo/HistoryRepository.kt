package com.xieguojian.aiseclab.core.repo

import android.content.Context
import com.xieguojian.aiseclab.core.model.Report
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 历史报告本地存档：每份 Report 一个 JSON 文件，存应用私有目录 `filesDir/history/`。
 *
 * 安全约束：**apiKey 绝不落盘** —— Report 内嵌 ModelProfile，序列化前把 apiKey 抹成空串。
 * 历史记录只用于回看结果，重新发起测试总是走 EncryptedSharedPreferences 里的现行配置。
 */
class HistoryRepository(context: Context) {

    private val json = Json { ignoreUnknownKeys = true }
    private val dir = File(context.filesDir, "history")

    /** 存一份报告（按 timestamp 命名，同一份重复保存 = 覆盖，天然幂等）。 */
    fun save(report: Report) {
        if (!dir.exists()) dir.mkdirs()
        val sanitized = report.copy(modelProfile = report.modelProfile.copy(apiKey = ""))
        File(dir, "report_${report.timestamp}.json")
            .writeText(json.encodeToString(Report.serializer(), sanitized))
    }

    /** 全部历史报告，新 → 旧。单个文件损坏（手改/写一半被杀）只丢那一份，不炸列表。 */
    fun list(): List<Report> =
        dir.listFiles { f -> f.isFile && f.name.startsWith("report_") && f.name.endsWith(".json") }
            ?.mapNotNull { f ->
                runCatching { json.decodeFromString(Report.serializer(), f.readText()) }.getOrNull()
            }
            ?.sortedByDescending { it.timestamp }
            ?: emptyList()

    fun delete(timestamp: Long) {
        fileOf(timestamp).delete()
    }

    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun fileOf(timestamp: Long) = File(dir, "report_$timestamp.json")
}
