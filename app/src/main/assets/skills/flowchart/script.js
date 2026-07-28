'use strict';
/**
 * 流程图技能编辑器状态机。
 *
 * 单一事实源：伪代码文本（lines 数组）。可视化操作生成/删除伪代码行 →
 * 重新 parse → 刷新步骤列表；文本模式直接编辑同一份文本，双向天然同步。
 * 交互遵循 needs_input 四阶段模式：路由输入 → 编辑 → 预览（setExpanded 收缩）→ 输出。
 */
(function () {
  var MAX_IMAGE_BASE64 = 460 * 1024; // Bridge 单消息 512KB 上限内留余量
  var DRAFT_KEY = 'draft';

  /**
   * 面板高度（键盘高度倍数，宿主按 SkillPanelSpec 钳制到 [0.4, 1.2]）：
   * 编辑/预览界面内容密度高，默认 0.6 紧凑高度下步骤列表/画布局促，
   * 启动即拉高到 0.9；路由打字时下方键盘/候选区仍完整可用。
   */
  var PANEL_HEIGHT_RATIO = 0.9;

  var lines = [];        // 伪代码行（单一事实源）
  var selIndex = -1;     // 选中行（可视化编辑的插入锚点）
  var model = FlowParser.parse('');
  var currentTab = 'edit';
  var draftTimer = null;
  var clearArmed = false;

  var el = {};
  ['tab-edit', 'tab-text', 'tab-preview', 'view-edit', 'view-text', 'view-preview',
    'steps', 'form', 'cond-row', 'cond-input', 'name-input', 'name-label',
    'form-ok', 'form-cancel', 'btn-step', 'btn-branch', 'btn-del', 'btn-clear',
    'pseudo-text', 'warn', 'ins-arrow', 'ins-branch', 'ins-comma', 'ins-newline',
    'preview-canvas', 'out-hint', 'btn-code', 'btn-image', 'btn-gallery'
  ].forEach(function (id) { el[id] = document.getElementById(id); });

  // ===== 文本状态 =====

  function pseudoText() { return lines.join('\n'); }

  function setText(text, skipTextarea) {
    lines = text === '' ? [] : String(text).split('\n');
    model = FlowParser.parse(pseudoText());
    if (!skipTextarea) el['pseudo-text'].value = pseudoText();
    renderSteps();
    renderWarnings();
    scheduleDraftSave();
  }

  function scheduleDraftSave() {
    if (draftTimer) clearTimeout(draftTimer);
    draftTimer = setTimeout(function () {
      IMESkill.storage.set(DRAFT_KEY, pseudoText()).catch(function () { });
    }, 800);
  }

  /** 行 lineInfo 的末端节点标签（新步骤的显式来源），逗号复原多行文字 */
  function endLabelOf(info) {
    var node = null;
    if (info.type === 'node') node = info.node;
    else if (info.type === 'chain') node = info.nodes[info.nodes.length - 1];
    else if (info.type === 'branch') node = info.to;
    return node ? node.lines.join(',') : null;
  }

  /** 当前插入锚点：选中行；未选中时取最后一条有效行 */
  function anchorIndex() {
    if (selIndex >= 0 && selIndex < lines.length) return selIndex;
    for (var i = lines.length - 1; i >= 0; i--) {
      var info = model.lineInfos[i];
      if (info && info.type !== 'empty') return i;
    }
    return -1;
  }

  function insertLine(afterIndex, text) {
    if (afterIndex < 0) lines.push(text);
    else lines.splice(afterIndex + 1, 0, text);
    selIndex = afterIndex < 0 ? lines.length - 1 : afterIndex + 1;
    setText(pseudoText());
  }

  // ===== 可视化步骤列表 =====

  function stepDisplay(info) {
    if (info.type === 'node') {
      return { tag: info.node.shape === 'terminal' ? '◯' : '▢', txt: info.node.lines.join('，') };
    }
    if (info.type === 'chain') {
      var labels = [];
      for (var i = 0; i < info.nodes.length; i++) labels.push(info.nodes[i].lines.join('，'));
      return { tag: '▢', txt: labels.join(' → ') };
    }
    if (info.type === 'branch') {
      return { tag: '◇', txt: (info.cond || '…') + ' → ' + info.to.lines.join('，') };
    }
    return { tag: '⚠', txt: info.raw || '' };
  }

  function renderSteps() {
    var box = el.steps;
    box.innerHTML = '';
    var hasContent = false;
    for (var i = 0; i < model.lineInfos.length; i++) {
      var info = model.lineInfos[i];
      if (info.type === 'empty') continue;
      hasContent = true;
      var row = document.createElement('div');
      row.className = 'step' + (info.type === 'branch' ? ' branch' : '') +
        (info.type === 'error' ? ' err' : '') + (i === selIndex ? ' sel' : '');
      var display = stepDisplay(info);
      var tag = document.createElement('span');
      tag.className = 'tag';
      tag.textContent = display.tag;
      var txt = document.createElement('span');
      txt.className = 'txt';
      txt.textContent = display.txt;
      row.appendChild(tag);
      row.appendChild(txt);
      (function (index) {
        row.addEventListener('click', function () {
          IMESkill.haptic();
          selIndex = selIndex === index ? -1 : index;
          renderSteps();
        });
      })(i);
      box.appendChild(row);
    }
    if (!hasContent) {
      var hint = document.createElement('div');
      hint.id = 'empty-hint';
      hint.textContent = '点「➕ 步骤」开始搭建流程：\n每一步起个名字即可，名字相同会自动连成同一个节点；「🔀 分支」在选中的步骤上加条件走向（自动变菱形判断框）。也可切到「文本」直接书写。';
      hint.style.whiteSpace = 'pre-wrap';
      box.appendChild(hint);
    }
  }

  function renderWarnings() {
    var msgs = [];
    for (var i = 0; i < model.warnings.length; i++) msgs.push('⚠ ' + model.warnings[i].msg);
    el.warn.textContent = msgs.join('\n');
    el.warn.style.whiteSpace = 'pre-wrap';
  }

  // ===== 内联表单（步骤/分支录入，输入路由打字）=====

  var formMode = null; // 'step' | 'branch'

  function openForm(mode) {
    formMode = mode;
    el['cond-row'].className = 'row' + (mode === 'branch' ? '' : ' hidden');
    el['name-label'].textContent = mode === 'branch' ? '分支去向' : '步骤名称';
    el['cond-input'].value = '';
    el['name-input'].value = '';
    el.form.className = 'on';
    routeTo(mode === 'branch' ? 'cond-input' : 'name-input');
  }

  function closeForm() {
    formMode = null;
    el.form.className = '';
    releaseRouting();
  }

  var routedId = null;

  function routeTo(id) {
    IMESkill.input.requestFocus(id).then(function () {
      if (routedId) el[routedId].classList.remove('routing');
      routedId = id;
      el[id].classList.add('routing');
    }).catch(function () { });
  }

  function releaseRouting() {
    if (routedId) el[routedId].classList.remove('routing');
    routedId = null;
    IMESkill.input.releaseFocus().catch(function () { });
  }

  el['cond-input'].addEventListener('click', function () { routeTo('cond-input'); });
  el['name-input'].addEventListener('click', function () { routeTo('name-input'); });
  el['form-cancel'].addEventListener('click', function () { IMESkill.haptic(); closeForm(); });

  el['form-ok'].addEventListener('click', function () {
    IMESkill.haptic();
    var name = el['name-input'].value.trim();
    if (!name) return;
    var anchor = anchorIndex();
    var source = anchor >= 0 ? endLabelOf(model.lineInfos[anchor]) : null;
    if (formMode === 'branch') {
      if (anchor < 0) return;
      var cond = el['cond-input'].value.trim();
      insertLine(anchor, '? ' + cond + ' -> ' + name);
    } else {
      // 显式来源（可读性优先）；空文档 / 锚点无末端节点时退化为独立节点行
      insertLine(anchor, source ? source + ' -> ' + name : name);
    }
    closeForm();
  });

  // ===== 可视化工具条 =====

  el['btn-step'].addEventListener('click', function () { IMESkill.haptic(); openForm('step'); });

  el['btn-branch'].addEventListener('click', function () {
    IMESkill.haptic();
    if (anchorIndex() < 0) {
      el.steps.scrollTop = 0;
      return; // 空文档没有可挂分支的节点
    }
    openForm('branch');
  });

  el['btn-del'].addEventListener('click', function () {
    IMESkill.haptic();
    var anchor = anchorIndex();
    if (anchor < 0) return;
    lines.splice(anchor, 1);
    selIndex = -1;
    setText(pseudoText());
  });

  el['btn-clear'].addEventListener('click', function () {
    IMESkill.haptic();
    if (!clearArmed) {
      clearArmed = true;
      el['btn-clear'].textContent = '再按确认';
      setTimeout(function () {
        clearArmed = false;
        el['btn-clear'].textContent = '🗑 清空';
      }, 2000);
      return;
    }
    clearArmed = false;
    el['btn-clear'].textContent = '🗑 清空';
    selIndex = -1;
    setText('');
  });

  // ===== 文本模式 =====

  el['pseudo-text'].addEventListener('click', function () { routeTo('pseudo-text'); });

  el['pseudo-text'].addEventListener('input', function () {
    // 路由注入 / 工具条插入均派发 input：同步回单一事实源（不回写 textarea 防光标跳动）
    setText(el['pseudo-text'].value, true);
  });

  function insertAtCursor(text) {
    var area = el['pseudo-text'];
    var start = typeof area.selectionStart === 'number' ? area.selectionStart : area.value.length;
    var end = typeof area.selectionEnd === 'number' ? area.selectionEnd : start;
    area.value = area.value.slice(0, start) + text + area.value.slice(end);
    var pos = start + text.length;
    try { area.setSelectionRange(pos, pos); } catch (e) { }
    setText(area.value, true);
  }

  el['ins-arrow'].addEventListener('click', function () { IMESkill.haptic(); insertAtCursor(' -> '); });
  el['ins-branch'].addEventListener('click', function () { IMESkill.haptic(); insertAtCursor('? '); });
  el['ins-comma'].addEventListener('click', function () { IMESkill.haptic(); insertAtCursor(','); });
  el['ins-newline'].addEventListener('click', function () { IMESkill.haptic(); insertAtCursor('\n'); });

  // ===== 页签切换 =====

  function switchTab(tab) {
    if (tab === currentTab) return;
    var leaving = currentTab;
    currentTab = tab;
    ['edit', 'text', 'preview'].forEach(function (name) {
      el['tab-' + name].className = name === tab ? 'on' : '';
      el['view-' + name].className = name === tab ? 'view on' : 'view';
    });
    closeForm();
    releaseRouting();
    if (tab === 'preview') {
      el['out-hint'].textContent = model.nodes.length ? '' : '还没有内容，先在「编辑」里添加步骤';
      // 收缩输入法界面腾出展示空间；等布局稳定后再按新尺寸绘制
      IMESkill.ui.setExpanded(false).catch(function () { });
      setTimeout(drawPreview, 120);
    } else if (leaving === 'preview') {
      IMESkill.ui.setExpanded(true).catch(function () { });
    }
    if (tab === 'edit') renderSteps();
    if (tab === 'text') el['pseudo-text'].value = pseudoText();
  }

  el['tab-edit'].addEventListener('click', function () { IMESkill.haptic(); switchTab('edit'); });
  el['tab-text'].addEventListener('click', function () { IMESkill.haptic(); switchTab('text'); });
  el['tab-preview'].addEventListener('click', function () { IMESkill.haptic(); switchTab('preview'); });

  function drawPreview() {
    if (currentTab !== 'preview') return;
    FlowRender.drawPreview(el['preview-canvas'], model);
  }

  window.addEventListener('resize', function () { setTimeout(drawPreview, 60); });

  // ===== 输出 =====

  function hint(text) { el['out-hint'].textContent = text; }

  el['btn-code'].addEventListener('click', function () {
    IMESkill.haptic();
    if (!model.nodes.length) { hint('还没有内容可上屏'); return; }
    var code = '```mermaid\n' + FlowParser.toMermaid(model) + '\n```';
    IMESkill.sendText(code).catch(function (e) { hint('上屏失败：' + e.message); });
  });

  el['btn-image'].addEventListener('click', function () {
    IMESkill.haptic();
    if (!model.nodes.length) { hint('还没有内容可发送'); return; }
    var url = FlowRender.exportPng(model, MAX_IMAGE_BASE64);
    if (!url) { hint('图表过大，请拆分后再导出'); return; }
    hint('发送中…');
    IMESkill.image.send(url).then(function () {
      hint('已发送到输入框');
    }).catch(function (e) {
      hint(e.message + '（可改用「存相册」）');
    });
  });

  el['btn-gallery'].addEventListener('click', function () {
    IMESkill.haptic();
    if (!model.nodes.length) { hint('还没有内容可保存'); return; }
    var url = FlowRender.exportPng(model, MAX_IMAGE_BASE64);
    if (!url) { hint('图表过大，请拆分后再导出'); return; }
    hint('保存中…');
    IMESkill.image.saveToGallery(url).then(function () {
      hint('已保存到相册（图片 › 字由输入法）');
    }).catch(function (e) {
      hint('保存失败：' + e.message);
    });
  });

  // ===== 启动：拉高面板 + 恢复草稿 =====

  // 面板高度自定义为 API v4 能力（manifest 已声明 min_host_api: 4）；
  // 异常时静默降级为默认紧凑高度，不影响技能可用性
  IMESkill.ui.setPanelHeight(PANEL_HEIGHT_RATIO).catch(function () { });

  IMESkill.storage.get(DRAFT_KEY).then(function (draft) {
    setText(draft || '');
  }).catch(function () {
    setText('');
  });
})();
