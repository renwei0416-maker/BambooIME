package com.osfans.trime.actions.bamboo

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import com.osfans.trime.TrimeApplication
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber

/**
 * 一个 AI 模式：快捷码 + 模式提示 + 提示词。
 *
 * @param code 快捷码，如 `eg;`
 * @param name 模式提示（界面显示名），如「英文翻译」，用户可改
 * @param prompt 发给模型的 system 提示词
 * @param key 内置键（`default`/`eng`/…），只用于认内置模式；用户新建的模式为 null
 */
@Serializable
data class AiMode(
    val code: String,
    val name: String,
    val prompt: String,
    val key: String? = null,
)

/**
 * 苍竹自己的设置。刻意不塞进 AppPrefs（那套是 rime 侧配置的镜像），
 * 用独立的 SharedPreferences 文件，改完立即生效、不需要部署。
 */
object BambooPrefs {
    private const val FILE = "bamboo_actions"

    private const val K_ENABLED = "enabled"
    private const val K_REVERT_ENABLED = "revert_enabled"
    private const val K_REVERT_TRIGGERS = "revert_triggers"
    private const val K_REVERT_MAX = "revert_max_code_points"

    private const val K_AI_ENABLED = "ai_enabled"
    private const val K_AI_MODES = "ai_modes"
    private const val K_AI_SEND_KEY = "ai_send_key"
    private const val K_AI_TRIGGER_MODE = "ai_trigger_mode"
    private const val K_AI_COMBO = "ai_combo"
    private const val K_CLIP_CODE_ENABLED = "clip_code_enabled"
    private const val K_CLIP_COMBO_ENABLED = "clip_combo_enabled"
    private const val K_FALLBACK_DEFAULT = "combo_fallback_default"
    private const val K_CLIP_COMBO = "clip_combo"
    private const val K_AI_DOUBLE_MS = "ai_double_ms"
    private const val K_AI_BASE_URL = "ai_base_url"

    private const val K_AI_MODEL = "ai_model"
    private const val K_AI_KEY = "ai_api_key"
    private const val K_AI_TEMP = "ai_temperature"
    private const val K_AI_MAX_TOKENS = "ai_max_tokens"
    private const val K_AI_TIMEOUT_MS = "ai_timeout_ms"
    private const val K_AI_SEND_AFTER = "ai_send_after"
    private const val K_AI_CLIP_CODE = "ai_clip_code"
    private const val K_AI_EXIT_CODE = "ai_exit_code"
    private const val K_AI_SOURCE = "ai_source"
    private const val K_AI_DISCARD_COMPOSITION = "ai_discard_composition"
    private const val K_AI_SENTENCE_ONLY = "ai_sentence_only"
    private const val K_AI_SENTENCE_MARKS = "ai_sentence_marks"
    private const val K_AI_SEND_DOUBLE = "ai_send_double"
    private const val K_AI_SEND_COMBO = "ai_send_combo"
    private const val K_LOG_FILE = "log_to_file"

    /** AI 接口地址默认值（DeepSeek 官方兼容端点）。 */
    const val DEFAULT_AI_BASE_URL = "https://api.deepseek.com/v1/chat/completions"

    /** AI 模型名默认值。 */
    const val DEFAULT_AI_MODEL = "deepseek-flash"

    const val DEFAULT_REVERT_TRIGGERS = "qq/"
    const val DEFAULT_MAX_DELETE = 500

    /**
     * 老版本的「发送方式」二选一。1.1.0 起拆成两个独立开关
     * （[K_AI_SEND_DOUBLE] / [K_AI_SEND_COMBO]），这两个常量只用于迁移旧存档。
     */
    const val TRIGGER_DOUBLE = "double"
    const val TRIGGER_COMBO = "combo"

    const val DEFAULT_AI_COMBO = "ctrl+;"
    const val DEFAULT_CLIP_COMBO = "ctrl+alt+;"

