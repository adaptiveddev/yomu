/*
 * Yomu: print layout of DOCX documents. Flows the blocks rendered by DocxLayout.kt into pages of the size of their section, like the
 * print layout of Word: headers and footers, footnotes at the bottom of the page, paragraphs split at line boundaries with widow and
 * orphan control, "keep with next", tables split between rows with repeated header rows, page and section breaks.
 * Bridge (optional): YomuLayout.onProgress(pages), onReady(pages), onPage(index, label). API: yomuLayout.goTo(page).
 */
(function () {
  'use strict';
  var bridge = window.YomuLayout || null;
  var query = new URLSearchParams(location.search);
  if (query.get('dark') === '1') document.documentElement.classList.add('dark');
  var src = document.getElementById('src');
  var out = document.getElementById('pages');
  var DT = parseFloat(document.body.dataset.dt) || 48;
  var EVEN_ODD = document.body.dataset.eo === '1';
  var notes = {};
  var store = document.getElementById('fnstore');
  if (store) Array.prototype.forEach.call(store.children, function (n) { notes[n.dataset.fn] = n; });
  var secs = Array.prototype.slice.call(src.querySelectorAll('section.sec'));
  var pages = [];
  var cur = null, active = null, secPages = 0, shown = 0, total = 0;
  var startPage = (function () { var h = /p=(\d+)/.exec(location.hash); return h ? parseInt(h[1], 10) : 0; })();

  function num(el, k, d) { var v = parseFloat(el.dataset[k]); return isNaN(v) ? d : v; }
  function mk(tag, cls) { var e = document.createElement(tag); if (cls) e.className = cls; return e; }

  function geom(sec) {
    if (!sec._g) {
      sec._g = { w: num(sec, 'w', 794), h: num(sec, 'h', 1123), mt: num(sec, 'mt', 76), mb: num(sec, 'mb', 76),
        ml: num(sec, 'ml', 113), mr: num(sec, 'mr', 57), hd: num(sec, 'hd', 47), fd: num(sec, 'fd', 47) };
    }
    return sec._g;
  }

  function roman(n) {
    var v = [1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1], s = ['M', 'CM', 'D', 'CD', 'C', 'XC', 'L', 'XL', 'X', 'IX', 'V', 'IV', 'I'], r = '';
    if (n <= 0 || n >= 4000) return String(n);
    for (var i = 0; i < v.length; i++) while (n >= v[i]) { r += s[i]; n -= v[i]; }
    return r;
  }

  function pageText(n, fmt) {
    switch (fmt) {
      case 'upperRoman': return roman(n);
      case 'lowerRoman': return roman(n).toLowerCase();
      case 'upperLetter': return String.fromCharCode(65 + (n - 1) % 26);
      case 'lowerLetter': return String.fromCharCode(97 + (n - 1) % 26);
      default: return String(n);
    }
  }

  /**
   * Word does not add the space after a paragraph to the space before the next one: the larger of the two wins. The blocks live in
   * flex columns (no CSS margin collapsing), so the space before is reduced by the preceding paragraph's space after.
   */
  function collapseSpacing(scope) {
    var ps = scope.querySelectorAll('.p'), i;
    for (i = 0; i < ps.length; i++) {
      var p = ps[i], prev = p.previousElementSibling;
      if (!prev || !prev.classList.contains('p') || p.dataset.pbb) continue;
      var a = parseFloat(prev.style.marginBottom) || 0, b = parseFloat(p.style.marginTop) || 0;
      if (a > 0 && b > 0) p.style.marginTop = Math.max(0, b - a).toFixed(2) + 'px';
    }
  }

  collapseSpacing(src);
  if (store) collapseSpacing(store);

  // ------------------------------------------------------------------------------------------------ tabs

  var HOSTS = { flow: 1, cc: 1, hf: 1, body: 1, tbc: 1, fns: 1, fn: 1 };

  function hostOf(p) {
    for (var e = p.parentElement; e; e = e.parentElement) {
      for (var i = 0; i < e.classList.length; i++) if (HOSTS[e.classList[i]]) return e;
    }
    return p.parentElement;
  }

  function stopsOf(p) {
    var s = p.dataset.tabs, r = [];
    if (s) s.split(';').forEach(function (t) {
      var a = t.split(','), pos = parseFloat(a[0]);
      if (!isNaN(pos)) r.push({ pos: pos, al: a[1] || 'left', ld: a[2] || 'none' });
    });
    r.sort(function (a, b) { return a.pos - b.pos; });
    return r;
  }

  function layoutTabs(scope) {
    var ps = scope.querySelectorAll('.p.ht');
    for (var i = 0; i < ps.length; i++) tabPara(ps[i]);
  }

  function segWidth(seg) {
    var q = seg.getClientRects();
    return q.length ? q[0].width : 0;
  }

  function tabPara(p) {
    var host = hostOf(p);
    if (!host) return;
    var origin = host.getBoundingClientRect().left, width = host.clientWidth;
    var stops = stopsOf(p), il = num(p, 'il', 0), hang = num(p, 'hang', 0);
    var last = stops.length ? stops[stops.length - 1].pos : 0;
    var tabs = p.querySelectorAll('.tab'), i;
    if (!tabs.length) return;
    // Word resolves tab positions as if the line were left-aligned, then aligns the whole line.
    var align = p.style.textAlign, cs = getComputedStyle(p).textAlign;
    var shifted = cs === 'center' || cs === 'right' || cs === 'end';
    if (shifted) p.style.textAlign = 'left';
    for (i = 0; i < tabs.length; i++) { tabs[i].style.width = '0px'; tabs[i].className = 'tab'; }
    for (i = 0; i < tabs.length; i++) {
      var tab = tabs[i];
      if (tab.closest('.p') !== p) continue;
      var x = tab.getBoundingClientRect().left - origin, stop = null, pt = tab.dataset.pt;
      if (pt) {
        stop = { pos: pt === 'center' ? width / 2 : pt === 'right' ? width : 0, al: pt, ld: tab.dataset.ld || 'none' };
      } else {
        for (var j = 0; j < stops.length; j++) if (stops[j].pos > x + 0.5) { stop = stops[j]; break; }
        if (hang > 0 && x < il - 0.5 && (!stop || il < stop.pos)) stop = { pos: il, al: 'left', ld: 'none' };
        if (!stop) stop = { pos: (Math.floor((Math.max(x, last) + 0.5) / DT) + 1) * DT, al: 'left', ld: 'none' };
      }
      var w = stop.pos - x;
      if (stop.al === 'right' || stop.al === 'center' || stop.al === 'decimal') {
        var seg = tab.nextElementSibling, sw = seg && seg.classList.contains('ts') ? segWidth(seg) : 0;
        w -= stop.al === 'center' ? sw / 2 : sw;
        if (x + w + sw > width + 0.5) w = Math.max(0, width - x - sw);
      }
      if (w < 0) w = 0;
      tab.style.width = w.toFixed(2) + 'px';
      if (stop.ld && stop.ld !== 'none') tab.classList.add('ld-' + stop.ld);
    }
    if (shifted) p.style.textAlign = align;
  }

  // ------------------------------------------------------------------------------------------------ pages

  function headerFooter(sec, key, page, g, isHeader) {
    var tpl = null;
    for (var c = sec.firstElementChild; c; c = c.nextElementSibling) if (c.tagName === 'TEMPLATE' && c.dataset.k === key) { tpl = c; break; }
    if (!tpl) return null;
    var box = mk('div', 'hf ' + (isHeader ? 'hdr' : 'ftr'));
    box.style.left = g.ml + 'px';
    box.style.width = (g.w - g.ml - g.mr) + 'px';
    if (isHeader) box.style.top = g.hd + 'px'; else box.style.bottom = g.fd + 'px';
    box.appendChild(tpl.content.cloneNode(true));
    collapseSpacing(box);
    page.appendChild(box);
    fill(box, page);
    layoutTabs(box);
    return box;
  }

  function fill(scope, page) {
    var t = pageText(+page.dataset.n, page.dataset.fmt), a = scope.querySelectorAll('.f-page'), i;
    for (i = 0; i < a.length; i++) a[i].textContent = t;
    if (total) {
      a = scope.querySelectorAll('.f-pages');
      for (i = 0; i < a.length; i++) a[i].textContent = String(total);
    }
  }

  function marks(page, g) {
    var L = 18, pos = [['tl', g.ml - L, g.mt - L], ['tr', g.w - g.mr, g.mt - L], ['bl', g.ml - L, g.h - g.mb], ['br', g.w - g.mr, g.h - g.mb]];
    for (var i = 0; i < pos.length; i++) {
      var m = mk('div', 'cm ' + pos[i][0]);
      m.style.left = pos[i][1] + 'px';
      m.style.top = pos[i][2] + 'px';
      page.appendChild(m);
    }
  }

  function newPage(hard) {
    var sec = active, g = geom(sec);
    secPages++;
    shown = secPages === 1 && sec.dataset.ps ? parseInt(sec.dataset.ps, 10) : shown + 1;
    var page = mk('div', 'page');
    page.style.width = g.w + 'px';
    page.style.height = g.h + 'px';
    page.dataset.n = shown;
    page.dataset.fmt = sec.dataset.pf || '';
    page._g = g;
    out.appendChild(page);
    var kind = secPages === 1 && sec.dataset.tp === '1' ? 'first' : (EVEN_ODD && shown % 2 === 0 ? 'even' : 'default');
    var top = g.mt, bottom = g.mb;
    var hdr = headerFooter(sec, 'hdr-' + kind, page, g, true);
    var ftr = headerFooter(sec, 'ftr-' + kind, page, g, false);
    if (hdr) top = Math.max(top, g.hd + hdr.getBoundingClientRect().height);
    if (ftr) bottom = Math.max(bottom, g.fd + ftr.getBoundingClientRect().height);
    var bh = Math.max(48, g.h - top - bottom);
    var body = mk('div', 'body');
    body.style.cssText = 'left:' + g.ml + 'px;top:' + top + 'px;width:' + (g.w - g.ml - g.mr) + 'px;height:' + bh + 'px';
    var fns = mk('div', 'fns');
    body.appendChild(fns);
    page.appendChild(body);
    marks(page, g);
    cur = { page: page, body: body, fns: fns, bh: bh, n: 0, hard: !!hard, fnKey: '' };
    pages.push(page);
  }

  // ------------------------------------------------------------------------------------------------ footnotes

  function syncNotes() {
    var refs = cur.body.querySelectorAll('.fnr'), want = [], seen = {};
    for (var i = 0; i < refs.length; i++) {
      var id = refs[i].dataset.fn;
      if (refs[i].closest('.fns') || !notes[id] || seen[id]) continue;
      seen[id] = 1;
      want.push(id);
    }
    var key = want.join(',');
    if (key === cur.fnKey) return;
    cur.fnKey = key;
    cur.fns.textContent = '';
    if (!want.length) return;
    cur.fns.appendChild(mk('div', 'fn-sep'));
    for (i = 0; i < want.length; i++) cur.fns.appendChild(notes[want[i]].cloneNode(true));
    layoutTabs(cur.fns);
  }

  function limit() {
    var top = cur.body.getBoundingClientRect().top;
    return top + cur.bh - (cur.fnKey ? cur.fns.getBoundingClientRect().height : 0);
  }

  function fits() {
    var last = cur.fns.previousElementSibling;
    return !last || last.getBoundingClientRect().bottom <= limit() + 0.5;
  }

  // ------------------------------------------------------------------------------------------------ placing blocks

  function place(b, forced) {
    var cl = b.classList;
    if (cl.contains('pb')) { newPage(true); return; }
    if (b.dataset.pbb && cur.n > 0) newPage(true);
    if (cur.n === 0 && !cur.hard) cl.add('top'); else cl.remove('top');
    cur.body.insertBefore(b, cur.fns);
    cur.n++;
    if (b.querySelector('.fnr')) syncNotes();
    if (fits()) return;
    if (b.tagName === 'TABLE') splitTable(b, forced);
    else if (cl.contains('p')) splitPara(b, forced);
    else if (!forced && cur.n > 1) moveOn(b);
  }

  /** Moves [b] (and the "keep with next" paragraphs before it) to a new page. */
  function moveOn(b) {
    var chain = [], prev = b.previousElementSibling;
    while (prev && prev.dataset && prev.dataset.kn && chain.length + 1 < cur.n) { chain.unshift(prev); prev = prev.previousElementSibling; }
    if (chain.length + 1 >= cur.n) chain = [];
    for (var i = 0; i < chain.length; i++) { chain[i].remove(); cur.n--; }
    b.remove();
    cur.n--;
    syncNotes();
    newPage(false);
    for (i = 0; i < chain.length; i++) place(chain[i], true);
    place(b, true);
  }

  function collectRects(node, rects, r) {
    for (var c = node.firstChild; c; c = c.nextSibling) {
      if (c.nodeType === 3) {
        if (!c.data.length) continue;
        r.selectNodeContents(c);
        var q = r.getClientRects();
        for (var i = 0; i < q.length; i++) if (q[i].height > 0) rects.push(q[i]);
      } else if (c.nodeType === 1) {
        var cl = c.classList;
        if (cl.contains('abs') || cl.contains('flt')) continue;
        if (c.tagName === 'IMG' || c.tagName === 'BR' || cl.contains('tb') || cl.contains('ph')) {
          var b = c.getBoundingClientRect();
          if (b.height > 0) rects.push(b);
          continue;
        }
        collectRects(c, rects, r);
      }
    }
  }

  function lineBoxes(p) {
    var rects = [];
    collectRects(p, rects, document.createRange());
    rects.sort(function (a, b) { return a.top - b.top; });
    var lines = [];
    for (var i = 0; i < rects.length; i++) {
      var q = rects[i], L = lines[lines.length - 1];
      if (L && q.top < L.b - 1) { if (q.bottom > L.b) L.b = q.bottom; } else lines.push({ t: q.top, b: q.bottom });
    }
    return lines;
  }

  function charTop(node, i, r) {
    r.setStart(node, i);
    r.setEnd(node, i + 1);
    var q = r.getClientRects();
    return q.length && q[0].height > 0 ? q[0].top : null;
  }

  /** First position of the paragraph content that starts on or below [y]. */
  function splitPoint(p, y) {
    var r = document.createRange(), found = null;
    (function walk(node) {
      for (var c = node.firstChild; c && !found; c = c.nextSibling) {
        if (c.nodeType === 3) {
          var len = c.data.length;
          if (!len) continue;
          r.selectNodeContents(c);
          var q = r.getClientRects();
          if (!q.length || q[q.length - 1].top < y - 0.5) continue;
          var lo = 0, hi = len;
          while (lo < hi) {
            var mid = (lo + hi) >> 1, m2 = mid, t = charTop(c, m2, r);
            while (t === null && m2 + 1 < len) { m2++; t = charTop(c, m2, r); }
            if (t === null || t >= y - 0.5) hi = mid; else lo = m2 + 1;
          }
          if (lo < len) found = { node: c, offset: lo };
        } else if (c.nodeType === 1) {
          var cl = c.classList;
          if (cl.contains('abs') || cl.contains('flt')) continue;
          if (c.tagName === 'IMG' || c.tagName === 'BR' || cl.contains('tb') || cl.contains('ph')) {
            var b = c.getBoundingClientRect();
            if (b.height > 0 && b.top >= y - 0.5) found = { node: c, before: true };
            continue;
          }
          walk(c);
        }
      }
    })(p);
    return found;
  }

  function splitAt(p, pt) {
    var r = document.createRange();
    if (pt.before) r.setStartBefore(pt.node); else r.setStart(pt.node, pt.offset);
    r.setEnd(p, p.childNodes.length);
    var frag = r.extractContents();
    var tail = p.cloneNode(false);
    tail.removeAttribute('id');
    tail.removeAttribute('data-pbb');
    tail.classList.remove('top');
    tail.classList.add('cont');
    tail.appendChild(frag);
    p.classList.add('brk');
    p.parentNode.insertBefore(tail, p.nextSibling);
    return tail;
  }

  function splitPara(p, forced) {
    var alone = forced || cur.n <= 1;
    if (p.dataset.kl && !alone) { moveOn(p); return; }
    var lim = limit(), lines = lineBoxes(p), n = lines.length;
    if (n < 2) { if (!alone) moveOn(p); return; }
    var cs = getComputedStyle(p);
    var contentBottom = p.getBoundingClientRect().bottom - parseFloat(cs.paddingBottom) - parseFloat(cs.borderBottomWidth);
    var bottomOf = function (i) { return i < n - 1 ? (lines[i].b + lines[i + 1].t) / 2 : contentBottom; };
    var k = 0;
    while (k < n && bottomOf(k) <= lim + 0.5) k++;
    if (k >= n) return;
    var fit = k;
    if (p.dataset.wc) {
      if (n - k < 2) k = n - 2;
      if (k < 2) k = 0;
    }
    if (k <= 0) {
      if (!alone) { moveOn(p); return; }
      k = Math.max(1, Math.min(fit, n - 1));
    }
    var pt = splitPoint(p, lines[k].t);
    if (!pt) { if (!alone) moveOn(p); return; }
    var tail = splitAt(p, pt);
    syncNotes();
    newPage(false);
    place(tail, false);
  }

  /** A cell spanning rows across the cut continues as an empty cell in the first row of the next part. */
  function fixSpans(rows, cut) {
    var target = rows[cut];
    for (var i = 0; i < cut; i++) {
      var cells = rows[i].cells;
      for (var j = 0; j < cells.length; j++) {
        var c = cells[j], span = c.rowSpan || 1;
        if (span <= 1 || i + span <= cut) continue;
        var rest = i + span - cut;
        c.rowSpan = cut - i;
        var cont = c.cloneNode(false);
        cont.rowSpan = rest;
        cont.appendChild(mk('div', 'cc'));
        var col = parseInt(c.dataset.c || '0', 10), before = null;
        for (var k = 0; k < target.cells.length; k++) if (parseInt(target.cells[k].dataset.c || '0', 10) > col) { before = target.cells[k]; break; }
        target.insertBefore(cont, before);
      }
    }
  }

  function splitTable(t, forced) {
    var alone = forced || cur.n <= 1;
    var tb = t.tBodies[0];
    if (!tb) return;
    var lim = limit(), rows = Array.prototype.slice.call(tb.rows), hdr = 0, cut = -1, i;
    while (hdr < rows.length && rows[hdr].dataset.hdr) hdr++;
    if (hdr >= rows.length) hdr = 0;
    for (i = 0; i < rows.length; i++) if (rows[i].getBoundingClientRect().bottom > lim + 0.5) { cut = i; break; }
    if (cut < 0) return;
    if (cut <= hdr) {
      if (!alone) { moveOn(t); return; }
      cut = hdr + 1;
      if (cut >= rows.length) return;
    }
    fixSpans(rows, cut);
    var t2 = t.cloneNode(false), cg = t.querySelector('colgroup'), b2 = tb.cloneNode(false);
    t2.classList.remove('top');
    if (cg) t2.appendChild(cg.cloneNode(true));
    t2.appendChild(b2);
    for (i = 0; i < hdr; i++) b2.appendChild(rows[i].cloneNode(true));
    for (i = cut; i < rows.length; i++) b2.appendChild(rows[i]);
    syncNotes();
    newPage(false);
    place(t2, false);
  }

  // ------------------------------------------------------------------------------------------------ floating objects

  function placeObjects(page) {
    var els = page.querySelectorAll('.abs');
    if (!els.length) return;
    var pr = page.getBoundingClientRect(), g = page._g;
    for (var i = 0; i < els.length; i++) {
      var el = els[i], d = el.dataset;
      var host = (el.parentElement && el.parentElement.closest('.p')) || el.parentElement;
      var hr = host.getBoundingClientRect(), w = el.offsetWidth, h = el.offsetHeight, x0, x1, y0, y1;
      switch (d.hr) {
        case 'page': x0 = 0; x1 = g.w; break;
        case 'leftMargin': case 'insideMargin': x0 = 0; x1 = g.ml; break;
        case 'rightMargin': case 'outsideMargin': x0 = g.w - g.mr; x1 = g.w; break;
        case 'character': x0 = x1 = hr.left - pr.left; break;
        default: x0 = g.ml; x1 = g.w - g.mr;
      }
      switch (d.vr) {
        case 'page': y0 = 0; y1 = g.h; break;
        case 'margin': y0 = g.mt; y1 = g.h - g.mb; break;
        case 'topMargin': y0 = 0; y1 = g.mt; break;
        case 'bottomMargin': y0 = g.h - g.mb; y1 = g.h; break;
        default: y0 = hr.top - pr.top; y1 = y0 + hr.height;
      }
      var ha = d.ha, va = d.va;
      var x = ha ? (ha === 'center' ? (x0 + x1 - w) / 2 : (ha === 'right' || ha === 'outside') ? x1 - w : x0) : x0 + (parseFloat(d.hx) || 0);
      var y = va ? (va === 'center' ? (y0 + y1 - h) / 2 : (va === 'bottom' || va === 'outside') ? y1 - h : y0) : y0 + (parseFloat(d.vy) || 0);
      el.style.left = x + 'px';
      el.style.top = y + 'px';
      page.appendChild(el);
    }
  }

  // ------------------------------------------------------------------------------------------------ driver

  var si = -1, queue = [], qi = 0;

  function startSection() {
    var sec = secs[si], flow = sec.querySelector('.flow'), prev = active;
    layoutTabs(flow);
    queue = Array.prototype.slice.call(flow.children);
    qi = 0;
    // Detached, the source no longer needs a layout every time a block leaves it.
    flow.parentNode.removeChild(flow);
    var type = sec.dataset.type || 'nextPage';
    active = sec;
    secPages = 0;
    if (!cur) { newPage(true); return; }
    if (type === 'continuous') return;
    if (type === 'evenPage' || type === 'oddPage') {
      var next = sec.dataset.ps ? parseInt(sec.dataset.ps, 10) : shown + 1;
      if ((type === 'evenPage') !== (next % 2 === 0)) {
        active = prev;
        newPage(true);
        active = sec;
        secPages = 0;
      }
    }
    newPage(true);
  }

  function step() {
    var t0 = Date.now();
    while (Date.now() - t0 < 60) {
      if (qi >= queue.length) {
        si++;
        if (si >= secs.length) { finish(); return; }
        startSection();
        continue;
      }
      try { place(queue[qi++], false); } catch (e) { if (window.console) console.error(e); }
    }
    if (bridge && bridge.onProgress) bridge.onProgress(pages.length);
    // Restoring a position: show the page as soon as it is complete instead of waiting for the whole document.
    if (startPage > 0 && pages.length > startPage) { goTo(startPage); startPage = 0; }
    setTimeout(step, 0);
  }

  function finish() {
    total = pages.length;
    for (var i = 0; i < pages.length; i++) {
      var p = pages[i];
      fill(p, p);
      var hfs = p.querySelectorAll('.hf');
      for (var j = 0; j < hfs.length; j++) layoutTabs(hfs[j]);
      placeObjects(p);
    }
    if (src.parentNode) src.parentNode.removeChild(src);
    document.body.classList.add('ready');
    if (startPage > 0) { goTo(startPage); startPage = 0; }
    report(true);
    if (bridge && bridge.onReady) bridge.onReady(total);
  }

  // ------------------------------------------------------------------------------------------------ navigation

  function pageIndexAt() {
    if (!pages.length) return 0;
    var vv = window.visualViewport;
    var y = vv ? vv.pageTop + vv.height / 2 : window.scrollY + window.innerHeight / 2;
    var lo = 0, hi = pages.length - 1;
    while (lo < hi) {
      var mid = (lo + hi + 1) >> 1;
      if (pages[mid].offsetTop <= y) lo = mid; else hi = mid - 1;
    }
    return lo;
  }

  var reported = -1, raf = 0;

  function report(force) {
    raf = 0;
    if (!pages.length) return;
    var i = pageIndexAt();
    if (i !== reported || force) {
      reported = i;
      if (bridge && bridge.onPage) bridge.onPage(i + 1, String(pages[i].dataset.n || i + 1));
    }
  }

  function onScroll() { if (!raf) raf = requestAnimationFrame(function () { report(false); }); }

  function goTo(n) {
    var p = pages[Math.max(0, Math.min(pages.length - 1, n - 1))];
    if (p) p.scrollIntoView({ block: 'start' });
    setTimeout(function () { report(true); }, 60);
  }

  window.addEventListener('scroll', onScroll, { passive: true });
  if (window.visualViewport) {
    window.visualViewport.addEventListener('scroll', onScroll);
    window.visualViewport.addEventListener('resize', onScroll);
  }
  document.addEventListener('click', function (e) {
    var a = e.target && e.target.closest ? e.target.closest('a[href]') : null;
    if (!a) return;
    var href = a.getAttribute('href') || '';
    if (href.charAt(0) !== '#') return;
    e.preventDefault();
    var target = document.getElementById(decodeURIComponent(href.slice(1)));
    if (target) {
      target.scrollIntoView({ block: 'center' });
      setTimeout(function () { report(true); }, 60);
    }
  });
  window.yomuLayout = { goTo: goTo, pages: function () { return pages.length; } };

  var started = false;
  function start() { if (!started) { started = true; setTimeout(step, 0); } }
  void src.offsetHeight;
  if (document.fonts && document.fonts.ready) {
    document.fonts.ready.then(start, start);
    setTimeout(start, 5000);
  } else {
    start();
  }
})();
