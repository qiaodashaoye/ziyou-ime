package com.ziyou.ime.ai

import android.content.Context
import android.content.SharedPreferences

/**
 * 预置 AI 平台选项：名称 + OpenAI 兼容 chat/completions 完整端点 + 推荐模型名。
 * 设置弹窗以下拉菜单形式供用户直接选择，无需手动录入地址。
 */
data class AiApiProvider(
    val name: String,
    val apiUrl: String,
    val defaultModel: String
)

/**
 * AI 问答服务配置
 *
 * 通过 SharedPreferences 持久化 OpenAI 兼容接口的连接参数
 * （API 地址 / API Key / 模型名），在设置页维护，键盘 AI 面板消费。
 * 默认指向阿里云百炼（DashScope）OpenAI 兼容端点，任何 OpenAI 兼容服务
 * （DeepSeek、Kimi、通义等）均可在设置页替换。
 */
object AiConfig {

    private const val PREF_NAME = "ziyou_ai"
    private const val KEY_API_URL = "api_url"
    private const val KEY_API_KEY = "api_key"
    private const val KEY_MODEL = "model"

    /** 默认 API 端点（阿里云百炼 DashScope OpenAI 兼容 chat/completions 完整地址） */
    const val DEFAULT_API_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"

    /** 默认模型名（阿里百炼 Qwen） */
    const val DEFAULT_MODEL = "qwen3.7-max-2026-05-17"

    /** 默认 API Key（阿里百炼 DashScope 密钥，未在设置页覆盖时使用） */
    const val DEFAULT_API_KEY = "sk-b856c14981084e9790518a212d9c8a57"

    /** 国内主流 AI 平台预置列表（均为 OpenAI 兼容端点，可直接对接） */
    val PRESET_PROVIDERS: List<AiApiProvider> = listOf(
        AiApiProvider(
            name = "阿里通义千问（百炼）",
            apiUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
            defaultModel = "qwen-max"
        ),
        AiApiProvider(
            name = "DeepSeek",
            apiUrl = "https://api.deepseek.com/v1/chat/completions",
            defaultModel = "deepseek-chat"
        ),
        AiApiProvider(
            name = "硅基流动 SiliconFlow",
            apiUrl = "https://api.siliconflow.cn/v1/chat/completions",
            defaultModel = "deepseek-ai/DeepSeek-V3"
        ),
        AiApiProvider(
            name = "字节豆包（火山方舟）",
            apiUrl = "https://ark.cn-beijing.volces.com/api/v3/chat/completions",
            defaultModel = "doubao-1-5-pro-32k-250115"
        ),
        AiApiProvider(
            name = "智谱清言 GLM",
            apiUrl = "https://open.bigmodel.cn/api/paas/v4/chat/completions",
            defaultModel = "glm-4-flash"
        ),
        AiApiProvider(
            name = "Moonshot Kimi",
            apiUrl = "https://api.moonshot.cn/v1/chat/completions",
            defaultModel = "moonshot-v1-8k"
        ),
        AiApiProvider(
            name = "百度文心一言（千帆）",
            apiUrl = "https://qianfan.baidubce.com/v2/chat/completions",
            defaultModel = "ernie-4.0-8k-latest"
        ),
        AiApiProvider(
            name = "腾讯混元",
            apiUrl = "https://api.hunyuan.cloud.tencent.com/v1/chat/completions",
            defaultModel = "hunyuan-turbo"
        )
    )

    /** 根据已保存的 URL 匹配预置平台索引；无匹配返回 -1（对应「自定义」） */
    fun matchProviderIndex(apiUrl: String): Int =
        PRESET_PROVIDERS.indexOfFirst { it.apiUrl == apiUrl }

    fun getApiUrl(context: Context): String =
        getPreferences(context).getString(KEY_API_URL, DEFAULT_API_URL)
            ?.takeIf { it.isNotBlank() } ?: DEFAULT_API_URL

    fun getApiKey(context: Context): String =
        getPreferences(context).getString(KEY_API_KEY, DEFAULT_API_KEY)
            ?.takeIf { it.isNotBlank() } ?: DEFAULT_API_KEY

    fun getModel(context: Context): String =
        getPreferences(context).getString(KEY_MODEL, DEFAULT_MODEL)
            ?.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL

    /** 是否已完成配置（API Key 非空即视为已配置；内置默认密钥时恒为 true） */
    fun isConfigured(context: Context): Boolean = getApiKey(context).isNotBlank()

    fun save(context: Context, apiUrl: String, apiKey: String, model: String) {
        getPreferences(context).edit()
            .putString(KEY_API_URL, apiUrl.trim())
            .putString(KEY_API_KEY, apiKey.trim())
            .putString(KEY_MODEL, model.trim())
            .apply()
    }

    private fun getPreferences(context: Context): SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
}
