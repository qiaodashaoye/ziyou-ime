'use strict';
/**
 * 流程图 Canvas 自绘渲染层（TD 方向分层布局，接近 Mermaid 视觉但非 100% 还原）。
 *
 * 布局：按边迭代松弛计算节点深度（环安全，最多 N 轮），同层横向均布、层间纵向排列；
 * 边为源底部 → 目标顶部的直线 + 箭头，边标签绘制在中点（白底衬托）。
 * 导出：offscreen canvas 按 scale 放大绘制，白色底（聊天场景可读），返回 dataURL。
 */
var FlowRender = (function () {

  var FONT_SIZE = 13;
  var LINE_H = 18;
  var PAD_X = 14;
  var PAD_Y = 9;
  var H_GAP = 26;
  var V_GAP = 46;
  var MARGIN = 20;

  var COLORS = {
    bg: '#ffffff',
    rectFill: '#e3f2fd', rectStroke: '#1976d2',
    diamondFill: '#fff8e1', diamondStroke: '#f9a825',
    terminalFill: '#e8f5e9', terminalStroke: '#388e3c',
    text: '#263238',
    edge: '#607d8b',
    edgeLabel: '#455a64'
  };

  /** 深度计算：depth[to] >= depth[from]+1，迭代 N 轮（环安全截断） */
  function computeDepths(model) {
    var depth = {};
    var i;
    for (i = 0; i < model.nodes.length; i++) depth[model.nodes[i].id] = 0;
    var rounds = model.nodes.length + 1;
    for (var r = 0; r < rounds; r++) {
      var changed = false;
      for (i = 0; i < model.edges.length; i++) {
        var e = model.edges[i];
        if (depth[e.to] < depth[e.from] + 1 && depth[e.to] <= model.nodes.length) {
          depth[e.to] = depth[e.from] + 1;
          changed = true;
        }
      }
      if (!changed) break;
    }
    return depth;
  }

  /** 计算布局：返回 {items:{id:{node,x,y,w,h}}, width, height}（x/y 为节点中心） */
  function layout(model, ctx) {
    ctx.font = FONT_SIZE + 'px sans-serif';
    var depth = computeDepths(model);
    var layers = [];
    var i, node;
    for (i = 0; i < model.nodes.length; i++) {
      node = model.nodes[i];
      var d = depth[node.id];
      if (!layers[d]) layers[d] = [];
      layers[d].push(node);
    }

    var items = {};
    var maxRowW = 0;
    var rows = [];
    for (var L = 0; L < layers.length; L++) {
      var layer = layers[L] || [];
      var rowW = 0, rowH = 0;
      var sized = [];
      for (i = 0; i < layer.length; i++) {
        node = layer[i];
        var textW = 0;
        for (var t = 0; t < node.lines.length; t++) {
          textW = Math.max(textW, ctx.measureText(node.lines[t]).width);
        }
        var w = Math.max(64, textW + PAD_X * 2);
        var h = node.lines.length * LINE_H + PAD_Y * 2;
        if (node.shape === 'diamond') { w += 34; h += 16; }
        if (node.shape === 'terminal') { w += 10; }
        sized.push({ node: node, w: w, h: h });
        rowW += w;
        rowH = Math.max(rowH, h);
      }
      rowW += Math.max(0, layer.length - 1) * H_GAP;
      maxRowW = Math.max(maxRowW, rowW);
      rows.push({ sized: sized, rowW: rowW, rowH: rowH });
    }

    var width = maxRowW + MARGIN * 2;
    var y = MARGIN;
    for (var R = 0; R < rows.length; R++) {
      var row = rows[R];
      var x = (width - row.rowW) / 2;
      for (i = 0; i < row.sized.length; i++) {
        var s = row.sized[i];
        items[s.node.id] = {
          node: s.node,
          x: x + s.w / 2,
          y: y + row.rowH / 2,
          w: s.w,
          h: s.h
        };
        x += s.w + H_GAP;
      }
      y += row.rowH + V_GAP;
    }
    return { items: items, width: width, height: y - V_GAP + MARGIN };
  }

  function roundRect(ctx, x, y, w, h, r) {
    ctx.beginPath();
    ctx.moveTo(x + r, y);
    ctx.arcTo(x + w, y, x + w, y + h, r);
    ctx.arcTo(x + w, y + h, x, y + h, r);
    ctx.arcTo(x, y + h, x, y, r);
    ctx.arcTo(x, y, x + w, y, r);
    ctx.closePath();
  }

  function drawNode(ctx, item) {
    var node = item.node;
    var x = item.x, y = item.y, w = item.w, h = item.h;
    ctx.lineWidth = 1.4;
    if (node.shape === 'diamond') {
      ctx.fillStyle = COLORS.diamondFill;
      ctx.strokeStyle = COLORS.diamondStroke;
      ctx.beginPath();
      ctx.moveTo(x, y - h / 2);
      ctx.lineTo(x + w / 2, y);
      ctx.lineTo(x, y + h / 2);
      ctx.lineTo(x - w / 2, y);
      ctx.closePath();
      ctx.fill();
      ctx.stroke();
    } else if (node.shape === 'terminal') {
      ctx.fillStyle = COLORS.terminalFill;
      ctx.strokeStyle = COLORS.terminalStroke;
      roundRect(ctx, x - w / 2, y - h / 2, w, h, h / 2);
      ctx.fill();
      ctx.stroke();
    } else {
      ctx.fillStyle = COLORS.rectFill;
      ctx.strokeStyle = COLORS.rectStroke;
      roundRect(ctx, x - w / 2, y - h / 2, w, h, 6);
      ctx.fill();
      ctx.stroke();
    }
    ctx.fillStyle = COLORS.text;
    ctx.font = FONT_SIZE + 'px sans-serif';
    ctx.textAlign = 'center';
    ctx.textBaseline = 'middle';
    var lines = node.lines;
    var startY = y - (lines.length - 1) * LINE_H / 2;
    for (var i = 0; i < lines.length; i++) {
      ctx.fillText(lines[i], x, startY + i * LINE_H);
    }
  }

  function drawArrowHead(ctx, x, y, angle) {
    var size = 7;
    ctx.beginPath();
    ctx.moveTo(x, y);
    ctx.lineTo(x - size * Math.cos(angle - 0.42), y - size * Math.sin(angle - 0.42));
    ctx.lineTo(x - size * Math.cos(angle + 0.42), y - size * Math.sin(angle + 0.42));
    ctx.closePath();
    ctx.fillStyle = COLORS.edge;
    ctx.fill();
  }

  function drawEdge(ctx, from, to, label) {
    var sx, sy, tx, ty;
    if (to.y > from.y) {
      sx = from.x; sy = from.y + from.h / 2;
      tx = to.x; ty = to.y - to.h / 2;
    } else if (to.y < from.y) {
      sx = from.x; sy = from.y - from.h / 2;
      tx = to.x; ty = to.y + to.h / 2;
    } else {
      // 同层横向：左右边缘相接
      var dir = to.x > from.x ? 1 : -1;
      sx = from.x + dir * from.w / 2; sy = from.y;
      tx = to.x - dir * to.w / 2; ty = to.y;
    }
    ctx.strokeStyle = COLORS.edge;
    ctx.lineWidth = 1.2;
    ctx.beginPath();
    ctx.moveTo(sx, sy);
    ctx.lineTo(tx, ty);
    ctx.stroke();
    drawArrowHead(ctx, tx, ty, Math.atan2(ty - sy, tx - sx));

    if (label) {
      var mx = (sx + tx) / 2, my = (sy + ty) / 2;
      ctx.font = (FONT_SIZE - 2) + 'px sans-serif';
      var lw = ctx.measureText(label).width;
      ctx.fillStyle = COLORS.bg;
      ctx.fillRect(mx - lw / 2 - 4, my - 8, lw + 8, 16);
      ctx.fillStyle = COLORS.edgeLabel;
      ctx.textAlign = 'center';
      ctx.textBaseline = 'middle';
      ctx.fillText(label, mx, my);
    }
  }

  /** 在离屏 canvas 上按 scale 绘制整图，返回该 canvas */
  function renderToCanvas(model, scale) {
    var canvas = document.createElement('canvas');
    var ctx = canvas.getContext('2d');
    var box = layout(model, ctx);
    canvas.width = Math.max(1, Math.round(box.width * scale));
    canvas.height = Math.max(1, Math.round(box.height * scale));
    ctx = canvas.getContext('2d');
    ctx.scale(scale, scale);
    ctx.fillStyle = COLORS.bg;
    ctx.fillRect(0, 0, box.width, box.height);
    var i;
    for (i = 0; i < model.edges.length; i++) {
      var e = model.edges[i];
      var from = box.items[e.from], to = box.items[e.to];
      if (from && to) drawEdge(ctx, from, to, e.label);
    }
    for (var id in box.items) {
      if (box.items.hasOwnProperty(id)) drawNode(ctx, box.items[id]);
    }
    return canvas;
  }

  /**
   * 绘制预览到目标 canvas（fit-scale 自适应显示区域，devicePixelRatio 对齐防模糊）。
   */
  function drawPreview(targetCanvas, model) {
    var dpr = window.devicePixelRatio || 1;
    var cssW = targetCanvas.clientWidth || 300;
    var cssH = targetCanvas.clientHeight || 200;
    targetCanvas.width = Math.round(cssW * dpr);
    targetCanvas.height = Math.round(cssH * dpr);
    var ctx = targetCanvas.getContext('2d');
    ctx.fillStyle = COLORS.bg;
    ctx.fillRect(0, 0, targetCanvas.width, targetCanvas.height);
    if (!model.nodes.length) return;
    var source = renderToCanvas(model, dpr);
    var fit = Math.min(targetCanvas.width / source.width, targetCanvas.height / source.height, 1);
    var dw = source.width * fit, dh = source.height * fit;
    ctx.drawImage(source, (targetCanvas.width - dw) / 2, (targetCanvas.height - dh) / 2, dw, dh);
  }

  /**
   * 导出 PNG dataURL：从 scale 2 起逐档降档，直到 base64 体积落入 Bridge 消息限额。
   * @returns dataURL 或 null（空模型）
   */
  function exportPng(model, maxBase64Length) {
    if (!model.nodes.length) return null;
    var scales = [2, 1.5, 1, 0.75];
    var url = null;
    for (var i = 0; i < scales.length; i++) {
      url = renderToCanvas(model, scales[i]).toDataURL('image/png');
      if (url.length <= maxBase64Length) return url;
    }
    return url.length <= maxBase64Length ? url : null;
  }

  return {
    drawPreview: drawPreview,
    exportPng: exportPng
  };
})();