    const val SOURCE_AUTO = "auto"
    const val SOURCE_COMPOSITION = "composition"
    const val SOURCE_COMMITTED = "committed"

    /** 非编码状态下发送时，默认只取「最后一个结束符号之后」的部分。 */
    const val DEFAULT_SENTENCE_MARKS = "。？！!?"

    private const val DEFAULT_NAME = "default"
    private const val LEGACY_DEFAULT_CODE = "jt;"
    private const val DEFAULT_DEFAULT_CODE = "ai;"
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    @Volatile private var sp: SharedPreferences? = null

    fun init(context: Context) {
        if (sp == null) {
            synchronized(this) {
                if (sp == null) {
                    sp = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                    migrateLegacy()
                    migratePlaintextKey()
                }
            }
        }
        // 有些老版本用默认 prefs 存过，这里不做迁移，只保证初始化
        PreferenceManager.getDefaultSharedPreferences(context)
    }

    /**
     * 早期版本直接把 API Key 明文存在 prefs 里（`ai_api_key` 是裸字符串）。
     * 这里一次性换成 Keystore 密文；换不成就原样留着，下次启动再试。
     */
    private fun migratePlaintextKey() {
        val stored = sp?.getString(K_AI_KEY, null) ?: return
        if (stored.isEmpty() || SecretStore.isEncrypted(stored)) return
        val encrypted = SecretStore.encrypt(stored)
        if (encrypted == null) {
            Timber.w("bamboo: plaintext api key could not be encrypted yet")
            return
        }
        sp?.edit()?.putString(K_AI_KEY, encrypted)?.apply()
        keyCache = stored
        Timber.i("bamboo: api key migrated to keystore ciphertext")
    }

    /**
     * 0.9 版把 `jt;` 当成 default 模式的码；现在 `jt;` 是「发送剪贴板」。
     * 老机器上已经存过 aiModes 的，把 default 的码换回 `ai;`，只迁移一次。
     */
    private fun migrateLegacy() {
        val raw = sp?.getString(K_AI_MODES, null) ?: return
        val modes =
            runCatching { json.decodeFromString<List<AiMode>>(raw) }.getOrNull()
                ?: return
        // 旧版把内置键名当成显示名（`eng`），这里换成中文提示名并补上 key，只迁移一次
        val fixed =
            modes.map { m ->
                val builtinKey = m.key ?: m.name.takeIf { AiPrompts.builtin.containsKey(it) }
                if (builtinKey == null) {
                    m
                } else {
                    m.copy(name = AiPrompts.displayNameOf(builtinKey), key = builtinKey)
                }
            }.map {
                if (it.key == DEFAULT_NAME && it.code == LEGACY_DEFAULT_CODE) {
                    it.copy(code = DEFAULT_DEFAULT_CODE)
                } else {
                    it
                }
            }
        if (fixed != modes) {
            sp?.edit()?.putString(K_AI_MODES, json.encodeToString(fixed))?.apply()
        }
    }

    private fun p(): SharedPreferences {
        sp?.let { return it }
        // 设置页可能在输入法服务启动前就打开，这里兜底用 Application context
        val ctx = TrimeApplication.getInstance()
        init(ctx)
        return sp ?: error("BambooPrefs init failed")
    }

    // ---------- 总开关 ----------

    var enabled: Boolean
        get() = p().getBoolean(K_ENABLED, true)
        set(v) = p().edit().putBoolean(K_ENABLED, v).apply()

    // ---------- 回退 ----------

    var revertEnabled: Boolean
        get() = p().getBoolean(K_REVERT_ENABLED, true)
        set(v) = p().edit().putBoolean(K_REVERT_ENABLED, v).apply()

    /** 逗号或空格分隔的触发码，例如 `qq/,ss;`。 */
    var revertTriggersRaw: String
        get() = p().getString(K_REVERT_TRIGGERS, DEFAULT_REVERT_TRIGGERS) ?: DEFAULT_REVERT_TRIGGERS
        set(v) = p().edit().putString(K_REVERT_TRIGGERS, v).apply()

