// SPDX-FileCopyrightText: 2015 - 2025 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

// 字由输入法 JNI核心文件 - 基于Trime精简

#include <rime_api.h>

#include <android/log.h>
#include <memory>
#include <string>
#include <vector>

#include "jni-utils.h"
#include "objconv.h"
#include "session.h"

#define MAX_BUFFER_LENGTH 2048

// 条件编译：可选模块依赖声明
#ifdef WITH_LUA
extern void rime_require_module_lua();
#endif
#ifdef WITH_OCTAGRAM
extern void rime_require_module_octagram();
#endif
#ifdef WITH_PREDICT
extern void rime_require_module_predict();
#endif

// librime编译为静态库时需要显式链接模块
static void declare_librime_module_dependencies() {
#ifdef WITH_LUA
  rime_require_module_lua();
#endif
#ifdef WITH_OCTAGRAM
  rime_require_module_octagram();
#endif
#ifdef WITH_PREDICT
  rime_require_module_predict();
#endif
}

// Rime引擎单例封装，管理会话生命周期和所有核心操作
class Rime {
 public:
  Rime() : rime(rime_get_api()), initialized_(false) {}
  Rime(Rime const &) = delete;
  void operator=(Rime const &) = delete;

  static Rime &Instance() {
    static Rime instance;
    return instance;
  }

  bool isInitialized() const { return initialized_ && rime; }

  void startup(bool fullCheck,
               const RimeNotificationHandler &notificationHandler) {
    if (!rime) return;
    // 防止重复初始化，避免内存泄漏和崩溃
    if (initialized_) {
      __android_log_print(ANDROID_LOG_WARN, "RimeJNI",
                          "Rime already initialized, skipping startup");
      return;
    }
    const char *userDir = getenv("RIME_USER_DATA_DIR");
    const char *sharedDir = getenv("RIME_SHARED_DATA_DIR");
    const char *versionName = getenv("RIME_DISTRIBUTION_VERSION");

    RIME_STRUCT(RimeTraits, traits)
    traits.shared_data_dir = sharedDir;
    traits.user_data_dir = userDir;
    traits.log_dir = "";  // 设为空以仅输出到logcat
    traits.app_name = "rime.ziyou";
    traits.distribution_name = "字由";
    traits.distribution_code_name = "ziyou";
    traits.distribution_version = versionName;

    rime->setup(&traits);
    rime->initialize(&traits);
    rime->set_notification_handler(notificationHandler, GlobalRef->jvm);
    rime->start_maintenance(fullCheck);
    initialized_ = true;
    __android_log_print(ANDROID_LOG_INFO, "RimeJNI",
                        "Rime engine started successfully");
  }

  bool processKey(int keycode, int mask) {
    return rime->process_key(session(), keycode, mask);
  }

  bool commitComposition() { return rime->commit_composition(session()); }

  void clearComposition() { rime->clear_composition(session()); }

  // 替换编码串指定区间（九宫格拼音消歧）。
  // 注意：set_input 会重建整个 composition，引擎内经 select_candidate 分段确认的
  // 段落会全部丢失（已确认汉字被打回拼音）。存在确认段时禁止调用本方法，
  // 由 Kotlin 层（InputLogicController 改走「退格重打」路径）保证。
  bool replaceKey(int caretPos, int length, const char* replacement) {
    auto s = session();
    auto input = rime->get_input(s);
    if (!input) return false;
    std::string str(input);
    if (caretPos < 0 || caretPos + length > (int)str.size()) return false;
    str.replace(caretPos, length, replacement);
    rime->set_input(s, str.c_str());
    rime->set_caret_pos(s, caretPos + strlen(replacement));
    return true;
  }

  std::unique_ptr<CommitProto> commit() {
    RIME_STRUCT(RimeCommit, data)
    if (rime->get_commit(session(), &data)) {
      auto p = std::make_unique<CommitProto>(&data);
      rime->free_commit(&data);
      return p;
    }
    return std::make_unique<CommitProto>();
  }

  std::unique_ptr<ContextProto> context() {
    RIME_STRUCT(RimeContext, data)
    auto s = session();
    if (rime->get_context(s, &data)) {
      auto input = rime->get_input(s);
      auto caretPos = rime->get_caret_pos(s);
      auto p = std::make_unique<ContextProto>(&data, input, caretPos);
      rime->free_context(&data);
      return p;
    }
    return std::make_unique<ContextProto>();
  }

