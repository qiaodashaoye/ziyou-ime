/**
 * 诗词助手技能 —— 逻辑脚本
 *
 * 与宿主交互（IMESkill Bridge API v2）：
 * - input.requestFocus / releaseFocus：键盘上屏文本路由进 #poem-input（needs_input）
 * - sendText：单句/整首发送到当前应用输入框（面板自动收起）
 * - storage：查询历史（最近 5 条）
 * - haptic：按钮震动反馈
 *
 * 数据内置离线（poems.js 由 build_poems.py 从 chinese-poetry/MIT 生成），
 * 沙箱 CSP 禁 eval，全部显式逻辑。
 */
'use strict';

var HISTORY_KEY = 'query_history';
var HISTORY_MAX = 5;
var MAX_RESULTS = 10;

var poemInput = document.getElementById('poem-input');
var resultsEl = document.getElementById('results');
var historyEl = document.getElementById('history');

// ===== 输入路由（needs_input：面板提升，下方键盘打字进搜索框）=====

poemInput.addEventListener('click', function () {
  IMESkill.input.requestFocus('poem-input').then(function () {
    poemInput.classList.add('routing');
  }).catch(function (e) {
    showError('输入路由不可用：' + e.message);
  });
});

function releaseRouting() {
  poemInput.classList.remove('routing');
  IMESkill.input.releaseFocus();
}

// ===== 检索 =====

/** 关键词匹配：题目/作者精确或包含、任一句子包含（去空白后比较） */
function searchPoems(raw) {
  var q = String(raw).replace(/\s/g, '');
  if (!q) return [];
  var hits = [];
  for (var i = 0; i < POEMS.length && hits.length < MAX_RESULTS * 3; i++) {
    var p = POEMS[i];
    if (p.t.indexOf(q) >= 0 || p.a.indexOf(q) >= 0) {
      hits.push(p);
      continue;
    }
    for (var j = 0; j < p.l.length; j++) {
      if (p.l[j].indexOf(q) >= 0) { hits.push(p); break; }
    }
  }
  return hits.slice(0, MAX_RESULTS);
}

/** 整首发送的标点还原：两句一联「，」收「。」（词句奇数句末以句号收束） */
function punctuate(lines) {
  var out = '';
  for (var i = 0; i < lines.length; i++) {
    var isCoupletEnd = (i % 2 === 1) || (i === lines.length - 1);
    out += lines[i] + (isCoupletEnd ? '。' : '，');
  }
  return out;
}

function showError(message) {
  resultsEl.textContent = '';
  var div = document.createElement('div');
  div.className = 'err';
  div.textContent = message;
  resultsEl.appendChild(div);
}

function renderPoems(list) {
  resultsEl.textContent = '';
  if (!list.length) {
    showError('未找到相关诗词。试试名句关键词（如「明月」「春风」）、题目或作者。');
    return;
  }
  list.forEach(function (p) {
    var card = document.createElement('div');
    card.className = 'card';

    var head = document.createElement('div');
    head.className = 'head';
    var left = document.createElement('span');
    var title = document.createElement('span');
    title.className = 'title';
    title.textContent = p.t;
    var author = document.createElement('span');
    author.className = 'author';
    author.textContent = p.a;
    left.appendChild(title);
    left.appendChild(author);
    var sendAll = document.createElement('button');
    sendAll.className = 'send-all';
    sendAll.textContent = '整首';
    sendAll.addEventListener('click', function () {
      IMESkill.haptic();
      IMESkill.sendText(punctuate(p.l)).catch(function (e) {
        showError('发送失败：' + e.message);
      });
    });
    head.appendChild(left);
    head.appendChild(sendAll);
    card.appendChild(head);

    p.l.forEach(function (line) {
      var el = document.createElement('span');
      el.className = 'line';
      el.textContent = line;
      el.addEventListener('click', function () {
        IMESkill.haptic();
        // 单句发送不带标点：交给候选栏诗词联想链继续接龙
        IMESkill.sendText(line).catch(function (e) {
          showError('发送失败：' + e.message);
        });
      });
      card.appendChild(el);
    });
    resultsEl.appendChild(card);
  });
}

function query() {
  var raw = poemInput.value.trim();
  if (!raw) {
    showError('请输入诗句、题目或作者（如：明月、静夜思、李白）');
    return;
  }
  renderPoems(searchPoems(raw));
  saveHistory(raw);
}

document.getElementById('go').addEventListener('click', function () {
  IMESkill.haptic();
  releaseRouting();
  query();
});

// ===== 查询历史（storage 权限，最近 5 条）=====

function renderHistory(list) {
  historyEl.textContent = '';
  list.forEach(function (q) {
    var btn = document.createElement('button');
    btn.className = 'ghost';
    btn.textContent = q;
    btn.addEventListener('click', function () {
      IMESkill.haptic();
      poemInput.value = q;
      releaseRouting();
      query();
    });
    historyEl.appendChild(btn);
  });
}

function saveHistory(q) {
  loadHistory().then(function (list) {
    var next = [q].concat(list.filter(function (x) { return x !== q; }))
      .slice(0, HISTORY_MAX);
    renderHistory(next);
    return IMESkill.storage.set(HISTORY_KEY, JSON.stringify(next));
  }).catch(function () { /* 历史仅为便利功能，失败静默 */ });
}

function loadHistory() {
  return IMESkill.storage.get(HISTORY_KEY).then(function (saved) {
    try {
      var list = JSON.parse(saved);
      return Array.isArray(list) ? list : [];
    } catch (e) { return []; }
  });
}

// 启动：恢复历史 + 默认展示「明月」示例结果，首屏即可体验
loadHistory().then(renderHistory).catch(function () { });
renderPoems(searchPoems('明月'));