    val revertTriggers: List<String>
        get() =
            revertTriggersRaw
                .split(',', ' ', '，')
                .map { it.trim() }
                .filter { it.length >= 2 }

    var revertMaxCodePoints: Int
        get() = p().getInt(K_REVERT_MAX, DEFAULT_MAX_DELETE)
        set(v) = p().edit().putInt(K_REVERT_MAX, v.coerceIn(1, 5000)).apply()

    // ---------- AI ----------

    var aiEnabled: Boolean
        get() = p().getBoolean(K_AI_ENABLED, true)
        set(v) = p().edit().putBoolean(K_AI_ENABLED, v).apply()

    /**
     * Cached copy of [aiModes]. Keys are read on the hot path (every keystroke goes
     * through [modeByCode]), and decoding the JSON list there showed up as jank, so
     * the decoded list is kept until [aiModes] is written again.
     */
    @Volatile
    private var modesCache: List<AiMode>? = null

    /** 内置默认提示词 + 用户新增的模式。 */
    var aiModes: List<AiMode>
        get() {
            modesCache?.let { return it }
            val loaded = decodeModes(p().getString(K_AI_MODES, null))
            modesCache = loaded
            return loaded
        }
        set(v) {
            modesCache = v
            p().edit().putString(K_AI_MODES, json.encodeToString(v)).apply()
        }

    private fun decodeModes(raw: String?): List<AiMode> {
        if (raw.isNullOrBlank()) return defaults()
        val saved =
            runCatching { json.decodeFromString<List<AiMode>>(raw) }.getOrElse { return defaults() }
        // 新版本可能加了内置模式（例如 1.1.0 的「聊天」），老机器的存档里没有它。
        // 这里把缺的内置模式补在**末尾**，不动用户已有的序号（BambooAi1..N 的顺序不变）。
        val have = saved.mapNotNull { it.key }.toSet()
        val missing =
            AiPrompts.builtin.keys
                .filter { it !in have }
                .map { AiMode(AiPrompts.codeOf(it), AiPrompts.displayNameOf(it), AiPrompts.promptOf(it), it) }
        return if (missing.isEmpty()) saved else saved + missing
    }

    fun defaults(): List<AiMode> =
        AiPrompts.builtin.map { (name, prompt) ->
            AiMode(AiPrompts.codeOf(name), AiPrompts.displayNameOf(name), prompt, name)
        }

    fun modeByCode(code: String): AiMode? = aiModes.firstOrNull { it.code == code }

    /** 发送键：默认 `/`，只取第一个字符。 */
    var aiSendKey: String
        get() = p().getString(K_AI_SEND_KEY, "/") ?: "/"
        set(v) = p().edit().putString(K_AI_SEND_KEY, v.take(1)).apply()

    /**
     * 双击发送键是否生效。
     *
     * 1.1.0 起「双击 / 快捷组合键」从二选一拆成两个独立开关：两条老路都可以单独关掉，
     * 都关掉时只剩主题动作名（token）和快捷码可用 —— 它们本来就不受这两个开关影响。
     * 新键没写过时，用旧存档里二选一的结果当默认值；旧存档也没写过时默认**关闭**
     * （即默认不启用双击 `/` 发送，只保留快捷组合键这条路）。
     */
    var aiDoubleTapEnabled: Boolean
        get() = p().getBoolean(K_AI_SEND_DOUBLE, legacyTrigger() != TRIGGER_COMBO)
        set(v) = p().edit().putBoolean(K_AI_SEND_DOUBLE, v).apply()

    /** 快捷组合键发送是否生效（主要给外接键盘）。 */
    var aiComboEnabled: Boolean
        get() = p().getBoolean(K_AI_SEND_COMBO, legacyTrigger() == TRIGGER_COMBO)
        set(v) = p().edit().putBoolean(K_AI_SEND_COMBO, v).apply()