  std::unique_ptr<StatusProto> status() {
    RIME_STRUCT(RimeStatus, data)
    if (rime->get_status(session(), &data)) {
      auto p = std::make_unique<StatusProto>(&data);
      rime->free_status(&data);
      return p;
    }
    return std::make_unique<StatusProto>();
  }

  void setOption(std::string_view key, bool value) {
    rime->set_option(session(), key.data(), value);
  }

  bool getOption(std::string_view key) {
    return rime->get_option(session(), key.data());
  }

  std::string currentSchemaId() {
    if (!isInitialized()) return "";
    char result[MAX_BUFFER_LENGTH];
    return rime->get_current_schema(session(), result, MAX_BUFFER_LENGTH)
               ? result
               : "";
  }

  std::vector<SchemaItem> schemaList() {
    std::vector<SchemaItem> result;
    if (!isInitialized()) return result;
    RimeSchemaList list{};
    if (rime->get_schema_list(&list)) {
      result = SchemaItem::fromCList(list);
      rime->free_schema_list(&list);
    }
    return std::move(result);
  }

  bool selectSchema(std::string_view schemaId) {
    return rime->select_schema(session(), schemaId.data());
  }

  bool selectCandidate(size_t index, bool global) {
    if (global) {
      return rime->select_candidate(session(), index);
    } else {
      return rime->select_candidate_on_current_page(session(), index);
    }
  }

  bool deleteCandidate(size_t index, bool global) {
    if (global) {
      return rime->delete_candidate(session(), index);
    } else {
      return rime->delete_candidate_on_current_page(session(), index);
    }
  }

  bool changePage(bool backward) {
    return rime->change_page(session(), backward);
  }

  std::vector<CandidateProto> getCandidates(int startIndex, int limit) {
    std::vector<CandidateProto> result;
    result.reserve(limit);
    RimeCandidateListIterator iter{};
    if (rime->candidate_list_from_index(session(), &iter, startIndex)) {
      int count = 0;
      while (rime->candidate_list_next(&iter)) {
        if (count >= limit) break;
        result.emplace_back(iter.candidate);
        ++count;
      }
      rime->candidate_list_end(&iter);
    }
    return std::move(result);
  }

  std::tuple<int, int, std::vector<CandidateProto>> getBulkCandidates() {
    constexpr int limit = 16;
    auto list = getCandidates(0, limit);
    // 使用-1表示当前候选词数量不确定
    auto size = list.size() < limit ? list.size() : -1;
    // 从 RimeContext 获取高亮候选词索引
    int highlighted = 0;
    RIME_STRUCT(RimeContext, ctx)
    if (rime->get_context(session(), &ctx)) {
      highlighted = ctx.menu.highlighted_candidate_index;
      rime->free_context(&ctx);
    }
    return std::make_tuple(size, highlighted, std::move(list));
  }

  void exit() {
    session_.reset();
    rime->finalize();
    initialized_ = false;
  }

  bool sync() {
    session_.reset();
    return rime->sync_user_data();
  }

 private:
  RimeApi *rime;
  bool initialized_;
  std::shared_ptr<SessionHolder> session_;

  RimeSessionId session(bool requestNewSession = true) {
    if (!session_ && requestNewSession) {
      try {
        auto newSession = std::make_shared<SessionHolder>();
        session_ = newSession;
      } catch (const std::exception &e) {
        __android_log_print(ANDROID_LOG_ERROR, "RimeJNI",
                            "Rime session creation failed: %s", e.what());
        session_ = nullptr;
      } catch (...) {
        __android_log_print(ANDROID_LOG_ERROR, "RimeJNI",
                            "Rime session creation failed: unknown exception");
        session_ = nullptr;
      }
    }
    if (!session_) {
      return 0;
    }
    return session_->id();
  }
};

// 全局引用单例指针
GlobalRefSingleton *GlobalRef;

// JNI库加载入口
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *jvm, void *reserved) {
  GlobalRef = new GlobalRefSingleton(jvm);
  declare_librime_module_dependencies();
  return JNI_VERSION_1_6;
}

// ==================== JNI导出函数 ====================

