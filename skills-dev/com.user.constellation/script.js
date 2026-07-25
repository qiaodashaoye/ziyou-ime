/**
 * 星座查询技能 —— 逻辑脚本
 *
 * 与宿主交互（IMESkill Bridge API v2）：
 * - input.requestFocus / releaseFocus：把键盘上屏文本路由进 #sign-input（needs_input）
 * - ui.setExpanded：查询成功后传 false 收缩整个输入法界面（键盘/编码区/候选区缩回），
 *   面板接管其空间展示介绍（窗口总高不变）；true 完整恢复
 * - storage：持久化查询历史（最近 5 条）
 * - sendText：星座介绍发送到当前应用输入框（面板自动收起）
 * - haptic：按钮震动反馈
 *
 * 数据内置离线（无 network 权限），沙箱 CSP 禁 eval，全部显式逻辑。
 */
'use strict';

// ===== 十二星座离线数据 =====
var ZODIAC = [
  { name: '白羊座', icon: '♈', date: '3.21-4.19', element: '火象', planet: '火星',
    traits: '热情直率、行动力强，天生的开拓者与急先锋。喜欢挑战和竞争，讨厌拖泥带水。',
    weakness: '急躁易冲动，缺乏耐心，三分钟热度。',
    lucky: '幸运色：红色｜幸运数字：9' },
  { name: '金牛座', icon: '♉', date: '4.20-5.20', element: '土象', planet: '金星',
    traits: '踏实稳重、审美出众，对生活品质有执着追求。理财能手，值得信赖的伙伴。',
    weakness: '固执保守，不易变通，占有欲较强。',
    lucky: '幸运色：绿色｜幸运数字：6' },
  { name: '双子座', icon: '♊', date: '5.21-6.21', element: '风象', planet: '水星',
    traits: '机智灵活、口才极佳，好奇心旺盛的信息达人。适应力强，社交场上的开心果。',
    weakness: '善变多虑，注意力分散，难以专一。',
    lucky: '幸运色：黄色｜幸运数字：5' },
  { name: '巨蟹座', icon: '♋', date: '6.22-7.22', element: '水象', planet: '月亮',
    traits: '温柔体贴、重视家庭，情感细腻的守护者。记忆力强，直觉敏锐，念旧且忠诚。',
    weakness: '多愁善感，容易情绪化，缺乏安全感。',
    lucky: '幸运色：银白色｜幸运数字：2' },
  { name: '狮子座', icon: '♌', date: '7.23-8.22', element: '火象', planet: '太阳',
    traits: '自信大方、天生领袖，光芒四射的舞台中心。慷慨仗义，保护欲强，重情重义。',
    weakness: '爱面子，自尊心过强，有时过于强势。',
    lucky: '幸运色：金色｜幸运数字：1' },
  { name: '处女座', icon: '♍', date: '8.23-9.22', element: '土象', planet: '水星',
    traits: '细致严谨、追求完美，可靠的分析与执行专家。观察入微，做事有条不紊。',
    weakness: '吹毛求疵，容易焦虑，对自己和他人要求过高。',
    lucky: '幸运色：灰色｜幸运数字：7' },
  { name: '天秤座', icon: '♎', date: '9.23-10.23', element: '风象', planet: '金星',
    traits: '优雅得体、公正平和，天生的外交官。审美一流，擅长协调关系，人缘极佳。',
    weakness: '选择困难，优柔寡断，过分在意他人眼光。',
    lucky: '幸运色：粉蓝色｜幸运数字：8' },
  { name: '天蝎座', icon: '♏', date: '10.24-11.22', element: '水象', planet: '冥王星',
    traits: '深沉专注、洞察力惊人，意志坚定的策略家。爱恨分明，一旦认定便全力以赴。',
    weakness: '占有欲与戒心强，记仇，不轻易信任他人。',
    lucky: '幸运色：暗红色｜幸运数字：4' },
  { name: '射手座', icon: '♐', date: '11.23-12.21', element: '火象', planet: '木星',
    traits: '乐观开朗、热爱自由，永远在路上的冒险家。心态豁达，幽默风趣，视野开阔。',
    weakness: '粗心大意，缺乏耐性，害怕束缚与承诺。',
    lucky: '幸运色：紫色｜幸运数字：3' },
  { name: '摩羯座', icon: '♑', date: '12.22-1.19', element: '土象', planet: '土星',
    traits: '沉稳务实、自律极强，目标明确的实干家。责任感重，越挫越勇，大器晚成。',
    weakness: '不苟言笑，压抑情感，工作狂倾向。',
    lucky: '幸运色：深棕色｜幸运数字：8' },
  { name: '水瓶座', icon: '♒', date: '1.20-2.18', element: '风象', planet: '天王星',
    traits: '独立创新、思想前卫，特立独行的智慧型人格。博爱友善，点子层出不穷。',
    weakness: '疏离感强，难以捉摸，过分理性显得冷漠。',
    lucky: '幸运色：蓝色｜幸运数字：4' },
  { name: '双鱼座', icon: '♓', date: '2.19-3.20', element: '水象', planet: '海王星',
    traits: '浪漫多情、想象力丰富，温柔的艺术家灵魂。共情力强，善解人意，心地柔软。',
    weakness: '逃避现实，意志薄弱，容易多愁善感。',
    lucky: '幸运色：海蓝色｜幸运数字：7' }
];

