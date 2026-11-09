/* Yomu EPUB reader runtime: pagination, text offsets, selection, highlights, footnotes, TTS marks. */
(function () {
  'use strict';
  var B = window.YomuBridge || { onReady: function () {}, onProgress: function () {}, onTap: function () {}, onSelection: function () {},
    onSelectionCleared: function () {}, onWord: function () {}, onLink: function () {}, onImage: function () {}, onFootnote: function () {},
    onEdge: function () {}, onHighlightTap: function () {}, footnote: function () { return ''; }, log: function () {} };
  var cfg = window.__yomuConfig || {};
  var SKIP = 'data-yomu-skip';
  var BLOCK = { P: 1, H1: 1, H2: 1, H3: 1, H4: 1, H5: 1, H6: 1, LI: 1, BLOCKQUOTE: 1, DD: 1, DT: 1, TD: 1, FIGCAPTION: 1, DIV: 1, PRE: 1 };
  var se = null;
  var pageW = 1, pageH = 1;
  var fixedScale = 1, fixedLeft = 0, fixedTop = 0;
  var highlights = [];
  var marks = {};
  var savedWords = [];
  var cache = null;
  var lastReport = '';
  var selectionShown = false;
  var anchorOffset = null;
  var ready = false;

  function json(o) { return JSON.stringify(o); }
  function debounce(fn, ms) { var t = null; return function () { var a = arguments; clearTimeout(t); t = setTimeout(function () { fn.apply(null, a); }, ms); }; }

  // ---------------------------------------------------------------- text model
  function skipped(n) {
    var p = n.parentNode;
    while (p && p !== document.body && p.nodeType === 1) {
      var t = p.tagName;
      if (t === 'SCRIPT' || t === 'STYLE' || t === 'NOSCRIPT' || p.hasAttribute(SKIP)) return true;
      p = p.parentNode;
    }
    return false;
  }
  function nodes() {
    if (cache) return cache;
    var list = [], map = new Map(), off = 0, n;
    var w = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null);
    while ((n = w.nextNode())) {
      if (skipped(n)) continue;
      var e = { node: n, start: off, len: n.nodeValue.length };
      list.push(e); map.set(n, e); off += e.len;
    }
    cache = { list: list, map: map, total: off };
    return cache;
  }
  function invalidate() { cache = null; }
  function posToOffset(c, o) {
    var m = nodes();
    if (c.nodeType === 3) {
      var e = m.map.get(c);
      if (e) return e.start + Math.min(o, e.len);
    }
    var ref = c.nodeType === 3 ? c : (c.childNodes[o] || null);
    for (var i = 0; i < m.list.length; i++) {
      var n = m.list[i].node;
      if (ref) {
        var p = ref.compareDocumentPosition(n);
        if (n === ref || (p & Node.DOCUMENT_POSITION_FOLLOWING) || (p & Node.DOCUMENT_POSITION_CONTAINED_BY)) return m.list[i].start;
      } else {
        var q = c.compareDocumentPosition(n);
        if ((q & Node.DOCUMENT_POSITION_FOLLOWING) && !(q & Node.DOCUMENT_POSITION_CONTAINED_BY)) return m.list[i].start;
      }
    }
    return m.total;
  }
  function offsetToPos(off) {
    var l = nodes().list;
    if (!l.length) return { node: document.body, offset: 0 };
    var lo = 0, hi = l.length - 1;
    while (lo < hi) { var mid = (lo + hi + 1) >> 1; if (l[mid].start <= off) lo = mid; else hi = mid - 1; }
    var e = l[lo];
    return { node: e.node, offset: Math.max(0, Math.min(e.len, off - e.start)) };
  }
  function rangeFor(start, end) {
    var a = offsetToPos(start), b = offsetToPos(Math.max(start, end));
    var r = document.createRange();
    try { r.setStart(a.node, a.offset); r.setEnd(b.node, b.offset); } catch (e) { r.selectNodeContents(document.body); r.collapse(true); }
    return r;
  }
  function textBetween(start, end) { return rangeFor(start, end).toString(); }
  function fullText() { var l = nodes().list, s = ''; for (var i = 0; i < l.length; i++) s += l[i].node.nodeValue; return s; }

  // ---------------------------------------------------------------- layout
  function isRtlDoc() { try { return getComputedStyle(document.body).direction === 'rtl'; } catch (e) { return false; } }
  function fixedDimensions() {
    var meta = document.querySelector('meta[name="yomu-fixed-viewport"]');
    var value = meta ? meta.getAttribute('content') || '' : '';
    var w = /(?:^|[,;\s])width\s*=\s*([0-9]+(?:\.[0-9]+)?)/i.exec(value);
    var h = /(?:^|[,;\s])height\s*=\s*([0-9]+(?:\.[0-9]+)?)/i.exec(value);
    var width = w ? Number(w[1]) : 0, height = h ? Number(h[1]) : 0;
    if (!width || !height) {
      var svg = document.querySelector('body > svg[viewBox], body > svg[viewbox]');
      var box = svg && (svg.getAttribute('viewBox') || svg.getAttribute('viewbox'));
      var v = box ? box.trim().split(/[\s,]+/).map(Number) : [];
      if (v.length === 4 && v[2] > 0 && v[3] > 0) { width = v[2]; height = v[3]; }
    }
    if (!(width >= 10 && width <= 20000 && height >= 10 && height <= 20000)) return [1024, 768];
    return [width, height];
  }
  function applyLayout() {
    se = document.scrollingElement || document.documentElement;
    pageW = window.innerWidth; pageH = window.innerHeight;
    var mh = cfg.marginH || 20, mv = cfg.marginV || 28;
    var st = document.getElementById('yomu-layout');
    if (!st) { st = document.createElement('style'); st.id = 'yomu-layout'; document.head.appendChild(st); }
    var css = '';
    if (cfg.fixed) {
      var dim = fixedDimensions();
      var availableH = Math.max(1, pageH - (cfg.footer ? 30 : 0));
      fixedScale = Math.min(pageW / dim[0], availableH / dim[1]);
      fixedLeft = (pageW - dim[0] * fixedScale) / 2;
      fixedTop = (availableH - dim[1] * fixedScale) / 2;
      css = 'html{position:absolute!important;left:' + fixedLeft + 'px!important;top:' + fixedTop + 'px!important;' +
        'width:' + dim[0] + 'px!important;height:' + dim[1] + 'px!important;overflow:hidden!important;' +
        'transform-origin:0 0!important;transform:scale(' + fixedScale + ')!important;}';
    } else if (cfg.vertical) {
      css = 'html{writing-mode:vertical-rl;-webkit-writing-mode:vertical-rl;height:100vh;overflow-x:auto;overflow-y:hidden;}' +
        'body{margin:0!important;padding:' + mv + 'px ' + mh + 'px!important;height:100vh;box-sizing:border-box;}';
    } else if (cfg.paged) {
      var cols = (cfg.columns || 1);
      var colW = Math.floor((pageW - 2 * mh * cols) / cols);
      // The status line (page, clock) lives in the bottom margin: keep it free of text.
      var mb = cfg.footer ? Math.max(mv, 30) : mv;
      css = 'html{height:' + pageH + 'px!important;overflow:hidden!important;margin:0!important;padding:0!important;}' +
        'body{margin:0!important;padding:' + mv + 'px ' + mh + 'px ' + mb + 'px ' + mh + 'px!important;height:' + pageH + 'px!important;box-sizing:border-box!important;' +
        'column-width:' + colW + 'px!important;column-gap:' + (2 * mh) + 'px!important;column-fill:auto!important;overflow:visible!important;' +
        'max-width:none!important;width:auto!important;}' +
        'img,svg,video{max-height:' + (pageH - mv - mb - 4) + 'px!important;max-width:100%!important;object-fit:contain;break-inside:avoid;}' +
        'p,li,h1,h2,h3,h4,h5,h6{orphans:2;widows:2;}';
    } else {
      css = 'html{overflow-x:hidden;}body{margin:0!important;padding:' + mv + 'px ' + mh + 'px ' + (cfg.footer ? Math.max(mv, 30) : mv) + 'px ' + mh + 'px!important;box-sizing:border-box;}' +
        'img,svg,video{max-width:100%!important;height:auto;}';
    }
    st.textContent = css;
    navButtons();
  }
  function navButtons() {
    if (cfg.paged || cfg.vertical) { ['yomu-prev', 'yomu-next'].forEach(function (id) { var e = document.getElementById(id); if (e) e.remove(); }); return; }
    if (cfg.hasPrev && !document.getElementById('yomu-prev')) {
      var p = document.createElement('div'); p.id = 'yomu-prev'; p.className = 'yomu-nav'; p.setAttribute(SKIP, '1'); p.textContent = cfg.prevLabel || '‹';
      p.addEventListener('click', function (e) { e.stopPropagation(); B.onEdge('prev'); });
      document.body.insertBefore(p, document.body.firstChild);
    }
    if (cfg.hasNext && !document.getElementById('yomu-next')) {
      var n = document.createElement('div'); n.id = 'yomu-next'; n.className = 'yomu-nav'; n.setAttribute(SKIP, '1'); n.textContent = cfg.nextLabel || '›';
      n.addEventListener('click', function (e) { e.stopPropagation(); B.onEdge('next'); });
      document.body.appendChild(n);
    }
  }
  function pageCount() {
    if (cfg.fixed || !cfg.paged || cfg.vertical) return 1;
    return Math.max(1, Math.round(se.scrollWidth / pageW));
  }
  function currentPage() { return cfg.fixed ? 0 : Math.round(Math.abs(se.scrollLeft) / pageW); }
  function setPage(i, smooth) {
    var n = pageCount();
    i = Math.max(0, Math.min(n - 1, i));
    var x = i * pageW * (isRtlDoc() ? -1 : 1);
    var anim = cfg.reduceMotion ? 'NONE' : (cfg.pageAnimation || 'SLIDE');
    if (smooth && anim === 'FADE') {
      var el = document.documentElement;
      el.style.transition = 'opacity 110ms ease-out';
      el.style.opacity = '0';
      setTimeout(function () {
        se.scrollLeft = x;
        el.style.transition = 'opacity 150ms ease-in';
        el.style.opacity = '1';
        report();
      }, 120);
      setTimeout(report, 300);
      return;
    }
    if (smooth && anim === 'SLIDE' && Math.abs(Math.abs(se.scrollLeft) - i * pageW) <= pageW * 1.5) se.scrollTo({ left: x, behavior: 'smooth' });
    else se.scrollLeft = x;
    setTimeout(report, smooth && anim === 'SLIDE' ? 350 : 30);
  }
  function progression() {
    if (cfg.vertical) { var mx = se.scrollWidth - pageW; return mx <= 0 ? 1 : Math.min(1, Math.abs(se.scrollLeft) / mx); }
    if (cfg.paged) { var n = pageCount(); return n <= 1 ? 1 : currentPage() / (n - 1); }
    var m = se.scrollHeight - pageH; return m <= 0 ? 1 : Math.min(1, se.scrollTop / m);
  }
  function caretAt(x, y) {
    var r = document.caretRangeFromPoint ? document.caretRangeFromPoint(x, y) : null;
    if (r && r.startContainer && (r.startContainer.nodeType === 3 || r.startContainer.childNodes.length)) return r;
    return null;
  }
  function firstVisibleOffset() {
    if (cfg.fixed) return 0;
    var mh = cfg.marginH || 20, mv = cfg.marginV || 28;
    var pts = [[mh + 2, mv + 2], [mh + 20, mv + 12], [pageW / 2, mv + 20], [pageW / 2, pageH / 3], [pageW / 2, pageH / 2]];
    for (var i = 0; i < pts.length; i++) {
      var r = caretAt(pts[i][0], pts[i][1]);
      if (r) return posToOffset(r.startContainer, r.startOffset);
    }
    return Math.round(progression() * nodes().total);
  }
  function lastVisibleOffset() {
    if (cfg.fixed) return nodes().total;
    var mh = cfg.marginH || 20, mv = cfg.marginV || 28;
    if (cfg.footer) mv = Math.max(mv, 30);
    var pts = [[pageW - mh - 2, pageH - mv - 4], [pageW - mh - 20, pageH - mv - 16], [pageW / 2, pageH - mv - 24], [pageW / 2, pageH * 0.75]];
    for (var i = 0; i < pts.length; i++) {
      var r = caretAt(pts[i][0], pts[i][1]);
      if (r) return posToOffset(r.startContainer, r.startOffset);
    }
    return nodes().total;
  }
  function report() {
    if (!ready) return;
    var o = { page: currentPage(), pages: pageCount(), progression: progression(), offset: firstVisibleOffset(), total: nodes().total };
    var s = json(o);
    if (s !== lastReport) { lastReport = s; B.onProgress(s); }
    drawAll();
  }
  var reportLater = debounce(report, 160);

  function rectOfOffset(off) {
    var r = rangeFor(off, Math.min(off + 1, nodes().total));
    var rects = r.getClientRects();
    var rc = rects.length ? rects[0] : r.getBoundingClientRect();
    return rc;
  }
  function goToOffset(off, smooth) {
    if (cfg.fixed) { setTimeout(report, 30); return; }
    off = Math.max(0, Math.min(off, nodes().total));
    var rc = rectOfOffset(off);
    if (cfg.vertical) { se.scrollLeft = se.scrollLeft + rc.right - pageW + (cfg.marginH || 20); }
    else if (cfg.paged) {
      var abs = Math.abs(se.scrollLeft) + (isRtlDoc() ? (pageW - rc.right) : rc.left);
      setPage(Math.floor((abs + 1) / pageW), smooth);
      return;
    } else { se.scrollTop = se.scrollTop + rc.top - (cfg.marginV || 28); }
    setTimeout(report, 30);
  }
  function goToProgression(p) {
    if (cfg.fixed) { setTimeout(report, 30); return; }
    p = Math.max(0, Math.min(1, p || 0));
    if (cfg.vertical) se.scrollLeft = -p * (se.scrollWidth - pageW);
    else if (cfg.paged) { setPage(Math.round(p * (pageCount() - 1)), false); return; }
    else se.scrollTop = p * (se.scrollHeight - pageH);
    setTimeout(report, 30);
  }
  function goToAnchor(id) {
    var el = document.getElementById(id) || document.getElementsByName(id)[0];
    if (!el) return false;
    var off = posToOffset(el, 0);
    goToOffset(off, false);
    return true;
  }
  function scrollBehavior() { return cfg.reduceMotion || cfg.pageAnimation === 'NONE' || cfg.pageAnimation === 'FADE' ? 'auto' : 'smooth'; }
  function next() {
    if (cfg.paged && !cfg.vertical) {
      var p = currentPage();
      if (p >= pageCount() - 1) B.onEdge('next'); else setPage(p + 1, true);
    } else {
      var before = cfg.vertical ? Math.abs(se.scrollLeft) : se.scrollTop;
      if (cfg.vertical) se.scrollBy({ left: -(pageW - 40), behavior: scrollBehavior() });
      else se.scrollBy({ top: pageH - 60, behavior: scrollBehavior() });
      setTimeout(function () { var after = cfg.vertical ? Math.abs(se.scrollLeft) : se.scrollTop; if (Math.abs(after - before) < 2) B.onEdge('next'); else report(); }, 400);
    }
  }
  function prev() {
    if (cfg.paged && !cfg.vertical) {
      var p = currentPage();
      if (p <= 0) B.onEdge('prev'); else setPage(p - 1, true);
    } else {
      var before = cfg.vertical ? Math.abs(se.scrollLeft) : se.scrollTop;
      if (cfg.vertical) se.scrollBy({ left: pageW - 40, behavior: scrollBehavior() });
      else se.scrollBy({ top: -(pageH - 60), behavior: scrollBehavior() });
      setTimeout(function () { var after = cfg.vertical ? Math.abs(se.scrollLeft) : se.scrollTop; if (Math.abs(after - before) < 2) B.onEdge('prev'); else report(); }, 400);
    }
  }

  // ---------------------------------------------------------------- decorations
  function layer(id, z) {
    var l = document.getElementById(id);
    if (!l) {
      l = document.createElement('div'); l.id = id; l.setAttribute(SKIP, '1');
      l.style.cssText = 'position:absolute;left:0;top:0;width:0;height:0;pointer-events:none;z-index:' + z + ';';
      document.documentElement.appendChild(l);
    }
    return l;
  }
  function drawRanges(l, items, cls) {
    var sx = window.scrollX, sy = window.scrollY, frag = document.createDocumentFragment();
    items.forEach(function (h) {
      var rects = rangeFor(h.start, h.end).getClientRects();
      h.rects = [];
      for (var i = 0; i < rects.length; i++) {
        var r = rects[i];
        if (r.width < 1 || r.height < 1) continue;
        h.rects.push({ l: r.left, t: r.top, r: r.right, b: r.bottom });
        var d = document.createElement('div');
        d.className = cls + ' ' + (h.style || '');
        var left = cfg.fixed ? (r.left - fixedLeft) / fixedScale : r.left + sx;
        var top = cfg.fixed ? (r.top - fixedTop) / fixedScale : r.top + sy;
        var width = cfg.fixed ? r.width / fixedScale : r.width;
        var height = cfg.fixed ? r.height / fixedScale : r.height;
        var s = 'position:absolute;left:' + left + 'px;top:' + top + 'px;width:' + width + 'px;height:' + height + 'px;';
        if (h.color) s += '--c:' + h.color + ';';
        d.style.cssText = s;
        frag.appendChild(d);
      }
      if (h.note && h.rects.length) {
        var last = h.rects[h.rects.length - 1];
        var n = document.createElement('div'); n.className = 'yomu-note-mark'; n.textContent = '✎';
        var noteLeft = cfg.fixed ? (last.r - fixedLeft) / fixedScale : last.r + sx;
        var noteTop = cfg.fixed ? (last.t - fixedTop) / fixedScale - 6 : last.t + sy - 6;
        n.style.cssText = 'position:absolute;left:' + noteLeft + 'px;top:' + noteTop + 'px;';
        frag.appendChild(n);
      }
    });
    l.innerHTML = '';
    l.appendChild(frag);
  }
  function drawAll() {
    if (!ready) return;
    drawRanges(layer('yomu-hl', 1), highlights, 'yomu-hl');
    var m = [];
    Object.keys(marks).forEach(function (k) { if (marks[k]) m.push({ start: marks[k].start, end: marks[k].end, style: 'yomu-mark-' + k }); });
    drawRanges(layer('yomu-marks', 2), m, 'yomu-mark');
    drawWords();
  }
  function drawWords() {
    var l = layer('yomu-words', 1);
    if (!savedWords.length) { l.innerHTML = ''; return; }
    var text = fullText();
    var esc = savedWords.map(function (w) { return w.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'); });
    var re;
    try { re = new RegExp('(^|[^\\p{L}\\p{M}])(' + esc.join('|') + ')(?=$|[^\\p{L}\\p{M}])', 'giu'); } catch (e) { return; }
    var items = [], m, count = 0;
    var lo = Math.max(0, firstVisibleOffset() - 4000), hi = Math.min(text.length, lastVisibleOffset() + 4000);
    var part = text.substring(lo, hi);
    while ((m = re.exec(part)) && count < 400) {
      var s = lo + m.index + m[1].length;
      items.push({ start: s, end: s + m[2].length, style: 'yomu-word' });
      count++;
    }
    drawRanges(l, items, 'yomu-wordmark');
  }

  // ---------------------------------------------------------------- selection
  function selectionInfo() {
    var sel = window.getSelection();
    if (!sel || sel.isCollapsed || !sel.rangeCount) return null;
    var r = sel.getRangeAt(0);
    var start = posToOffset(r.startContainer, r.startOffset), end = posToOffset(r.endContainer, r.endOffset);
    var rc = r.getBoundingClientRect();
    return { text: sel.toString(), start: start, end: end, left: rc.left, top: rc.top, right: rc.right, bottom: rc.bottom, sentence: sentenceAround(start, end) };
  }
  var onSelectionChange = debounce(function () {
    var info = selectionInfo();
    if (info && info.text.trim().length) { selectionShown = true; B.onSelection(json(info)); }
    else if (selectionShown) { selectionShown = false; B.onSelectionCleared(); }
  }, 220);
  function clearSelection() { var s = window.getSelection(); if (s) s.removeAllRanges(); selectionShown = false; }
  function select(start, end) {
    var s = window.getSelection(); s.removeAllRanges(); s.addRange(rangeFor(start, end));
  }
  function blockOf(node) {
    var n = node.nodeType === 3 ? node.parentNode : node;
    while (n && n !== document.body) { if (BLOCK[n.tagName]) return n; n = n.parentNode; }
    return document.body;
  }
  function blockBounds(off) {
    var p = offsetToPos(off);
    var b = blockOf(p.node);
    var r = document.createRange(); r.selectNodeContents(b);
    return { start: posToOffset(r.startContainer, r.startOffset), end: posToOffset(r.endContainer, r.endOffset), el: b };
  }
  var SENT_END = /[.!?…。！？]+["»”’)\]]*\s*/g;
  function sentenceBounds(start, end) {
    var bb = blockBounds(start), text = textBetween(bb.start, bb.end), rel = start - bb.start, relEnd = end - bb.start;
    var s = 0, e = text.length, m;
    SENT_END.lastIndex = 0;
    while ((m = SENT_END.exec(text))) {
      var stop = m.index + m[0].length;
      if (stop <= rel) s = stop;
      else if (m.index >= relEnd) { e = m.index + m[0].replace(/\s+$/, '').length; break; }
    }
    return { start: bb.start + s, end: bb.start + e };
  }
  function sentenceAround(start, end) { var b = sentenceBounds(start, end); return textBetween(b.start, b.end).trim().slice(0, 600); }
  function expand(kind) {
    var info = selectionInfo(); if (!info) return;
    var b = kind === 'paragraph' ? blockBounds(info.start) : sentenceBounds(info.start, info.end);
    select(b.start, b.end);
  }

  // ---------------------------------------------------------------- words
  function wordAt(x, y) {
    var r = caretAt(x, y);
    if (!r || r.startContainer.nodeType !== 3) return null;
    var node = r.startContainer, text = node.nodeValue, i = Math.min(r.startOffset, text.length - 1);
    if (i < 0) return null;
    var probe = document.createRange(); probe.setStart(node, i); probe.setEnd(node, Math.min(text.length, i + 1));
    var pr = probe.getBoundingClientRect();
    if (x < pr.left - 12 || x > pr.right + 12 || y < pr.top - 8 || y > pr.bottom + 8) return null;
    var s = i, e = i + 1, found = false;
    if (window.Intl && Intl.Segmenter) {
      try {
        var seg = new Intl.Segmenter(cfg.lang || undefined, { granularity: 'word' });
        var it = seg.segment(text)[Symbol.iterator](), step;
        while (!(step = it.next()).done) {
          var g = step.value;
          if (g.index <= i && i < g.index + g.segment.length) { if (!g.isWordLike) return null; s = g.index; e = g.index + g.segment.length; found = true; break; }
        }
      } catch (err) { found = false; }
    }
    if (!found) {
      var W = /[\p{L}\p{M}\p{N}'’\-]/u;
      if (!W.test(text[i])) return null;
      while (s > 0 && W.test(text[s - 1])) s--;
      while (e < text.length && W.test(text[e])) e++;
    }
    var base = nodes().map.get(node); if (!base) return null;
    var start = base.start + s, end = base.start + e;
    var wr = document.createRange(); wr.setStart(node, s); wr.setEnd(node, e);
    var rc = wr.getBoundingClientRect();
    return { word: text.substring(s, e), start: start, end: end, tail: textBetween(start, Math.min(nodes().total, start + 24)),
      sentence: sentenceAround(start, end), left: rc.left, top: rc.top, right: rc.right, bottom: rc.bottom };
  }

  // ---------------------------------------------------------------- touch, taps, links
  var touch = null, tapTimer = null, lastTapAt = 0;
  function onTouchStart(e) { if (e.touches.length !== 1) { touch = null; return; } var t = e.touches[0]; touch = { x: t.clientX, y: t.clientY, at: Date.now() }; }
  function onTouchEnd(e) {
    if (!touch) return;
    var t = e.changedTouches[0], dx = t.clientX - touch.x, dy = t.clientY - touch.y, dt = Date.now() - touch.at;
    touch = null;
    var sel = window.getSelection();
    if (sel && !sel.isCollapsed) return;
    if (cfg.paged && !cfg.vertical && Math.abs(dx) > 40 && Math.abs(dx) > Math.abs(dy) * 1.3 && dt < 800) {
      var forward = dx < 0;
      if (cfg.rtl) forward = !forward;
      if (forward) next(); else prev();
    }
  }
  function hitHighlight(x, y) {
    for (var i = 0; i < highlights.length; i++) {
      var rs = highlights[i].rects || [];
      for (var j = 0; j < rs.length; j++) { var r = rs[j]; if (x >= r.l && x <= r.r && y >= r.t && y <= r.b) return highlights[i]; }
    }
    return null;
  }
  function footnoteFor(a) {
    var href = a.getAttribute('href') || '';
    var type = (a.getAttribute('epub:type') || '') + ' ' + (a.getAttribute('role') || '');
    var isNote = /noteref/.test(type);
    var txt = (a.textContent || '').trim();
    if (!isNote && href.indexOf('#') >= 0 && /^[\[(]?(\d{1,3}|[*†‡§a-z])[\])]?$/i.test(txt)) isNote = true;
    if (!isNote && a.closest('sup')) isNote = href.indexOf('#') >= 0;
    if (!isNote) return null;
    var id = href.substring(href.indexOf('#') + 1);
    var path = href.substring(0, href.indexOf('#'));
    var html = '';
    if (!path) {
      var el = document.getElementById(id);
      if (el) {
        var holder = el;
        if (el.tagName === 'A' && el.parentNode && el.parentNode !== document.body) holder = el.closest('li,p,aside,div,dd') || el.parentNode;
        html = holder.innerHTML;
      }
    } else {
      html = B.footnote(href) || '';
    }
    return html ? html : null;
  }
  function onClick(e) {
    var sel = window.getSelection();
    if (sel && !sel.isCollapsed) { clearSelection(); B.onSelectionCleared(); e.preventDefault(); return; }
    var a = e.target.closest ? e.target.closest('a[href]') : null;
    if (a) {
      e.preventDefault();
      var note = footnoteFor(a);
      if (note) { B.onFootnote(note); return; }
      B.onLink(a.href);
      return;
    }
    var img = e.target.closest ? e.target.closest('img,image') : null;
    if (img && !cfg.paged && !cfg.fixed) { B.onImage(img.currentSrc || img.src || img.getAttribute('xlink:href') || img.getAttribute('href') || ''); return; }
    var x = e.clientX, y = e.clientY;
    var h = hitHighlight(x, y);
    if (h) { B.onHighlightTap(json({ id: h.id, left: x, top: y, right: x, bottom: y })); return; }
    if (img && !cfg.fixed) { B.onImage(img.currentSrc || img.src || img.getAttribute('xlink:href') || img.getAttribute('href') || ''); return; }
    var now = Date.now();
    if (cfg.wordTap === 'DOUBLE_TAP') {
      if (now - lastTapAt < 300 && tapTimer) {
        clearTimeout(tapTimer); tapTimer = null; lastTapAt = 0;
        var w = wordAt(x, y); if (w) B.onWord(json(w));
        return;
      }
      lastTapAt = now;
      tapTimer = setTimeout(function () { tapTimer = null; B.onTap(x / pageW, y / pageH); }, 280);
      return;
    }
    if (cfg.wordTap === 'SINGLE_TAP') {
      var zoneX = x / pageW;
      if (zoneX > 0.2 && zoneX < 0.8) { var w2 = wordAt(x, y); if (w2) { B.onWord(json(w2)); return; } }
    }
    B.onTap(x / pageW, y / pageH);
  }

  // ---------------------------------------------------------------- blocks, sentences, translation
  function leafBlocks() {
    var out = [], all = document.body.querySelectorAll('p,h1,h2,h3,h4,h5,h6,li,blockquote,dd,dt,td,figcaption,div,pre');
    for (var i = 0; i < all.length; i++) {
      var el = all[i];
      if (el.hasAttribute(SKIP) || el.closest('[' + SKIP + ']')) continue;
      var nested = false;
      for (var c = 0; c < el.children.length; c++) if (BLOCK[el.children[c].tagName]) { nested = true; break; }
      if (nested) continue;
      var t = (el.textContent || '').trim();
      if (t.length < 1) continue;
      out.push(el);
    }
    return out;
  }
  function blocks(from) {
    var list = leafBlocks(), res = [];
    for (var i = 0; i < list.length; i++) {
      var el = list[i]; el.setAttribute('data-yomu-i', String(i));
      var r = document.createRange(); r.selectNodeContents(el);
      var s = posToOffset(r.startContainer, r.startOffset), e = posToOffset(r.endContainer, r.endOffset);
      if (from != null && e < from) continue;
      res.push({ i: i, start: s, end: e, text: (el.textContent || '').trim(), translated: !!(el.nextElementSibling && el.nextElementSibling.classList.contains('yomu-tr')) });
    }
    return res;
  }
  function insertTranslation(i, text) {
    var el = document.querySelector('[data-yomu-i="' + i + '"]');
    if (!el) return;
    var n = el.nextElementSibling;
    if (n && n.classList.contains('yomu-tr')) { n.textContent = text; return; }
    var tag = (el.tagName === 'LI' || el.tagName === 'TD') ? 'div' : 'p';
    var t = document.createElement(tag); t.className = 'yomu-tr'; t.setAttribute(SKIP, '1'); t.textContent = text;
    if (el.tagName === 'LI' || el.tagName === 'TD') el.appendChild(t); else el.parentNode.insertBefore(t, el.nextSibling);
  }
  function removeTranslations() { var l = document.querySelectorAll('.yomu-tr'); for (var i = 0; i < l.length; i++) l[i].remove(); }
  function sentences(from) {
    var text = fullText(), out = [], start = Math.max(0, from || 0);
    var list = blocks(start);
    list.forEach(function (b) {
      var s0 = Math.max(b.start, start), t = text.substring(s0, b.end);
      var pieces = [];
      if (window.Intl && Intl.Segmenter) {
        try { var seg = new Intl.Segmenter(cfg.lang || undefined, { granularity: 'sentence' }); var it = seg.segment(t)[Symbol.iterator](), st; while (!(st = it.next()).done) pieces.push({ i: st.value.index, s: st.value.segment }); } catch (e) { pieces = []; }
      }
      if (!pieces.length) { var m, last = 0; SENT_END.lastIndex = 0; while ((m = SENT_END.exec(t))) { pieces.push({ i: last, s: t.substring(last, m.index + m[0].length) }); last = m.index + m[0].length; } if (last < t.length) pieces.push({ i: last, s: t.substring(last) }); }
      pieces.forEach(function (p) { var clean = p.s.trim(); if (clean.length && /[\p{L}\p{N}]/u.test(clean)) { var lead = p.s.length - p.s.replace(/^\s+/, '').length; out.push({ start: s0 + p.i + lead, end: s0 + p.i + lead + clean.length, text: clean }); } });
    });
    return out;
  }
  function ensureVisible(off) {
    if (cfg.paged && !cfg.vertical) {
      var rc = rectOfOffset(off);
      if (rc.left < 0 || rc.left > pageW || rc.top < 0 || rc.top > pageH) goToOffset(off, true);
    } else {
      var r = rectOfOffset(off);
      if (r.top < 0 || r.bottom > pageH - 20) goToOffset(off, false);
    }
  }

  // ---------------------------------------------------------------- API
  window.yomu = {
    next: next, prev: prev,
    goToOffset: function (o) { goToOffset(o, false); },
    goToProgression: goToProgression,
    goToAnchor: goToAnchor,
    setConfig: function (c, css) {
      var keep = ready ? firstVisibleOffset() : null;
      cfg = Object.assign(cfg, c || {});
      if (css != null) { var u = document.getElementById('yomu-user'); if (u) u.textContent = css; }
      applyLayout(); invalidate();
      if (keep != null) setTimeout(function () { goToOffset(keep, false); }, 60);
    },
    setHighlights: function (list) { highlights = list || []; drawAll(); },
    mark: function (kind, start, end, scroll) { marks[kind] = (start == null) ? null : { start: start, end: end }; if (start != null && scroll) ensureVisible(start); drawAll(); },
    setSavedWords: function (list) { savedWords = (list || []).filter(function (w) { return w && w.length > 1; }).slice(0, 3000); drawAll(); },
    clearSelection: clearSelection,
    select: select,
    expand: expand,
    selection: function () { return json(selectionInfo()); },
    blocks: function (from) { return json(blocks(from)); },
    insertTranslation: function (i, t) { insertTranslation(i, t); },
    commitTranslations: function () { var keep = firstVisibleOffset(); invalidate(); if (anchorOffset != null) keep = anchorOffset; setTimeout(function () { goToOffset(keep, false); drawAll(); }, 30); },
    removeTranslations: function () { var keep = firstVisibleOffset(); removeTranslations(); invalidate(); setTimeout(function () { goToOffset(keep, false); }, 30); },
    sentences: function (from) { return json(sentences(from)); },
    visibleText: function () { var a = firstVisibleOffset(), b = lastVisibleOffset(); return textBetween(a, Math.max(a, b)); },
    text: function (s, e) { return textBetween(s, e); },
    scrollStep: function (px) { if (cfg.paged && !cfg.vertical) return; var before = se.scrollTop; se.scrollTop += px; if (se.scrollTop === before && px > 0) B.onEdge('next'); else reportLater(); },
    state: function () { return json({ page: currentPage(), pages: pageCount(), progression: progression(), offset: firstVisibleOffset(), total: nodes().total }); }
  };

  function start() {
    applyLayout();
    var go = function () {
      invalidate();
      var t = cfg.target || {};
      if (t.anchor && goToAnchor(t.anchor)) { /* positioned */ }
      else if (t.offset != null) goToOffset(t.offset, false);
      else if (t.progression != null) goToProgression(t.progression);
      ready = true;
      setTimeout(function () {
        lastReport = '';
        report();
        B.onReady(json({ pages: pageCount(), total: nodes().total }));
      }, 40);
    };
    var fontsReady = document.fonts && document.fonts.ready ? document.fonts.ready : Promise.resolve();
    var loaded = document.readyState === 'complete' ? Promise.resolve() : new Promise(function (res) { window.addEventListener('load', res, { once: true }); setTimeout(res, 2500); });
    Promise.all([fontsReady, loaded]).then(function () { applyLayout(); setTimeout(go, 30); });
    document.addEventListener('selectionchange', onSelectionChange);
    document.addEventListener('touchstart', onTouchStart, { passive: true });
    document.addEventListener('touchend', onTouchEnd, { passive: true });
    document.addEventListener('click', onClick, true);
    window.addEventListener('scroll', function () { if (!cfg.paged || cfg.vertical) reportLater(); }, { passive: true });
    window.addEventListener('resize', debounce(function () { var keep = firstVisibleOffset(); applyLayout(); invalidate(); setTimeout(function () { goToOffset(keep, false); }, 50); }, 200));
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', start); else start();
})();