// 启动Rime引擎
extern "C" JNIEXPORT void JNICALL
Java_com_ziyou_ime_core_RimeNative_startupRime(
    JNIEnv *env, jclass clazz, jstring shared_dir, jstring user_dir,
    jstring version_name, jboolean full_check) {
  setenv("RIME_SHARED_DATA_DIR", CString(env, shared_dir), 1);
  setenv("RIME_USER_DATA_DIR", CString(env, user_dir), 1);
  setenv("RIME_DISTRIBUTION_VERSION", CString(env, version_name), 1);

  // 消息回调：将Rime通知转发到Java层
  auto notificationHandler = [](void *context_object, RimeSessionId session_id,
                                const char *message_type,
                                const char *message_value) {
    if (!message_type || !message_value) return;
    auto env = GlobalRef->AttachEnv();
    int type = 0;  // unknown
    if (strcmp(message_type, "schema") == 0) {
      type = 1;
    } else if (strcmp(message_type, "option") == 0) {
      type = 2;
    } else if (strcmp(message_type, "deploy") == 0) {
      type = 3;
    }
    auto vararg = JRef<jobjectArray>(
        env, env->NewObjectArray(1, GlobalRef->Object, nullptr));
    env->SetObjectArrayElement(vararg, 0, JString(env, message_value));
    env->CallStaticVoidMethod(GlobalRef->Rime, GlobalRef->HandleRimeMessage,
                              type, *vararg);
    // 检查并清除 JNI 异常，避免崩溃
    if (env->ExceptionCheck()) {
      env->ExceptionDescribe();
      env->ExceptionClear();
    }
  };

  Rime::Instance().startup(full_check, notificationHandler);
}

// 退出Rime引擎
extern "C" JNIEXPORT void JNICALL
Java_com_ziyou_ime_core_RimeNative_exitRime(JNIEnv *env,
                                                     jclass /* thiz */) {
  Rime::Instance().exit();
}

// 同步用户数据
extern "C" JNIEXPORT jboolean JNICALL
Java_com_ziyou_ime_core_RimeNative_syncRimeUserData(JNIEnv *env,
                                                             jclass /* thiz */) {
  return Rime::Instance().sync();
}

// ==================== 输入处理 ====================

// 处理按键事件
extern "C" JNIEXPORT jboolean JNICALL
Java_com_ziyou_ime_core_RimeNative_processRimeKey(JNIEnv *env,
                                                           jclass /* thiz */,
                                                           jint keycode,
                                                           jint mask) {
  return Rime::Instance().processKey(keycode, mask);
}

// 批量按键处理（热路径）：process_key + get_commit + get_context 合并为单次跨界，
// 返回 [consumed: Boolean, commit: CommitProto?, context: ContextProto?]；
// 未消费时后两项为 null（调用方无需刷新 UI）
extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_ziyou_ime_core_RimeNative_processRimeKeyBulk(
    JNIEnv *env, jclass /* thiz */, jint keycode, jint mask) {
  auto &rime = Rime::Instance();
  bool consumed = rime.processKey(keycode, mask);
  auto jConsumed = JRef(env, env->NewObject(GlobalRef->Boolean,
                                            GlobalRef->BooleanInit, consumed));
  auto result = env->NewObjectArray(3, GlobalRef->Object, nullptr);
  env->SetObjectArrayElement(result, 0, jConsumed);
  if (consumed) {
    auto commit = rime.commit();
    auto jCommit = JRef(env, rimeCommitToJObject(env, *commit));
    auto context = rime.context();
    auto jContext = JRef(env, rimeContextToJObject(env, *context));
    env->SetObjectArrayElement(result, 1, jCommit);
    env->SetObjectArrayElement(result, 2, jContext);
  }
  return result;
}

// 提交当前组合
extern "C" JNIEXPORT jboolean JNICALL
Java_com_ziyou_ime_core_RimeNative_commitRimeComposition(
    JNIEnv *env, jclass /* thiz */) {
  return Rime::Instance().commitComposition();
}

// 清除当前组合
extern "C" JNIEXPORT void JNICALL
Java_com_ziyou_ime_core_RimeNative_clearRimeComposition(
    JNIEnv *env, jclass /* thiz */) {
  Rime::Instance().clearComposition();
}

// 替换编码中指定位置的键序列（用于九宫格拼音消歧）
extern "C" JNIEXPORT jboolean JNICALL
Java_com_ziyou_ime_core_RimeNative_replaceRimeKey(
    JNIEnv *env, jclass /* clazz */, jint caretPos, jint length, jstring key) {
  auto keyStr = CString(env, key);
  return Rime::Instance().replaceKey(caretPos, length, keyStr);
}

// ==================== 输出获取 ====================

// 获取已提交文本
extern "C" JNIEXPORT jobject JNICALL
Java_com_ziyou_ime_core_RimeNative_getRimeCommit(JNIEnv *env,
                                                          jclass /* thiz */) {
  auto commit = Rime::Instance().commit();
  return rimeCommitToJObject(env, *commit);
}