/** 别名映射：无「座」简称 / 常见别称 → 标准名 */
var ALIASES = { '白羊': '白羊座', '牡羊': '白羊座', '牡羊座': '白羊座',
  '金牛': '金牛座', '双子': '双子座', '巨蟹': '巨蟹座', '狮子': '狮子座',
  '处女': '处女座', '室女': '处女座', '室女座': '处女座', '天秤': '天秤座',
  '天平': '天秤座', '天平座': '天秤座', '天蝎': '天蝎座', '射手': '射手座',
  '人马': '射手座', '人马座': '射手座', '摩羯': '摩羯座', '山羊': '摩羯座',
  '山羊座': '摩羯座', '水瓶': '水瓶座', '宝瓶': '水瓶座', '宝瓶座': '水瓶座',
  '双鱼': '双鱼座' };

var HISTORY_KEY = 'query_history';
var HISTORY_MAX = 5;

var signInput = document.getElementById('sign-input');
var resultEl = document.getElementById('result');
var sendBtn = document.getElementById('send');
var historyEl = document.getElementById('history');

/** 当前可发送的介绍文本（null = 无结果） */
var currentText = null;

// ===== 输入路由（needs_input：面板提升至编码区上方，下方键盘打字）=====

signInput.addEventListener('click', function () {
  IMESkill.input.requestFocus('sign-input').then(function () {
    signInput.classList.add('routing');  // 视觉焦点态：键盘文本正路由到本框
  }).catch(function (e) {
    showError('输入路由不可用：' + e.message);
  });
});

function releaseRouting() {
  signInput.classList.remove('routing');
  IMESkill.input.releaseFocus();
}

// ===== 查询 =====

/** 名称归一：去空白，匹配标准名或别名 */
function findZodiac(raw) {
  var name = String(raw).replace(/\s/g, '');
  if (!name) return null;
  name = ALIASES[name] || name;
  for (var i = 0; i < ZODIAC.length; i++) {
    if (ZODIAC[i].name === name) return ZODIAC[i];
  }
  return null;
}

function buildText(z) {
  return z.icon + ' ' + z.name + '（' + z.date + '）｜' + z.element + '星座，守护星' +
    z.planet + '。' + z.traits + '短板：' + z.weakness + z.lucky;
}

function showError(message) {
  currentText = null;
  sendBtn.classList.remove('show');
  resultEl.innerHTML = '';
  var div = document.createElement('div');
  div.className = 'err';
  div.textContent = message;
  resultEl.appendChild(div);
  // 出错时恢复完整输入法界面，方便用户重新输入
  IMESkill.ui.setExpanded(true).catch(function () { });
}

function showZodiac(z) {
  currentText = buildText(z);
  resultEl.innerHTML = '';
  var title = document.createElement('div');
  title.className = 'title';
  title.textContent = z.icon + ' ' + z.name + '（' + z.date + '）';
  var body = document.createElement('div');
  body.textContent = z.element + '星座 · 守护星' + z.planet + '\n' +
    '特质：' + z.traits + '\n短板：' + z.weakness + '\n' + z.lucky;
  resultEl.appendChild(title);
  resultEl.appendChild(body);
  sendBtn.classList.add('show');
  // 查询完成：setExpanded(false) 收缩整个输入法界面（键盘/编码区/候选区一并缩回），
  // 面板接管全部空间展示完整介绍（IME 窗口总高不变；
  // 再次点输入框 requestFocus 时宿主会自动 setExpanded(true) 恢复完整界面）
  IMESkill.ui.setExpanded(false).catch(function () { });
}

function query() {
  var raw = signInput.value.trim();
  if (!raw) {
    showError('请先输入星座名称（如：白羊、狮子座）');
    return;
  }
  var zodiac = findZodiac(raw);
  if (!zodiac) {
    showError('未找到「' + raw + '」。支持的星座：\n' +
      ZODIAC.map(function (z) { return z.name; }).join('、'));
    return;
  }
  showZodiac(zodiac);
  saveHistory(zodiac.name);
}

document.getElementById('go').addEventListener('click', function () {
  IMESkill.haptic();
  releaseRouting();   // 查询后收回路由，避免误把后续按键打进输入框
  query();
});

// ===== 发送（sendText 直达当前应用输入框并自动收起面板）=====

sendBtn.addEventListener('click', function () {
  if (!currentText) return;
  IMESkill.haptic();
  IMESkill.sendText(currentText).catch(function (e) {
    showError('发送失败：' + e.message);
  });
});

// ===== 查询历史（storage 权限，最近 5 条）=====

function renderHistory(list) {
  historyEl.innerHTML = '';
  list.forEach(function (name) {
    var btn = document.createElement('button');
    btn.textContent = name;
    btn.addEventListener('click', function () {
      IMESkill.haptic();
      signInput.value = name;
      releaseRouting();
      query();
    });
    historyEl.appendChild(btn);
  });
}

function saveHistory(name) {
  loadHistory().then(function (list) {
    var next = [name].concat(list.filter(function (n) { return n !== name; }))
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

// 启动：恢复查询历史
loadHistory().then(renderHistory).catch(function () { });