    /** 旧版「发送方式」的残留值，只用来给上面两个开关提供默认值。 */
    private fun legacyTrigger(): String =
        p().getString(K_AI_TRIGGER_MODE, TRIGGER_COMBO) ?: TRIGGER_COMBO

    /** 快捷组合键，例如 `ctrl+;`。 */
    var aiCombo: String
        get() = p().getString(K_AI_COMBO, DEFAULT_AI_COMBO) ?: DEFAULT_AI_COMBO
        set(v) = p().edit().putString(K_AI_COMBO, v.trim()).apply()

    /** 剪贴板发送：文字快捷码 `jt;` 是否启用（与下面的组合键互不影响）。 */
    var clipCodeEnabled: Boolean
        get() = p().getBoolean(K_CLIP_CODE_ENABLED, true)
        set(v) = p().edit().putBoolean(K_CLIP_CODE_ENABLED, v).apply()

    /**
     * 没进入 AI 模式时，快捷组合键 / 剪贴板码是否也发给默认模式（`ai;`）。
     * 默认 true；关掉后：组合键直接放行、文字码只提示不发送。
     */
    var aiFallbackDefault: Boolean
        get() = p().getBoolean(K_FALLBACK_DEFAULT, true)
        set(v) = p().edit().putBoolean(K_FALLBACK_DEFAULT, v).apply()

    /** 剪贴板发送：快捷组合键（可单独开关，与 `jt;` 互不影响）。 */
    var clipComboEnabled: Boolean
        get() = p().getBoolean(K_CLIP_COMBO_ENABLED, true)
        set(v) = p().edit().putBoolean(K_CLIP_COMBO_ENABLED, v).apply()

    var clipCombo: String
        get() = p().getString(K_CLIP_COMBO, DEFAULT_CLIP_COMBO) ?: DEFAULT_CLIP_COMBO
        set(v) = p().edit().putString(K_CLIP_COMBO, v.trim()).apply()

    var aiDoubleMs: Int
        get() = p().getInt(K_AI_DOUBLE_MS, 1000)
        set(v) = p().edit().putInt(K_AI_DOUBLE_MS, v.coerceIn(200, 3000)).apply()

    /** 接口地址默认值：用户没填过就用它，填过的仍用自己的。 */
    var aiBaseUrl: String
        get() = p().getString(K_AI_BASE_URL, "").orEmpty().ifEmpty { DEFAULT_AI_BASE_URL }
        set(v) = p().edit().putString(K_AI_BASE_URL, v.trim()).apply()

    /** 模型名默认值：同上。 */
    var aiModel: String
        get() = p().getString(K_AI_MODEL, "").orEmpty().ifEmpty { DEFAULT_AI_MODEL }
        set(v) = p().edit().putString(K_AI_MODEL, v.trim()).apply()

    /**
     * AI 服务的 API Key。**落盘时永远是密文**（[SecretStore]，Android Keystore 里的
     * 不可导出 AES-256-GCM 密钥），内存里才保留明文，避免每次按键都过一遍 Keystore。
     *
     * 三种边界：
     *  * 读到空 → 返回 ""；
     *  * 读到解不开的密文（换机恢复备份、Keystore 被清）→ 返回 ""，密文保留不动；
     *  * 加密失败（极少见）→ 内存里仍可用，但不写盘，宁可下次让用户重填，
     *    也不把明文落到 shared_prefs。
     */
    var aiApiKey: String
        get() {
            keyCache?.let { return it }
            val plain = SecretStore.decrypt(p().getString(K_AI_KEY, null)) ?: ""
            keyCache = plain
            return plain
        }
        set(v) {
            val trimmed = v.trim()
            keyCache = trimmed
            if (trimmed.isEmpty()) {
                p().edit().remove(K_AI_KEY).apply()
                return
            }
            val encrypted = SecretStore.encrypt(trimmed)
            if (encrypted == null) {
                Timber.w("bamboo: API key not persisted (keystore unavailable)")
                return
            }
            p().edit().putString(K_AI_KEY, encrypted).apply()
        }