// 获取输入上下文
extern "C" JNIEXPORT jobject JNICALL
Java_com_ziyou_ime_core_RimeNative_getRimeContext(JNIEnv *env,
                                                           jclass /* thiz */) {
  auto context = Rime::Instance().context();
  return rimeContextToJObject(env, *context);
}

// 获取当前状态
extern "C" JNIEXPORT jobject JNICALL
Java_com_ziyou_ime_core_RimeNative_getRimeStatus(JNIEnv *env,
                                                          jclass /* thiz */) {
  auto status = Rime::Instance().status();
  return rimeStatusToJObject(env, *status);
}

// ==================== 运行时选项 ====================

// 设置选项
extern "C" JNIEXPORT void JNICALL
Java_com_ziyou_ime_core_RimeNative_setRimeOption(JNIEnv *env,
                                                          jclass /* thiz */,
                                                          jstring option,
                                                          jboolean value) {
  Rime::Instance().setOption(*CString(env, option), value);
}

// 获取选项
extern "C" JNIEXPORT jboolean JNICALL
Java_com_ziyou_ime_core_RimeNative_getRimeOption(JNIEnv *env,
                                                          jclass /* thiz */,
                                                          jstring option) {
  return Rime::Instance().getOption(*CString(env, option));
}

// ==================== 方案管理 ====================

// 获取方案列表
extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_ziyou_ime_core_RimeNative_getRimeSchemaList(
    JNIEnv *env, jclass /* thiz */) {
  return rimeSchemaListToJObjectArray(env, Rime::Instance().schemaList());
}

// 获取当前方案ID
extern "C" JNIEXPORT jstring JNICALL
Java_com_ziyou_ime_core_RimeNative_getCurrentRimeSchema(
    JNIEnv *env, jclass /* thiz */) {
  return env->NewStringUTF(Rime::Instance().currentSchemaId().c_str());
}

// 选择方案
extern "C" JNIEXPORT jboolean JNICALL
Java_com_ziyou_ime_core_RimeNative_selectRimeSchema(JNIEnv *env,
                                                             jclass /* thiz */,
                                                             jstring schema_id) {
  return Rime::Instance().selectSchema(*CString(env, schema_id));
}

// ==================== 候选词操作 ====================

// 选择候选词
extern "C" JNIEXPORT jboolean JNICALL
Java_com_ziyou_ime_core_RimeNative_selectRimeCandidate(
    JNIEnv *env, jclass /* thiz */, jint index, jboolean global) {
  return Rime::Instance().selectCandidate(index, global);
}

// 删除候选词
extern "C" JNIEXPORT jboolean JNICALL
Java_com_ziyou_ime_core_RimeNative_deleteRimeCandidate(
    JNIEnv *env, jclass /* thiz */, jint index, jboolean global) {
  return Rime::Instance().deleteCandidate(index, global);
}

// 翻页
extern "C" JNIEXPORT jboolean JNICALL
Java_com_ziyou_ime_core_RimeNative_changeRimeCandidatePage(
    JNIEnv *env, jclass clazz, jboolean backward) {
  return Rime::Instance().changePage(backward);
}

// 获取候选词列表（指定起始位置和数量）
extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_ziyou_ime_core_RimeNative_getRimeCandidates(JNIEnv *env,
                                                              jclass clazz,
                                                              jint start_index,
                                                              jint limit) {
  return rimeCandidateListToJObjectArray(
      env, Rime::Instance().getCandidates(start_index, limit));
}

// 批量获取候选词（返回[size, highlighted, candidates]）
extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_ziyou_ime_core_RimeNative_getRimeBulkCandidates(
    JNIEnv *env, jclass clazz) {
  auto [size, highlighted, list] = Rime::Instance().getBulkCandidates();
  auto jSize = JRef(
      env, env->NewObject(GlobalRef->Integer, GlobalRef->IntegerInit, size));
  auto jHighlighted = JRef(
      env,
      env->NewObject(GlobalRef->Integer, GlobalRef->IntegerInit, highlighted));
  auto jList =
      JRef<jobjectArray>(env, rimeCandidateListToJObjectArray(env, list));
  auto params = env->NewObjectArray(3, GlobalRef->Object, nullptr);
  env->SetObjectArrayElement(params, 0, jSize);
  env->SetObjectArrayElement(params, 1, jHighlighted);
  env->SetObjectArrayElement(params, 2, jList);
  return params;
}
