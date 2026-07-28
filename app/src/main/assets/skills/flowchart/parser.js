'use strict';
/**
 * 伪代码 → 流程图模型 → Mermaid flowchart TD 翻译层（纯函数，无 DOM 依赖）。
 *
 * 语法 v1（面向非技术用户，冻结）：
 * - 普通行：`节点名` 或 `A -> B -> C` 链式连线；行首 `->` 表示从上一行末端节点继续；
 * - 分支行：`? 条件 -> 目标`，归属上方最近一条普通行的末端节点（该节点自动升级为菱形），
 *   条件译为边标签；
 * - 逗号分隔 = 同一节点的多行文字（译为 <br/>）；相同文字 = 同一节点（自动合并连线）；
 * - 缩进纯视觉、解析忽略；「开始」「结束」节点译为胶囊形；
 * - 节点首次出现自动创建（不报错）；孤立节点只给软提示（warnings）。
 * - 容错：全角箭头（→ / －＞）与全角问号（？）自动归一。
 */
var FlowParser = (function () {

  /** 逗号（含全角）切成多行文字，去空段；返回归一化标签（\n 分隔） */
  function normalizeLabel(text) {
    var parts = String(text).split(/[,，]/);
    var lines = [];
    for (var i = 0; i < parts.length; i++) {
      var s = parts[i].trim();
      if (s) lines.push(s);
    }
    return lines.join('\n');
  }

  /** 行内符号容错归一：全角箭头/问号 → 半角 */
  function normalizeLine(line) {
    return line
      .replace(/－＞/g, '->')
      .replace(/—>/g, '->')
      .replace(/→/g, '->')
      .replace(/？/g, '?');
  }

  /**
   * 解析整篇伪代码。
   * @returns {nodes, edges, warnings, lineInfos}
   *   nodes: [{id, label, lines, shape:'rect'|'diamond'|'terminal'}]
   *   edges: [{from, to, label}]
   *   warnings: [{line, msg}]（软提示，不阻断翻译）
   *   lineInfos: 与原文行号对齐的展示信息（可视化列表用）
   */
  function parse(text) {
    var nodes = [];
    var byLabel = {};
    var edges = [];
    var warnings = [];
    var lineInfos = [];
    var lastNode = null; // 最近一条普通行的末端节点（? 行归属锚点）

    function nodeFor(labelText) {
      var label = normalizeLabel(labelText);
      if (!label) return null;
      var node = byLabel[label];
      if (!node) {
        node = {
          id: 'N' + (nodes.length + 1),
          label: label,
          lines: label.split('\n'),
          shape: (label === '开始' || label === '结束') ? 'terminal' : 'rect'
        };
        nodes.push(node);
        byLabel[label] = node;
      }
      return node;
    }

    var rawLines = String(text).split('\n');
    for (var i = 0; i < rawLines.length; i++) {
      var line = normalizeLine(rawLines[i].trim());
      if (!line) { lineInfos.push({ type: 'empty' }); continue; }

      if (line.charAt(0) === '?') {
        // ── 分支行 ──
        var rest = line.slice(1).trim();
        if (!lastNode) {
          warnings.push({ line: i, msg: '第' + (i + 1) + '行：分支前需要先有一个节点' });
          lineInfos.push({ type: 'error', raw: rawLines[i].trim() });
          continue;
        }
        var arrowAt = rest.indexOf('->');
        var cond = arrowAt < 0 ? '' : rest.slice(0, arrowAt).trim();
        var targetText = arrowAt < 0 ? rest : rest.slice(arrowAt + 2).trim();
        var target = nodeFor(targetText);
        if (!target) {
          warnings.push({ line: i, msg: '第' + (i + 1) + '行：分支缺少目标节点' });
          lineInfos.push({ type: 'error', raw: rawLines[i].trim() });
          continue;
        }
        if (lastNode.shape === 'rect') lastNode.shape = 'diamond';
        edges.push({ from: lastNode.id, to: target.id, label: cond });
        lineInfos.push({ type: 'branch', from: lastNode, to: target, cond: cond });
      } else {
        // ── 普通行：单节点 或 链式连线 ──
        var parts = line.split('->');
        for (var t = 0; t < parts.length; t++) parts[t] = parts[t].trim();
        if (parts.length === 1) {
          var single = nodeFor(parts[0]);
          if (!single) { lineInfos.push({ type: 'empty' }); continue; }
          lastNode = single;
          lineInfos.push({ type: 'node', node: single });
        } else {
          var prev = null;
          var chain = [];
          var broken = false;
          for (var p = 0; p < parts.length; p++) {
            var node;
            if (p === 0 && parts[0] === '') {
              node = lastNode; // 行首 -> 从上一行末端节点继续
              if (!node) {
                warnings.push({ line: i, msg: '第' + (i + 1) + '行：行首 -> 前面需要先有一个节点' });
                broken = true;
                break;
              }
            } else {
              node = nodeFor(parts[p]);
            }
            if (!node) {
              warnings.push({ line: i, msg: '第' + (i + 1) + '行：箭头两侧不能为空' });
              continue;
            }
            if (prev && prev !== node) edges.push({ from: prev.id, to: node.id, label: '' });
            chain.push(node);
            prev = node;
          }
          if (broken || !prev) {
            lineInfos.push({ type: 'error', raw: rawLines[i].trim() });
            continue;
          }
          lastNode = prev;
          lineInfos.push({ type: 'chain', nodes: chain });
        }
      }
    }

    // 孤立节点软提示（不阻断：单节点文档不提示）
    if (nodes.length > 1) {
      var connected = {};
      for (var e = 0; e < edges.length; e++) {
        connected[edges[e].from] = true;
        connected[edges[e].to] = true;
      }
      for (var n = 0; n < nodes.length; n++) {
        if (!connected[nodes[n].id]) {
          warnings.push({ line: -1, msg: '「' + nodes[n].lines[0] + '」没有连线（孤立节点）' });
        }
      }
    }
    return { nodes: nodes, edges: edges, warnings: warnings, lineInfos: lineInfos };
  }

  /** 节点文字转 Mermaid 安全形式（引号包裹 + 转义） */
  function escapeLabel(label) {
    return label.replace(/"/g, '#quot;');
  }

  /** 模型 → 标准 Mermaid flowchart TD 代码 */
  function toMermaid(model) {
    var out = ['flowchart TD'];
    var i;
    for (i = 0; i < model.nodes.length; i++) {
      var node = model.nodes[i];
      var text = escapeLabel(node.lines.join('<br/>'));
      if (node.shape === 'diamond') out.push('    ' + node.id + '{"' + text + '"}');
      else if (node.shape === 'terminal') out.push('    ' + node.id + '(["' + text + '"])');
      else out.push('    ' + node.id + '["' + text + '"]');
    }
    for (i = 0; i < model.edges.length; i++) {
      var edge = model.edges[i];
      if (edge.label) {
        out.push('    ' + edge.from + ' -->|' + edge.label.replace(/\|/g, '/') + '| ' + edge.to);
      } else {
        out.push('    ' + edge.from + ' --> ' + edge.to);
      }
    }
    return out.join('\n');
  }

  /** 开发自检（selftest.html 调用；返回失败信息数组，空 = 全部通过） */
  function selfTest() {
    var failures = [];
    function assert(name, cond) { if (!cond) failures.push(name); }

    // 用例 1：活动报名示例（方案冻结样例）全量翻译
    var demo = [
      '活动发布',
      '  -> 报名渠道判定',
      '     ? 站内小程序 -> 匹配梯度',
      '     ? 美团 -> 原价报名,不占早鸟名额',
      '  匹配梯度 -> 确认占位',
      '  确认占位 -> 支付判定',
      '     ? 是 -> 占用名额',
      '     ? 否 -> 取消报名,释放名额',
      '  占用名额 -> 查看早鸟进度'
    ].join('\n');
    var m = parse(demo);
    assert('demo 节点数=9', m.nodes.length === 9);
    assert('demo 边数=8', m.edges.length === 8);
    assert('demo 无警告', m.warnings.length === 0);
    var byLabel = {};
    for (var i = 0; i < m.nodes.length; i++) byLabel[m.nodes[i].label] = m.nodes[i];
    assert('渠道判定为菱形', byLabel['报名渠道判定'] && byLabel['报名渠道判定'].shape === 'diamond');
    assert('支付判定为菱形', byLabel['支付判定'] && byLabel['支付判定'].shape === 'diamond');
    assert('逗号并入同节点', !!byLabel['原价报名\n不占早鸟名额']);
    var code = toMermaid(m);
    assert('输出 TD 头', code.indexOf('flowchart TD') === 0);
    assert('分支边带标签', code.indexOf('-->|站内小程序|') >= 0);
    assert('多行标签 br', code.indexOf('原价报名<br/>不占早鸟名额') >= 0);

    // 用例 2：开始/结束胶囊形 + 相同标签合并
    var m2 = parse('开始 -> 处理\n处理 -> 结束');
    assert('开始为胶囊', m2.nodes[0].shape === 'terminal');
    assert('相同标签合并', m2.nodes.length === 3);

    // 用例 3：容错——全角符号、孤立节点提示、空分支归属
    var m3 = parse('甲 → 乙\n？条件 -> 丙\n孤岛');
    assert('全角归一后有 3 条边或 2 条边', m3.edges.length === 2);
    assert('孤立节点提示', m3.warnings.length === 1);
    var m4 = parse('? 悬空 -> 某处');
    assert('悬空分支警告', m4.warnings.length === 1);

    // 用例 4：引号转义
    var m5 = parse('带"引号" -> 下一步');
    assert('引号转义', toMermaid(m5).indexOf('#quot;') >= 0);
    return failures;
  }

  return {
    parse: parse,
    toMermaid: toMermaid,
    normalizeLabel: normalizeLabel,
    selfTest: selfTest
  };
})();