    @Volatile private var keyCache: String? = null

    var aiTemperature: Float
        get() = p().getFloat(K_AI_TEMP, 0.7f)
        set(v) = p().edit().putFloat(K_AI_TEMP, v.coerceIn(0f, 2f)).apply()

    var aiMaxTokens: Int
        get() = p().getInt(K_AI_MAX_TOKENS, 1024)
        set(v) = p().edit().putInt(K_AI_MAX_TOKENS, v.coerceIn(1, 32768)).apply()

    var aiTimeoutMs: Int
        get() = p().getInt(K_AI_TIMEOUT_MS, 60_000)
        set(v) = p().edit().putInt(K_AI_TIMEOUT_MS, v.coerceIn(5_000, 300_000)).apply()

    /** AI 上屏后是否再补一个空格（默认关闭）。 */
    var aiSendAfter: Boolean
        get() = p().getBoolean(K_AI_SEND_AFTER, false)
        set(v) = p().edit().putBoolean(K_AI_SEND_AFTER, v).apply()

    /** AI 模式下「发送剪贴板」的触发码（对应原版 actions/ai/clip_key）。 */
    var aiClipCode: String
        get() = p().getString(K_AI_CLIP_CODE, "jt;") ?: "jt;"
        set(v) = p().edit().putString(K_AI_CLIP_CODE, v.trim()).apply()

    /** AI 模式下「退出模式」的触发码；留空则只能用同一个 AI 码退出。 */
    var aiExitCode: String
        get() = p().getString(K_AI_EXIT_CODE, "tc;") ?: "tc;"
        set(v) = p().edit().putString(K_AI_EXIT_CODE, v.trim()).apply()

    /**
     * 发送时用哪段文本：
     *  * [SOURCE_AUTO]（默认）—— 有拼写就用拼写，没有就用最近一次上屏；
     *  * [SOURCE_COMPOSITION] —— 只用拼写（没有就报错，不回退到上屏）；
     *  * [SOURCE_COMMITTED] —— 只用最近一次上屏。
     */
    var aiSource: String
        get() = p().getString(K_AI_SOURCE, SOURCE_AUTO) ?: SOURCE_AUTO
        set(v) = p().edit().putString(K_AI_SOURCE, v).apply()

    /** true = 发送后把拼写候选丢掉（不上屏），只上屏 AI 的结果。 */
    var aiDiscardComposition: Boolean
        get() = p().getBoolean(K_AI_DISCARD_COMPOSITION, true)
        set(v) = p().edit().putBoolean(K_AI_DISCARD_COMPOSITION, v).apply()

    /**
     * true（默认）= 非编码状态下发送时，只发「光标前最后一个结束符号之后」的那段；
     * false = 发上一次上屏的整段。
     */
    var aiSentenceOnly: Boolean
        get() = p().getBoolean(K_AI_SENTENCE_ONLY, true)
        set(v) = p().edit().putBoolean(K_AI_SENTENCE_ONLY, v).apply()

    /** 结束符号表，可自选。 */
    var aiSentenceMarks: String
        get() = p().getString(K_AI_SENTENCE_MARKS, DEFAULT_SENTENCE_MARKS) ?: DEFAULT_SENTENCE_MARKS
        set(v) = p().edit().putString(K_AI_SENTENCE_MARKS, v).apply()

    // ---------- 日志 ----------

    /** 打开后 glog 的 WARNING/ERROR 会写到 <方案包>/logs/。 */
    var logToFile: Boolean
        get() = p().getBoolean(K_LOG_FILE, false)
        set(v) = p().edit().putBoolean(K_LOG_FILE, v).apply()

    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        p().registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        p().unregisterOnSharedPreferenceChangeListener(listener)
    }
}
