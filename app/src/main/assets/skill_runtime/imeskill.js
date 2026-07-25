/**
 * IMESkill Bridge 垫片（宿主注入，技能脚本勿修改）。
 *
 * 底层经 __IMESkillNative.postMessage 单入口与宿主通信，
 * 全部 API 返回 Promise；宿主经 window.__imeskillResolve 异步回传结果。
 * 幂等：重复注入（DOCUMENT_START_SCRIPT + onPageStarted 双路径）不重复初始化。
 */
(function () {
  'use strict';
  if (window.IMESkill) return;

  var seq = 0;
  var pending = {};

  window.__imeskillResolve = function (callId, ok, dataJson) {
    var p = pending[callId];
    if (!p) return;
    delete pending[callId];
    var data = null;
    if (dataJson !== null && dataJson !== undefined) {
      try { data = JSON.parse(dataJson); } catch (e) { data = null; }
    }
    if (ok) {
      p.resolve(data);
    } else {
      p.reject(new Error(data && data.message ? data.message : 'IMESkill error'));
    }
  };

  function call(method, params) {
    return new Promise(function (resolve, reject) {
      var id = ++seq;
      pending[id] = { resolve: resolve, reject: reject };
      try {
        __IMESkillNative.postMessage(JSON.stringify({
          callId: id, method: method, params: params || {}
        }));
      } catch (e) {
        delete pending[id];
        reject(e);
      }
    });
  }

  // ===== 输入路由（Phase 3）：宿主把键盘上屏文本注入 requestFocus 登记的元素 =====
  var focusedInput = null;

  function insertText(el, text) {
    var start = typeof el.selectionStart === 'number' ? el.selectionStart : el.value.length;
    var end = typeof el.selectionEnd === 'number' ? el.selectionEnd : start;
    el.value = el.value.slice(0, start) + text + el.value.slice(end);
    var pos = start + text.length;
    try { el.setSelectionRange(pos, pos); } catch (e) { }
    el.dispatchEvent(new Event('input', { bubbles: true }));
  }

  function deleteBack(el) {
    var start = typeof el.selectionStart === 'number' ? el.selectionStart : el.value.length;
    var end = typeof el.selectionEnd === 'number' ? el.selectionEnd : start;
    if (start === end && start > 0) start -= 1;
    if (end <= 0) return;
    el.value = el.value.slice(0, start) + el.value.slice(end);
    try { el.setSelectionRange(start, start); } catch (e) { }
    el.dispatchEvent(new Event('input', { bubbles: true }));
  }

  // 宿主回调入口（勿在技能脚本中直接调用）
  window.__imeskillInput = {
    commit: function (text) { if (focusedInput) insertText(focusedInput, String(text)); },
    backspace: function () { if (focusedInput) deleteBack(focusedInput); }
  };

  window.IMESkill = {
    /** 宿主 Bridge API 版本（manifest.min_host_api 协商用）：v2 增 fetch/clipboard/input */
    apiVersion: 2,

    /** 文本上屏并关闭面板 */
    sendText: function (text) { return call('sendText', { text: String(text) }); },

    /** 宿主环境信息：{packageName, inputType} */
    getContext: function () { return call('getContext'); },

    /** 系统语言（BCP 47，如 zh-CN） */
    getLocale: function () { return call('getLocale'); },

    /** 震动反馈 */
    haptic: function () { return call('haptic'); },

    /** 轻量 KV 持久化（需 storage 权限；value 按字符串存取） */
    storage: {
      get: function (key) { return call('storage.get', { key: String(key) }); },
      set: function (key, value) { return call('storage.set', { key: String(key), value: String(value) }); },
      remove: function (key) { return call('storage.remove', { key: String(key) }); }
    },

    /** 面板 UI 控制 */
    ui: {
      setTitle: function (title) { return call('ui.setTitle', { title: String(title) }); },
      close: function () { return call('ui.close'); },
      /**
       * 输入法界面展开/收缩（仅 needs_input 技能有效）：
       * setExpanded(false) 收缩整个输入法界面——键盘、编码区、候选区一并缩回，
       * 面板接管其空间（IME 窗口总高不变），适合查询完成后展示长内容；
       * setExpanded(true) 完整恢复；input.requestFocus 会自动恢复（打字需要完整界面）。
       */
      setExpanded: function (expanded) {
        return call('ui.setExpanded', { expanded: expanded === undefined ? true : !!expanded });
      }
    },

    /** 剪贴板（各需对应权限：clipboard_read / clipboard_write） */
    clipboard: {
      read: function () { return call('clipboard.read'); },
      write: function (text) { return call('clipboard.write', { text: String(text) }); }
    },

    /**
     * 面板内文本输入路由（需 manifest 声明 needs_input）：
     * requestFocus 后键盘上屏文本（含中文候选）注入指定 id 的 input/textarea 元素，
     * releaseFocus 后恢复直达宿主应用编辑框。
     */
    input: {
      requestFocus: function (fieldId) {
        var el = document.getElementById(String(fieldId));
        if (!el) return Promise.reject(new Error('元素不存在: ' + fieldId));
        return call('input.requestFocus', { fieldId: String(fieldId) }).then(function () {
          focusedInput = el;
        });
      },
      releaseFocus: function () {
        focusedInput = null;
        return call('input.releaseFocus');
      }
    },

    /**
     * 网络请求代理（需 network 权限 + network_domains 白名单）。
     * 返回 {status, body}；options 支持 {method:'POST', body, contentType}。
     */
    fetch: function (url, options) { return call('fetch', { url: String(url), options: options || {} }); }
  };
})();
