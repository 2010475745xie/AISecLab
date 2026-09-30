package com.xieguojian.aiseclab.core.repo

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.xieguojian.aiseclab.core.model.AuxModelProfile
import com.xieguojian.aiseclab.core.model.GuardProfile
import com.xieguojian.aiseclab.core.model.ModelProfile
import com.xieguojian.aiseclab.core.model.RoleSource
import kotlinx.serialization.json.Json

/**
 * 密钥本地加密存储：MasterKey(AES256_GCM) + EncryptedSharedPreferences(AES256_SIV 键 / AES256_GCM 值)。
 * 绝不落明文 SharedPreferences，不打日志。Key 只在这里和内存中短暂存在。
 */
class ProfileRepository(context: Context) {

    private val json = Json { ignoreUnknownKeys = true }

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "aiseclab_profiles",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun saveTarget(profile: ModelProfile) {
        prefs.edit().putString(KEY_TARGET, json.encodeToString(ModelProfile.serializer(), profile)).apply()
    }

    fun loadTarget(): ModelProfile? =
        prefs.getString(KEY_TARGET, null)?.let {
            runCatching { json.decodeFromString(ModelProfile.serializer(), it) }.getOrNull()
        }

    fun saveAux(aux: AuxModelProfile?) {
        if (aux == null) {
            prefs.edit().remove(KEY_AUX).apply()
        } else {
            prefs.edit().putString(KEY_AUX, json.encodeToString(AuxModelProfile.serializer(), aux)).apply()
        }
    }

    fun loadAux(): AuxModelProfile? =
        prefs.getString(KEY_AUX, null)?.let {
            runCatching { json.decodeFromString(AuxModelProfile.serializer(), it) }.getOrNull()
        }

    fun saveGuard(guard: GuardProfile?) {
        if (guard == null) {
            prefs.edit().remove(KEY_GUARD).apply()
        } else {
            prefs.edit().putString(KEY_GUARD, json.encodeToString(GuardProfile.serializer(), guard)).apply()
        }
    }

    fun loadGuard(): GuardProfile? =
        prefs.getString(KEY_GUARD, null)?.let {
            runCatching { json.decodeFromString(GuardProfile.serializer(), it) }.getOrNull()
        }

    fun saveRoleSource(src: RoleSource) {
        prefs.edit().putString(KEY_ROLE_SOURCE, src.name).apply()
    }

    fun loadRoleSource(): RoleSource =
        prefs.getString(KEY_ROLE_SOURCE, null)
            ?.let { runCatching { RoleSource.valueOf(it) }.getOrNull() } ?: RoleSource.AUTO

    /**
     * 测试后端地址（可选）。
     *
     * 空 = 在本机跑（默认）；非空 = 把整轮测试委托给该后端（见 [com.xieguojian.aiseclab.core.api.BackendClient]）。
     * 地址不写死、由用户填：比赛/共享服务器场景只需要在设置里粘贴即可，代码零改动。
     * 仅存地址，不存被测模型 Key —— 被测 Key 始终随请求体发往后端，仅后端任务周期内存留存。
     */
    fun saveBackendUrl(url: String) {
        prefs.edit().putString(KEY_BACKEND_URL, url.trim()).apply()
    }

    fun loadBackendUrl(): String =
        prefs.getString(KEY_BACKEND_URL, null)?.trim() ?: ""

    /**
     * 后端访问令牌（可选）。仅当后端设置了 API_TOKEN 时才需要填。
     * 与模型 Key 一样走 EncryptedSharedPreferences，不落明文。
     */
    fun saveBackendToken(token: String) {
        prefs.edit().putString(KEY_BACKEND_TOKEN, token.trim()).apply()
    }

    fun loadBackendToken(): String =
        prefs.getString(KEY_BACKEND_TOKEN, null)?.trim() ?: ""

    /**
     * 「本机运行测试」总开关。**默认关闭**。
     *
     * 关闭时：没填后端地址就不允许开测 —— 端上跑没有多次采样/稳定性保护（后端才有
     * SAMPLES_PER_CASE 多数裁决），单次采样定论的结果方差无人兜底，所以默认引导走后端。
     * 开启时：恢复旧行为（后端地址留空 = 端上直接跑）。
     */
    fun saveLocalRunEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_LOCAL_RUN, enabled).apply()
    }

    fun loadLocalRunEnabled(): Boolean =
        prefs.getBoolean(KEY_LOCAL_RUN, false)   // 默认关闭

    /**
     * 首次启动引导页标记。
     *
     * 仅存一个布尔：第一次走完引导（[finishOnboarding]）后置 true，
     * 之后 `MainActivity` 直接进首页而非引导页。重置 App 数据会随 EncryptedSharedPreferences 一起清空 → 重新引导。
     */
    fun saveOnboarded() {
        prefs.edit().putBoolean(KEY_ONBOARDED, true).apply()
    }

    fun loadOnboarded(): Boolean =
        prefs.getBoolean(KEY_ONBOARDED, false)

    companion object {
        private const val KEY_TARGET = "target_profile"
        private const val KEY_AUX = "aux_profile"
        private const val KEY_GUARD = "guard_profile"
        private const val KEY_ROLE_SOURCE = "role_source"
        private const val KEY_BACKEND_URL = "backend_url"
        private const val KEY_BACKEND_TOKEN = "backend_token"
        private const val KEY_LOCAL_RUN = "local_run_enabled"
        private const val KEY_ONBOARDED = "onboarded"
    }
}
