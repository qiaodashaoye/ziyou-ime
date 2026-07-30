package com.ziyou.ime.ai.knowledge

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.atomic.AtomicInteger

/**
 * AI 问答使用统计（本地）
 *
 * 遵循热路径持久化规范：计数经内存原子自增，达 [FLUSH_THRESHOLD] 次累计
 * 或面板 release 时批量落盘（SharedPreferences `ziyou_ai_stats`），
 * 禁止每次提问同步写 SP。仅本地统计，不上传。
 *
 * 指标：提问总数 / 成功数 / 失败数 / 知识库命中提问数 / 累计命中 chunk 数。
 */
object AiUsageStats {

    private const val PREF_NAME = "ziyou_ai_stats"
    private const val KEY_QUESTIONS = "questions"
    private const val KEY_SUCCESS = "success"
    private const val KEY_FAILURE = "failure"
    private const val KEY_KB_HIT_QUESTIONS = "kb_hit_questions"
    private const val KEY_KB_HIT_CHUNKS = "kb_hit_chunks"

    /** 未落盘增量达到该值时触发 flush */
    private const val FLUSH_THRESHOLD = 10

    // 内存增量计数器（自上次 flush 以来）
    private val pendingQuestions = AtomicInteger(0)
    private val pendingSuccess = AtomicInteger(0)
    private val pendingFailure = AtomicInteger(0)
    private val pendingKbHitQuestions = AtomicInteger(0)
    private val pendingKbHitChunks = AtomicInteger(0)

    /** 统计快照（含未落盘增量） */
    data class Snapshot(
        val questions: Int,
        val success: Int,
        val failure: Int,
        val kbHitQuestions: Int,
        val kbHitChunks: Int
    )

    // ===== 计数（热路径 O(1) 内存自增） =====

    /** 记一次提问；[kbChunkHits] 为本次检索命中的 chunk 数（未走知识库传 0）。 */
    fun recordQuestion(context: Context, kbChunkHits: Int) {
        pendingQuestions.incrementAndGet()
        if (kbChunkHits > 0) {
            pendingKbHitQuestions.incrementAndGet()
            pendingKbHitChunks.addAndGet(kbChunkHits)
        }
        maybeFlush(context)
    }

    /** 记一次回答成功。 */
    fun recordSuccess(context: Context) {
        pendingSuccess.incrementAndGet()
        maybeFlush(context)
    }

    /** 记一次回答失败。 */
    fun recordFailure(context: Context) {
        pendingFailure.incrementAndGet()
        maybeFlush(context)
    }

    // ===== 落盘与读取 =====

    /** 强制落盘未持久化的增量（面板 release 时调用）。 */
    @Synchronized
    fun flush(context: Context) {
        val questions = pendingQuestions.getAndSet(0)
        val success = pendingSuccess.getAndSet(0)
        val failure = pendingFailure.getAndSet(0)
        val kbHitQuestions = pendingKbHitQuestions.getAndSet(0)
        val kbHitChunks = pendingKbHitChunks.getAndSet(0)
        if (questions == 0 && success == 0 && failure == 0 &&
            kbHitQuestions == 0 && kbHitChunks == 0) return
        val prefs = getPreferences(context)
        prefs.edit()
            .putInt(KEY_QUESTIONS, prefs.getInt(KEY_QUESTIONS, 0) + questions)
            .putInt(KEY_SUCCESS, prefs.getInt(KEY_SUCCESS, 0) + success)
            .putInt(KEY_FAILURE, prefs.getInt(KEY_FAILURE, 0) + failure)
            .putInt(KEY_KB_HIT_QUESTIONS, prefs.getInt(KEY_KB_HIT_QUESTIONS, 0) + kbHitQuestions)
            .putInt(KEY_KB_HIT_CHUNKS, prefs.getInt(KEY_KB_HIT_CHUNKS, 0) + kbHitChunks)
            .apply()
    }

    /** 读取统计快照（已落盘 + 内存增量）。 */
    fun getSnapshot(context: Context): Snapshot {
        val prefs = getPreferences(context)
        return Snapshot(
            questions = prefs.getInt(KEY_QUESTIONS, 0) + pendingQuestions.get(),
            success = prefs.getInt(KEY_SUCCESS, 0) + pendingSuccess.get(),
            failure = prefs.getInt(KEY_FAILURE, 0) + pendingFailure.get(),
            kbHitQuestions = prefs.getInt(KEY_KB_HIT_QUESTIONS, 0) + pendingKbHitQuestions.get(),
            kbHitChunks = prefs.getInt(KEY_KB_HIT_CHUNKS, 0) + pendingKbHitChunks.get()
        )
    }

    /** 未落盘增量总数达阈值时批量落盘。 */
    private fun maybeFlush(context: Context) {
        val pendingTotal = pendingQuestions.get() + pendingSuccess.get() +
            pendingFailure.get() + pendingKbHitQuestions.get()
        if (pendingTotal >= FLUSH_THRESHOLD) {
            flush(context.applicationContext)
        }
    }

    private fun getPreferences(context: Context): SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
}
