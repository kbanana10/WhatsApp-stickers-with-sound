// Interfaz: panel en Shadow DOM que se abre con el botón de la extensión.
// Bilingual (Arabic RTL / English LTR), modern UI with Drag & Drop, theme toggle, and Trusted-Types-safe DOM.
(() => {
  const WAAS = (window.WAAS = window.WAAS || {});
  const log = (lvl, ev, data) => WAAS.log(lvl, ev, data);
  const t = (k, v) => (WAAS.t ? WAAS.t(k, v) : k);
  const FRAME = 1 / 30;
  const MIN_LEN = 0.3;
  const DEFAULT_LEN = 10;

  const CSS = `
  :host { all: initial; }
  * { box-sizing: border-box; font-family: system-ui, "Segoe UI", Tahoma, Arial, Roboto, sans-serif; }
  .overlay { position: fixed; inset: 0; z-index: 2147483001; background: rgba(11,20,26,.62); backdrop-filter: blur(3px);
             display: flex; align-items: center; justify-content: center; padding: 12px; }
  .panel { --bg:#ffffff; --fg:#111b21; --muted:#667781; --card:#f5f7f9; --card-hover:#eef1f4; --line:#dde3e7;
           --accent:#00a884; --accent-hover:#009675; --accent-fg:#fff; --accent-soft:rgba(0,168,132,.12);
           --ok:#25d366; --ok-bg:rgba(37,211,102,.18); --warn:#d29a00; --warn-bg:rgba(240,180,0,.18); --no:#8696a0; --err:#d13b3b;
           width: min(720px, 96vw); max-height: 94vh; overflow: auto; background: var(--bg); color: var(--fg);
           border: 1px solid var(--line); border-radius: 18px; padding: 18px 22px 16px;
           box-shadow: 0 20px 60px rgba(0,0,0,.38); font-size: 14px; line-height: 1.45; }
  .panel.dark { --bg:#111b21; --fg:#e9edef; --muted:#8696a0; --card:#1b262d; --card-hover:#222f37; --line:#2a3942;
                --accent-soft:rgba(0,168,132,.2); --ok-bg:rgba(37,211,102,.22); --warn-bg:rgba(240,180,0,.22); }
  header { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; margin-bottom: 10px; }
  .brand { display: flex; align-items: center; gap: 10px; }
  .logo-badge { width: 36px; height: 36px; border-radius: 10px; background: var(--accent-soft); color: var(--accent);
                display: flex; align-items: center; justify-content: center; font-size: 19px; flex: none; }
  h2 { margin: 0; font-size: 18px; font-weight: 700; letter-spacing: -.01em; }
  .muted { color: var(--muted); font-size: 12px; }
  .hdr-r { display: flex; align-items: center; gap: 6px; flex: none; flex-wrap: wrap; }
  .lang-seg { display: inline-flex; border: 1px solid var(--line); border-radius: 18px; overflow: hidden; background: var(--card); }
  .lang-seg button { border: none; background: transparent; color: var(--muted); padding: 4px 10px; font-size: 12px; font-weight: 600; transition: .15s; }
  .lang-seg button.on { background: var(--accent); color: var(--accent-fg); }
  .icon-btn { width: 30px; height: 30px; border-radius: 50%; border: 1px solid var(--line); background: var(--card);
              color: var(--fg); display: inline-flex; align-items: center; justify-content: center; font-size: 14px; transition: .15s; }
  .icon-btn:hover { border-color: var(--accent); }
  .kofi { display: inline-flex; align-items: center; gap: 4px; padding: 5px 11px; border-radius: 18px; background: #fff3cf; border: 1px solid #f2c14e;
          color: #7a4b00; font-size: 12px; font-weight: 700; text-decoration: none; white-space: nowrap; transition: .15s; }
  .panel.dark .kofi { background: rgba(255,196,60,.12); border-color: rgba(255,196,60,.45); color: #ffcf66; }
  .kofi:hover { background: #ffe9a8; }
  .panel.dark .kofi:hover { background: rgba(255,196,60,.22); }
  .close { background: none; border: none; font-size: 22px; color: var(--muted); line-height: 1; padding: 2px 6px; border-radius: 8px; }
  .close:hover { background: var(--card); color: var(--fg); }

  .note { color: var(--warn); font-size: 12.5px; margin-top: 6px; }
  .tabs { display: flex; gap: 6px; background: var(--card); padding: 4px; border-radius: 12px; margin: 8px 0 6px; }
  .tabs button { flex: 1; border: none; background: transparent; color: var(--muted); padding: 8px 10px; font-size: 13px;
                 font-weight: 600; border-radius: 9px; transition: .15s; white-space: nowrap; }
  .tabs button:hover:not(:disabled) { color: var(--fg); }
  .tabs button.on { background: var(--bg); color: var(--fg); box-shadow: 0 2px 6px rgba(0,0,0,.08); }
  .tabdesc { margin: 4px 2px 8px; font-size: 12.5px; }

  section { background: var(--card); border: 1px solid var(--line); border-radius: 12px; padding: 13px 15px; margin: 10px 0; transition: opacity .15s; }
  section.off { opacity: .45; pointer-events: none; }
  h3 { margin: 0 0 10px; font-size: 14px; font-weight: 700; display: flex; align-items: center; gap: 8px; }
  h3 .n { display: inline-flex; width: 22px; height: 22px; border-radius: 50%; background: var(--accent); color: var(--accent-fg);
          align-items: center; justify-content: center; font-size: 11.5px; font-weight: 700; flex: none; }
  section.done h3 .n { background: var(--ok); }

  .dropzone { border: 1.5px dashed var(--line); border-radius: 10px; padding: 11px 13px; background: var(--bg);
              display: flex; align-items: center; gap: 10px; flex-wrap: wrap; transition: border-color .15s, background .15s; }
  .dropzone.drag { border-color: var(--accent); background: var(--accent-soft); }
  .row { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; margin: 6px 0; }
  .grow { flex: 1; }
  button { font: inherit; cursor: pointer; }
  button:disabled { opacity: .45; cursor: default; }
  .act { padding: 8px 16px; border-radius: 20px; border: none; background: var(--accent); color: var(--accent-fg); font-weight: 600; transition: background .15s; }
  .act:hover:not(:disabled) { background: var(--accent-hover); }
  .sec { padding: 6px 12px; border-radius: 16px; border: 1px solid var(--line); background: var(--bg); color: var(--fg); font-size: 12.5px; font-weight: 500; transition: border-color .15s; }
  .sec:hover:not(:disabled) { border-color: var(--accent); }
  .icon { width: 28px; padding: 4px 0; text-align: center; }

  input[type=text] { width: 210px; max-width: 100%; padding: 6px 10px; border-radius: 8px; border: 1px solid var(--line); background: var(--bg); color: var(--fg); font: inherit; }
  input[type=text]:focus, input[type=number]:focus, select:focus { outline: none; border-color: var(--accent); }
  .meta { display: grid; grid-template-columns: auto 1fr; gap: 8px 12px; align-items: center; margin: 6px 0 10px; }
  input[type=number] { width: 80px; padding: 5px 8px; border-radius: 8px; border: 1px solid var(--line); background: var(--bg); color: var(--fg); font: inherit; direction: ltr; text-align: center; }
  select { padding: 6px 10px; border-radius: 8px; border: 1px solid var(--line); background: var(--bg); color: var(--fg); font: inherit; max-width: 100%; }
  label.chk { display: flex; gap: 8px; align-items: flex-start; margin: 8px 0; cursor: pointer; }
  video { display: block; width: 100%; max-height: 250px; background: #000; border-radius: 10px; }
  .thumb { width: 144px; height: 144px; object-fit: contain; border-radius: 10px; border: 1px solid var(--line);
           background: repeating-conic-gradient(#ccc 0 25%, #f5f5f5 0 50%) 50% / 16px 16px; }
  .panel.dark .thumb { background: repeating-conic-gradient(#2c3840 0 25%, #1d272d 0 50%) 50% / 16px 16px; }

  /* Keep timeline LTR even in RTL mode so time flows left-to-right accurately */
  .track { direction: ltr; position: relative; height: 36px; margin: 12px 8px 6px; background: var(--line); border-radius: 8px; cursor: pointer; touch-action: none; user-select: none; }
  .track.audio { height: 50px; }
  .track canvas { position: absolute; inset: 0; width: 100%; height: 100%; pointer-events: none; border-radius: 8px; }
  .track .sel { position: absolute; top: 0; bottom: 0; background: rgba(0,168,132,.32); border-top: 2px solid var(--accent); border-bottom: 2px solid var(--accent); pointer-events: none; }
  .track .handle { position: absolute; top: -5px; bottom: -5px; width: 14px; margin-left: -7px; background: var(--accent); border-radius: 5px; cursor: ew-resize; box-shadow: 0 2px 6px rgba(0,0,0,.25); }
  .track .handle::after { content: ""; position: absolute; left: 4px; top: 10px; bottom: 10px; width: 4px; border-left: 1px solid rgba(255,255,255,.85); border-right: 1px solid rgba(255,255,255,.85); }
  .track .ph { position: absolute; top: -7px; bottom: -7px; width: 2px; margin-left: -1px; background: var(--fg); pointer-events: none; }
  .times { direction: ltr; display: flex; justify-content: space-between; font-size: 11px; color: var(--muted); margin: 0 8px; }
  .trim { display: grid; grid-template-columns: auto auto 1fr; gap: 6px 10px; align-items: center; margin-top: 6px; }
  .pill { display: inline-flex; align-items: center; padding: 3px 9px; border-radius: 12px; background: var(--accent-soft); color: var(--accent); font-size: 12px; font-weight: 600; }

  .seg { display: inline-flex; border: 1px solid var(--line); border-radius: 18px; overflow: hidden; background: var(--bg); }
  .seg button { border: none; padding: 6px 12px; background: transparent; color: var(--fg); font-size: 12.5px; font-weight: 500; transition: .15s; }
  .seg button.on { background: var(--accent); color: var(--accent-fg); font-weight: 600; }
  .summary { background: var(--bg); border: 1px solid var(--line); border-radius: 10px; padding: 9px 12px; margin: 6px 0; font-size: 13px; }
  .grid-wrap { overflow-x: auto; }
  table.grid { direction: ltr; border-collapse: separate; border-spacing: 3px; margin: 6px 0; font-size: 11.5px; }
  table.grid th { color: var(--muted); font-weight: 600; padding: 2px 4px; text-align: center; }
  table.grid th.side { text-align: right; }
  table.grid td button { width: 52px; padding: 5px 0; border-radius: 6px; border: 1px solid transparent; font-size: 11.5px; background: transparent; color: var(--no); transition: transform .1s; }
  table.grid td button:hover:not(:disabled) { transform: scale(1.04); }
  table.grid td button.ok { background: var(--ok-bg); color: var(--fg); }
  table.grid td button.baja { background: var(--warn-bg); color: var(--fg); }
  table.grid td button.no, table.grid td button.fuera { cursor: default; opacity: .6; }
  table.grid td button.sel { border-color: var(--fg); font-weight: 700; box-shadow: 0 0 0 1px var(--fg); }
  .legend { display: flex; gap: 12px; flex-wrap: wrap; font-size: 11.5px; color: var(--muted); margin-top: 4px; }
  .legend i { display: inline-block; width: 12px; height: 12px; border-radius: 3px; vertical-align: -2px; margin-inline-end: 4px; }
  .stale { color: var(--warn); font-size: 12.5px; font-weight: 600; }
  .bar { height: 6px; background: var(--line); border-radius: 4px; overflow: hidden; margin-top: 8px; }
  .bar > div { height: 100%; width: 0; background: var(--accent); transition: width .2s; }
  .status { font-size: 13px; margin: 6px 0 0; min-height: 18px; }
  .status.err { color: var(--err); font-weight: 600; }
  .result { display: flex; gap: 14px; align-items: center; flex-wrap: wrap; margin-top: 10px; background: var(--bg); border: 1px solid var(--line); border-radius: 12px; padding: 12px; }
  details { margin-top: 10px; }
  summary { cursor: pointer; color: var(--muted); font-size: 12.5px; font-weight: 600; }
  .logbox { direction: ltr; text-align: left; max-height: 150px; overflow: auto; font: 11px/1.35 ui-monospace, Consolas, monospace; background: var(--bg); border: 1px solid var(--line); padding: 8px 10px; border-radius: 8px; white-space: pre-wrap; margin-top: 6px; }
  .logbox .e { color: var(--err); } .logbox .w { color: var(--warn); }
  .tip { margin-top: 8px; padding: 8px 11px; border-radius: 10px; background: var(--accent-soft); color: var(--fg); font-size: 12px; line-height: 1.5; }
  @media (max-width: 560px) {
    .panel { width: 99vw; padding: 14px 12px; border-radius: 14px; }
    .tabs { flex-wrap: wrap; }
    .tabs button { font-size: 12px; padding: 7px 6px; }
    .track .handle { width: 20px; margin-left: -10px; }
    .trim { grid-template-columns: auto 1fr; }
    .trim > span:nth-child(3n) { grid-column: 1 / -1; }
  }
  `;

  function h(tag, props, ...children) {
    const el = document.createElement(tag);
    if (props) {
      for (const [k, v] of Object.entries(props)) {
        if (k === "class") el.className = v;
        else if (k === "style") el.style.cssText = v;
        else if (k.startsWith("on")) el.addEventListener(k.slice(2), v);
        else if (k in el) el[k] = v;
        else el.setAttribute(k, v);
      }
    }
    for (const c of children.flat()) if (c != null) el.append(c.nodeType ? c : document.createTextNode(String(c)));
    return el;
  }

  function attachStyles(root) {
    try {
      const sheet = new CSSStyleSheet();
      sheet.replaceSync(CSS);
      root.adoptedStyleSheets = [sheet];
    } catch (e) {
      log("warn", "ui:estilos-fallback", e);
      root.append(h("style", { textContent: CSS }));
    }
  }

  function download(blob, name) {
    const a = h("a", { href: URL.createObjectURL(blob), download: name });
    document.body.append(a);
    a.click();
    setTimeout(() => { URL.revokeObjectURL(a.href); a.remove(); }, 2000);
  }

  async function saveLog() {
    const stored = await WAAS.getStoredLog();
    const entries = stored && stored.length ? stored : WAAS.entries;
    const content = JSON.stringify({ exported: new Date().toISOString(), version: WAAS.VERSION, ua: navigator.userAgent, entries }, null, 1);
    const blob = new Blob([content], { type: "application/json" });
    const name = "audio-stickers-log-" + new Date().toISOString().replace(/[:.]/g, "-").slice(0, 19) + ".json";
    if (window.showSaveFilePicker) {
      try {
        const handle = await window.showSaveFilePicker({ suggestedName: name, types: [{ description: "JSON Log", accept: { "application/json": [".json"] } }] });
        const w = await handle.createWritable();
        await w.write(blob);
        await w.close();
        log("info", "registro:guardado", { nombre: handle.name, entradas: entries.length });
        return;
      } catch (e) {
        if (e && e.name === "AbortError") return;
        log("warn", "registro:selector-fallo", e);
      }
    }
    download(blob, name);
    log("info", "registro:descargado", { nombre: name, entradas: entries.length });
  }
  WAAS.saveLog = saveLog;

  const fmtT = (sec) => (sec >= 60 ? Math.floor(sec / 60) + ":" + (sec % 60).toFixed(2).padStart(5, "0") : sec.toFixed(2) + " " + t("secUnit"));
  const fmtB = (b) => (b >= 1e6 ? (b / 1e6).toFixed(2) + " MB" : Math.round(b / 1000) + " KB");
  const isWaDark = () => {
    try {
      return document.body.classList.contains("dark") || document.documentElement.classList.contains("dark") ||
        matchMedia("(prefers-color-scheme: dark)").matches;
    } catch (e) { return false; }
  };
  const store = {
    get(k, def) { try { const v = localStorage.getItem(k); return v == null ? def : JSON.parse(v); } catch (e) { return def; } },
    set(k, v) { try { localStorage.setItem(k, JSON.stringify(v)); } catch (e) {} },
  };
  const baseName = (f) => (f && f.name.replace(/\.[^.]+$/, "")) || "sticker";

  function mount() {
    if (document.getElementById("waas-root")) return;
    const host = h("div", { id: "waas-root" });
    document.body.append(host);
    const root = host.attachShadow({ mode: "open" });
    attachStyles(root);

    const G = { busy: false, forms: [], refreshTabs: () => {}, langUpdaters: [] };
    const onLang = (fn) => G.langUpdaters.push(fn);
    const setBusy = (b) => { G.busy = b; G.forms.forEach((f) => f.refresh()); G.refreshTabs(); };
    const run = async (name, fn) => {
      const ac = new AbortController();
      G.abort = ac;
      setBusy(true);
      try { return await WAAS.run(name, () => fn(ac.signal)); } finally { G.abort = null; setBusy(false); }
    };

    // ============================================================ timeline trimmer
    function makeTrimmer(kind, hintKey) {
      const isVideo = kind === "video";
      const media = isVideo ? h("video", { playsInline: true, preload: "auto" }) : h("audio", { preload: "auto" });
      const wave = isVideo ? null : h("canvas");
      const selBar = h("div", { class: "sel" });
      const hS = h("div", { class: "handle" });
      const hE = h("div", { class: "handle" });
      const ph = h("div", { class: "ph" });
      const track = h("div", { class: "track" + (isVideo ? "" : " audio") }, wave, selBar, ph, hS, hE);
      const tLeft = h("span"), tRight = h("span");
      const playSegBtn = h("button", { class: "sec" });
      const playCurBtn = h("button", { class: "sec" });
      const playEndBtn = h("button", { class: "sec" });
      const curLbl = h("span", { class: "muted grow", style: "text-align:end" });
      const startLbl = h("span");
      const endLbl = h("span");
      const startNum = h("input", { type: "number", min: 0, step: 0.01 });
      const endNum = h("input", { type: "number", min: 0, step: 0.01 });
      const startCur = h("button", { class: "sec" });
      const endCur = h("button", { class: "sec" });
      const lenLbl = h("span", { class: "pill" });
      const hintEl = hintKey ? h("div", { class: "muted", style: "margin-top:6px" }) : null;
      const tState = { start: 0, end: 0, playhead: 0, stopAt: null, dur: 0, offset: 0, url: null, peaks: null, peakMax: 1, muted: false };
      const listeners = new Set();
      const emit = () => listeners.forEach((fn) => fn());
      const mkStep = (txt, titleKey, fn) => {
        const b = h("button", { class: "sec icon", textContent: txt, onclick: fn });
        onLang(() => { b.title = t(titleKey); });
        return b;
      };
      const el = h("div", null,
        isVideo ? media : null, track, h("div", { class: "times" }, tLeft, tRight),
        h("div", { class: "row" }, playSegBtn, playCurBtn, playEndBtn, curLbl),
        h("div", { class: "trim" },
          startLbl,
          h("span", { class: "row", style: "margin:0" }, mkStep("−", "prevFrame", () => setStart(tState.start - FRAME)), startNum, mkStep("+", "nextFrame", () => setStart(tState.start + FRAME))),
          h("span", { class: "row", style: "margin:0" }, startCur),
          endLbl,
          h("span", { class: "row", style: "margin:0" }, mkStep("−", "prevFrame", () => setEnd(tState.end - FRAME)), endNum, mkStep("+", "nextFrame", () => setEnd(tState.end + FRAME))),
          h("span", { class: "row", style: "margin:0" }, endCur, lenLbl)),
        hintEl);

      const layout = () => {
        const d = tState.dur || 1;
        const pct = (x) => Math.max(0, Math.min(100, (x / d) * 100)) + "%";
        selBar.style.left = pct(tState.start);
        selBar.style.width = Math.max(0, ((tState.end - tState.start) / d) * 100) + "%";
        hS.style.left = pct(tState.start);
        hE.style.left = pct(tState.end);
        ph.style.left = pct(tState.playhead);
        tLeft.textContent = t("zeroSec");
        tRight.textContent = fmtT(tState.dur);
        curLbl.textContent = t("cursorAt", { t: fmtT(tState.playhead) });
        if (root.activeElement !== startNum) startNum.value = tState.start.toFixed(2);
        if (root.activeElement !== endNum) endNum.value = tState.end.toFixed(2);
        lenLbl.textContent = t("durLbl", { t: fmtT(tState.end - tState.start) });
      };

      const updateLang = () => {
        hS.title = t("trimStartHandle");
        hE.title = t("trimEndHandle");
        track.title = t("trimTrackTitle");
        playSegBtn.textContent = media.paused ? t("playSeg") : t("stopPlay");
        playCurBtn.textContent = t("playFromCur");
        playEndBtn.textContent = t("playEnd");
        startLbl.textContent = t("startLbl");
        endLbl.textContent = t("endLbl");
        startCur.textContent = t("setFromCursorStart");
        startCur.title = t("setFromCursorStartTitle");
        endCur.textContent = t("setFromCursorEnd");
        endCur.title = t("setFromCursorEndTitle");
        if (hintEl) hintEl.textContent = t(hintKey);
        layout();
      };
      onLang(updateLang);

      const drawWave = () => {
        if (!wave) return;
        const w = track.clientWidth, hh = track.clientHeight;
        if (!w) return;
        const dpr = window.devicePixelRatio || 1;
        wave.width = Math.round(w * dpr); wave.height = Math.round(hh * dpr);
        const ctx = wave.getContext("2d");
        ctx.clearRect(0, 0, wave.width, wave.height);
        if (!tState.peaks) return;
        ctx.fillStyle = getComputedStyle(track).getPropertyValue("--muted").trim() || "#667781";
        const n = tState.peaks.length, cols = wave.width, mid = wave.height / 2, norm = 1 / (tState.peakMax || 1);
        for (let x = 0; x < cols; x++) {
          const a = Math.floor((x / cols) * n), b = Math.max(a + 1, Math.floor(((x + 1) / cols) * n));
          let p = 0;
          for (let i = a; i < b && i < n; i++) if (tState.peaks[i] > p) p = tState.peaks[i];
          const hgt = Math.max(1, p * norm * (wave.height - 4));
          ctx.fillRect(x, mid - hgt / 2, 1, hgt);
        }
      };
      if (wave && typeof ResizeObserver !== "undefined") new ResizeObserver(drawWave).observe(track);

      let seeking = false, pendingSeek = null;
      const seekTo = (x) => { pendingSeek = x; if (!seeking) doSeek(); };
      const doSeek = () => {
        if (pendingSeek == null) return;
        const x = pendingSeek; pendingSeek = null; seeking = true;
        tState.playhead = x;
        media.currentTime = tState.offset + x;
        layout();
      };
      media.addEventListener("seeked", () => { seeking = false; if (pendingSeek != null) doSeek(); });

      const changed = () => { layout(); emit(); };
      const setStart = (x, seek = true) => {
        if (!tState.dur) return;
        tState.start = Math.max(0, Math.min(x, tState.end - MIN_LEN));
        changed();
        if (seek) { stop(); seekTo(tState.start); }
      };
      const setEnd = (x, seek = true) => {
        if (!tState.dur) return;
        tState.end = Math.min(tState.dur, Math.max(x, tState.start + MIN_LEN));
        changed();
        if (seek) { stop(); seekTo(Math.max(tState.start, tState.end - FRAME)); }
      };

      const tAt = (ev) => {
        const r = track.getBoundingClientRect();
        return Math.max(0, Math.min(1, (ev.clientX - r.left) / r.width)) * tState.dur;
      };
      let drag = null;
      track.addEventListener("pointerdown", (ev) => {
        if (!tState.dur || G.busy) return;
        drag = ev.target === hS ? "s" : ev.target === hE ? "e" : "p";
        track.setPointerCapture(ev.pointerId);
        stop();
        applyDrag(ev);
      });
      track.addEventListener("pointermove", (ev) => { if (drag) applyDrag(ev); });
      const endDrag = () => { drag = null; };
      track.addEventListener("pointerup", endDrag);
      track.addEventListener("pointercancel", endDrag);
      const applyDrag = (ev) => {
        const x = tAt(ev);
        if (drag === "s") setStart(x);
        else if (drag === "e") setEnd(x);
        else seekTo(x);
      };
      startNum.addEventListener("change", () => setStart(+startNum.value || 0));
      endNum.addEventListener("change", () => setEnd(+endNum.value || 0));
      startCur.addEventListener("click", () => setStart(tState.playhead));
      endCur.addEventListener("click", () => setEnd(tState.playhead));

      let raf = 0;
      const tick = () => {
        tState.playhead = Math.max(0, media.currentTime - tState.offset);
        layout();
        if (tState.stopAt != null && media.currentTime >= tState.offset + tState.stopAt) { stop(); return; }
        if (!media.paused) raf = requestAnimationFrame(tick);
      };
      function stop() {
        cancelAnimationFrame(raf);
        if (!media.paused) media.pause();
        tState.stopAt = null;
        playSegBtn.textContent = t("playSeg");
      }
      const play = (from, to, muted = tState.muted) => {
        stop();
        tState.stopAt = to;
        media.muted = muted;
        seeking = false; pendingSeek = null;
        media.currentTime = tState.offset + from;
        playSegBtn.textContent = t("stopPlay");
        return media.play().then(() => { raf = requestAnimationFrame(tick); }).catch((e) => { stop(); log("warn", "ui:reproducir", e); });
      };
      const toggleRange = (from, to) => { if (!media.paused) stop(); else play(from, to); };
      playSegBtn.addEventListener("click", () => toggleRange(tState.start, tState.end));
      playCurBtn.addEventListener("click", () => toggleRange(tState.playhead >= tState.start && tState.playhead < tState.end - 0.1 ? tState.playhead : tState.start, tState.end));
      playEndBtn.addEventListener("click", () => toggleRange(Math.max(tState.start, tState.end - 2), tState.end));

      const load = (file, { duration, offset = 0, peaks = null, peakMax = 1 }) => {
        stop();
        if (tState.url) URL.revokeObjectURL(tState.url);
        tState.url = URL.createObjectURL(file);
        Object.assign(tState, { dur: duration, offset, start: 0, end: Math.min(duration, DEFAULT_LEN), playhead: 0, peaks, peakMax });
        media.addEventListener("loadeddata", () => seekTo(0), { once: true });
        media.src = tState.url;
        changed();
        requestAnimationFrame(drawWave);
      };
      const clear = () => {
        stop();
        if (tState.url) URL.revokeObjectURL(tState.url);
        tState.url = null;
        media.removeAttribute("src");
        Object.assign(tState, { dur: 0, start: 0, end: 0, playhead: 0, peaks: null });
        changed();
        drawWave();
      };

      updateLang();
      return {
        el, media, load, clear, stop, play, layout, drawWave,
        onChange: (fn) => listeners.add(fn),
        setMuted: (m) => { tState.muted = m; media.muted = m; },
        get loaded() { return tState.dur > 0; },
        get start() { return tState.start; },
        get end() { return tState.end; },
        get len() { return tState.end - tState.start; },
        get key() { return tState.start.toFixed(3) + "-" + tState.end.toFixed(3); },
      };
    }

    // ============================================================ common section + Drag&Drop picker
    function section(n, titleKey, ...body) {
      const titleSpan = h("span");
      onLang(() => { titleSpan.textContent = t(titleKey); });
      titleSpan.textContent = t(titleKey);
      return h("section", { class: "off" }, h("h3", null, h("span", { class: "n" }, String(n)), titleSpan), ...body);
    }

    function makePicker(labelKey, accept, hintKey) {
      const input = h("input", { type: "file", accept, style: "display:none" });
      const btn = h("button", { class: "act" });
      const info = h("span", { class: "muted grow" });
      const dropHint = h("span", { class: "muted" });
      let customInfo = null;
      const zone = h("div", { class: "dropzone" }, btn, info, dropHint, input);
      btn.addEventListener("click", () => input.click());
      let pickCb = () => {};
      input.addEventListener("change", () => {
        const f = input.files && input.files[0];
        input.value = "";
        if (f) pickCb(f);
      });
      zone.addEventListener("dragover", (ev) => { ev.preventDefault(); if (!G.busy) zone.classList.add("drag"); });
      zone.addEventListener("dragleave", () => zone.classList.remove("drag"));
      zone.addEventListener("drop", (ev) => {
        ev.preventDefault();
        zone.classList.remove("drag");
        if (G.busy) return;
        const f = ev.dataTransfer && ev.dataTransfer.files && ev.dataTransfer.files[0];
        if (f) pickCb(f);
      });
      const refreshText = () => {
        btn.textContent = customInfo ? t("changeFile") : t(labelKey);
        if (!customInfo) info.textContent = t(hintKey);
        dropHint.textContent = customInfo ? "" : t("dropHint");
      };
      onLang(refreshText);
      refreshText();
      return {
        el: zone, btn, info,
        setStatusText: (txt) => { customInfo = txt; info.textContent = txt || t(hintKey); refreshText(); },
        clearStatusText: () => { customInfo = null; refreshText(); },
        onPick: (fn) => { pickCb = fn; },
      };
    }

    // ============================================================ audio source
    function makeAudioSource(onChange) {
      const picker = makePicker("pickAudioBtn", "audio/*,video/*,.mp3,.m4a,.aac,.ogg,.opus,.wav,.flac,.mp4,.mov,.webm", "pickAudioHint");
      const trimmer = makeTrimmer("audio", "trimHintAudio");
      const wrap = h("div", { style: "display:none" }, trimmer.el);
      const s = { file: null, meta: null };
      const updateMeta = () => {
        if (s.file && s.meta) {
          picker.setStatusText(`${s.file.name} · ${fmtT(s.meta.duration)}${s.meta.peakMax < 0.001 ? t("noSoundDetected") : ""}`);
        }
      };
      onLang(updateMeta);
      picker.onPick(async (f) => {
        picker.setStatusText(t("readingFile", { name: f.name }));
        await run("audio", async () => {
          try {
            const a = await WAAS.probeAudio(f);
            s.file = f; s.meta = a;
            trimmer.load(f, { duration: a.duration, peaks: a.peaks, peakMax: a.peakMax });
            wrap.style.display = "";
            updateMeta();
            log("info", "ui:audio-elegido", { nombre: f.name, tipo: f.type, bytes: f.size, duracion: a.duration, canales: a.channels, pico: +a.peakMax.toFixed(4) });
          } catch (e) {
            log("error", "ui:audio-error", e);
            picker.setStatusText(t("cannotRead", { err: e.message }));
            s.file = null; s.meta = null;
            trimmer.clear();
            wrap.style.display = "none";
          }
        });
        onChange();
      });
      trimmer.onChange(onChange);
      return {
        els: [picker.el, wrap], trimmer, picker,
        get ready() { return !!s.file && trimmer.loaded; },
        get params() { return s.file ? { file: s.file, start: trimmer.start, dur: trimmer.len } : null; },
        get key() { return s.file ? s.file.name + s.file.size + "|" + trimmer.key : ""; },
      };
    }

    // ============================================================ format & grid
    function makeFormat({ getParams, grid = true, sync = true, still = false, extras = [] }) {
      const f = { mode: "ajustar", an: null, anKey: null, sel: null };
      const framingLbl = h("span");
      const segAjustar = h("button", { class: "on" });
      const segRecortar = h("button");
      const calcBtn = h("button", { class: "act" });
      const calcInfo = h("span", { class: "muted" });
      const staleLbl = h("div", { class: "stale", style: "display:none" });
      const calcBarIn = h("div");
      const calcBar = h("div", { class: "bar", style: "display:none" }, calcBarIn);
      const gridHost = h("div", { class: "grid-wrap" });
      const selLbl = h("div", { class: "row", style: "font-weight:600" });
      const legOkTxt = h("span"), legWarnTxt = h("span"), legNoTxt = h("span"), legStarTxt = h("span");
      const legend = h("div", { class: "legend" },
        h("span", null, h("i", { style: "background:var(--ok-bg);border:1px solid var(--ok)" }), legOkTxt),
        h("span", null, h("i", { style: "background:var(--warn-bg);border:1px solid var(--warn)" }), legWarnTxt),
        h("span", null, h("i", { style: "border:1px solid var(--line)" }), legNoTxt),
        legStarTxt);

      const syncLbl = h("span"), syncHint = h("span", { class: "muted" });
      const syncNum = h("input", { type: "number", min: 0, max: 1000, step: 10, value: store.get("waas-compensacion-ms", 0) });
      syncNum.addEventListener("change", () => { store.set("waas-compensacion-ms", +syncNum.value || 0); api.onUpdate(); });
      const stillChk = h("input", { type: "checkbox", onchange: () => api.onUpdate() });
      const stillTitle = h("span"), stillHint = h("div", { class: "muted" });
      const advSummary = h("summary");
      const advRows = [];
      if (sync) advRows.push(h("div", { class: "row" }, syncLbl, syncNum, syncHint));
      if (still) advRows.push(h("label", { class: "chk" }, stillChk, h("span", null, stillTitle, stillHint)));
      const advanced = advRows.length ? h("details", null, advSummary, ...advRows) : null;

      const fileId = (x) => (x ? x.name + "|" + x.size + "|" + x.lastModified : "");
      const key = () => {
        const p = getParams();
        if (!p) return null;
        const v = p.visual;
        const followsAudio = v.kind === "image" || p.fit !== "independiente";
        return JSON.stringify([v.kind, fileId(v.file), v.start, v.dur, followsAudio ? p.fit : "", followsAudio ? +p.audio.dur.toFixed(3) : "", f.mode]);
      };
      const fullKey = () => {
        const p = getParams();
        if (!p) return null;
        return JSON.stringify([key(), fileId(p.audio.file), +p.audio.start.toFixed(3), +p.audio.dur.toFixed(3), p.fit, f.sel, api.options()]);
      };
      const fresh = () => !!f.an && f.anKey === key();
      const reset = () => {
        if (f.an) WAAS.release(f.an);
        f.an = null; f.sel = null;
        gridHost.textContent = ""; selLbl.textContent = "";
        calcInfo.textContent = t("calcHint");
      };

      const renderGrid = () => {
        gridHost.textContent = "";
        const an = f.an;
        if (!an) return;
        const L = WAAS.limits;
        const thead = h("tr", null, h("th", { class: "side" }, t("pxFps")), L.FPS_LIST.map((x) => h("th", null, String(x))));
        const rows = L.SIDES.map((side) => h("tr", null, h("th", { class: "side" }, String(side)), L.FPS_LIST.map((fps) => {
          const c = an.cells.find((x) => x.side === side && x.fps === fps);
          const isAuto = an.auto && an.auto.side === side && an.auto.fps === fps;
          const isSel = f.sel && f.sel.side === side && f.sel.fps === fps;
          const can = !!c && (c.estado === "ok" || c.estado === "baja");
          const label = !c || c.estado === "fuera" ? "·" : c.estado === "no" ? "✕" : (isAuto ? "★" : "") + Math.round(c.est / 1000);
          const title = !c || c.estado === "fuera" ? t("cellUnavailable")
            : c.estado === "no" ? t("cellTooBig", { b: fmtB(c.estMin) })
            : c.estado === "baja" ? t("cellLowerQ", { b: fmtB(c.est) })
            : t("cellOk", { b: fmtB(c.est) });
          return h("td", null, h("button", {
            class: (c ? c.estado : "fuera") + (isSel ? " sel" : ""), textContent: label, title, disabled: !can,
            onclick: () => { f.sel = { side, fps }; renderGrid(); api.onUpdate(); },
          }));
        })));
        gridHost.append(h("table", { class: "grid" }, thead, rows));
        const c = f.sel && an.cells.find((x) => x.side === f.sel.side && x.fps === f.sel.fps);
        selLbl.textContent = c
          ? t("chosenCell", { side: c.side, fps: c.fps, b: fmtB(c.est) }) + (c.estado === "baja" ? t("chosenLowerQ") : "")
          : t("noCellFits");
      };

      const updateLang = () => {
        framingLbl.textContent = t("framingLbl");
        segAjustar.textContent = t("fitContain");
        segRecortar.textContent = t("fitCover");
        calcBtn.textContent = t("calcBtn");
        staleLbl.textContent = t("staleGrid");
        legOkTxt.textContent = t("legendOk");
        legWarnTxt.textContent = t("legendWarn");
        legNoTxt.textContent = t("legendNo");
        legStarTxt.textContent = t("legendStar");
        if (advanced) advSummary.textContent = t("advanced");
        syncLbl.textContent = t("syncOffsetLbl");
        syncHint.textContent = t("syncOffsetHint");
        stillTitle.textContent = t("stillChkLbl");
        stillHint.textContent = t("stillChkHint");
        if (!f.an) calcInfo.textContent = t("calcHint");
        else {
          calcInfo.textContent = t("calcReady", { s: (f.an.ms / 1000).toFixed(1) });
          renderGrid();
        }
      };
      onLang(updateLang);
      updateLang();

      segAjustar.addEventListener("click", () => { f.mode = "ajustar"; api.onUpdate(); });
      segRecortar.addEventListener("click", () => { f.mode = "recortar"; api.onUpdate(); });
      calcBtn.addEventListener("click", async () => {
        const p = getParams();
        if (!p) return;
        reset();
        const k = key();
        api.onUpdate();
        try {
          const an = await run("analizar", (signal) => WAAS.analyze({
            ...p, mode: f.mode, signal,
            onProgress: (txt, frac) => { calcInfo.textContent = txt; api.setProgress(frac); },
          }));
          f.an = an; f.anKey = k;
          f.sel = an.auto ? { side: an.auto.side, fps: an.auto.fps } : null;
          calcInfo.textContent = t("calcReady", { s: (an.ms / 1000).toFixed(1) });
          renderGrid();
        } catch (e) {
          calcInfo.textContent = e.name === "AbortError" ? t("aborted") : e.message;
        }
        api.setProgress(0);
        api.onUpdate();
      });

      const body = [...extras, h("div", { class: "row" }, framingLbl, h("span", { class: "seg" }, segAjustar, segRecortar))];
      if (grid) body.push(h("div", { class: "row" }, calcBtn, calcInfo), calcBar, staleLbl, gridHost, selLbl, legend);
      if (advanced) body.push(advanced);

      const api = {
        body,
        onUpdate: () => {},
        setProgress: (frac) => { calcBar.style.display = frac > 0 ? "" : "none"; calcBarIn.style.width = Math.round(frac * 100) + "%"; },
        refresh() {
          const has = !!getParams();
          const stale = !!f.an && !fresh();
          staleLbl.style.display = stale ? "" : "none";
          gridHost.style.opacity = stale ? ".35" : "";
          gridHost.style.pointerEvents = stale ? "none" : "";
          calcBtn.disabled = G.busy || !has;
          segAjustar.disabled = segRecortar.disabled = G.busy;
          segAjustar.classList.toggle("on", f.mode === "ajustar");
          segRecortar.classList.toggle("on", f.mode === "recortar");
        },
        get ready() { return grid ? fresh() && !!f.sel : !!getParams(); },
        reset,
        fullKey,
        options: () => ({ syncMs: sync ? +syncNum.value || 0 : 0, still: still && stillChk.checked }),
        async prepare(signal, onProgress) {
          const p = getParams();
          if (grid) return { an: f.an, side: f.sel.side, fps: f.sel.fps, audio: p.audio, temp: false };
          const an = await WAAS.analyze({ ...p, mode: f.mode, signal, onProgress });
          return { an, side: an.auto.side, fps: an.auto.fps, audio: p.audio, temp: true };
        },
      };
      return api;
    }

    // ============================================================ create & send
    function makeCreate({ fmt, getBase }) {
      const META_KEY = "waas-metadatos";
      const metaHint = h("div", { class: "muted" });
      const packLbl = h("span"), authorLbl = h("span");
      const packInput = h("input", { type: "text", maxLength: 128 });
      const authorInput = h("input", { type: "text", maxLength: 128 });
      const loadMeta = () => { const m = store.get(META_KEY, {}) || {}; packInput.value = m.paquete || ""; authorInput.value = m.autor || ""; };
      const saveMeta = () => store.set(META_KEY, { paquete: packInput.value, autor: authorInput.value });
      loadMeta();
      packInput.addEventListener("change", saveMeta);
      authorInput.addEventListener("change", saveMeta);

      const createBtn = h("button", { class: "act" });
      const cancelBtn = h("button", { class: "sec", style: "display:none" });
      const barIn = h("div");
      const status = h("div", { class: "status" });
      const resImg = h("img", { class: "thumb" });
      const resAudio = h("audio", { controls: true });
      const resInfo = h("div", { class: "muted", style: "max-width:380px" });
      const togetherBtn = h("button", { class: "sec" });
      const optSelfOnly = h("option", { value: "propio-solo" });
      const optOpenOnly = h("option", { value: "abierto-solo" });
      const optOpenFwd = h("option", { value: "abierto" });
      const optSelfFwd = h("option", { value: "propio" });
      const destSel = h("select", null, optSelfOnly, optOpenOnly, optOpenFwd, optSelfFwd);
      const favTip = h("div", { class: "tip" });
      const sendBtn = h("button", { class: "act" });
      const dlBtn = h("button", { class: "sec" });
      const staleNote = h("div", { class: "stale", style: "display:none;margin-top:8px" });
      const resultBox = h("div", { class: "result" }, resImg, h("div", { class: "grow" }, resAudio, h("div", { class: "row" }, togetherBtn), resInfo));
      const result = h("div", { style: "display:none" }, staleNote, resultBox,
        h("div", { class: "row", style: "margin-top:10px" }, destSel, sendBtn, dlBtn), favTip);
      const c = { result: null, key: null, urls: [] };
      const setStatus = (txt, frac, isErr) => {
        status.textContent = txt || "";
        status.className = "status" + (isErr ? " err" : "");
        if (frac != null) barIn.style.width = Math.round(frac * 100) + "%";
      };
      const revoke = () => { c.urls.forEach((u) => URL.revokeObjectURL(u)); c.urls = []; };
      const clear = () => { c.result = null; c.key = null; result.style.display = "none"; revoke(); setStatus("", 0); };

      const renderResInfo = () => {
        if (!c.result) return;
        const i = c.result.info;
        const durTxt = Math.abs(i.duracion - i.duracionAudio) > 0.05
          ? t("resInfoDurDiff", { v: fmtT(i.duracion), a: fmtT(i.duracionAudio) })
          : fmtT(i.duracion);
        resInfo.textContent = `${durTxt} · ${i.lado} px${i.fps ? " · " + i.fps + " fps" : ""}${i.isAnimated ? "" : t("resInfoFixed")}${t("resInfoQuality", { q: Math.round(i.calidad * 100) })}${t("resInfoSticker", { b: fmtB(i.webpBytes) })}${i.estimado ? t("resInfoEst", { b: fmtB(i.estimado) }) : ""}${t("resInfoAudio", { b: fmtB(i.oggBytes), kbps: i.audioKbps })}`;
      };

      const updateLang = () => {
        metaHint.textContent = t("metaHint");
        packLbl.textContent = t("packLbl");
        packInput.placeholder = t("packPh");
        authorLbl.textContent = t("authorLbl");
        authorInput.placeholder = t("authorPh");
        cancelBtn.textContent = t("cancelBtn");
        resImg.alt = t("previewAlt");
        togetherBtn.textContent = t("playTogetherBtn");
        optSelfOnly.textContent = t("destSelfOnly");
        optOpenOnly.textContent = t("destOpenOnly");
        favTip.textContent = t("favTip");
        optOpenFwd.textContent = t("destOpenForward");
        optSelfFwd.textContent = t("destSelfForward");
        sendBtn.textContent = t("sendBtn");
        dlBtn.textContent = t("dlBtn");
        staleNote.textContent = t("staleResult");
        renderResInfo();
        api.refresh();
      };
      onLang(updateLang);

      createBtn.addEventListener("click", async () => {
        if (!fmt.ready) return;
        result.style.display = "none";
        try {
          const k = fmt.fullKey();
          const res = await run("crear", async (signal) => {
            const p = await fmt.prepare(signal, (txt, f) => setStatus(txt, f * 0.3));
            try {
              return await WAAS.create(p.an, {
                side: p.side, fps: p.fps, audio: p.audio, ...fmt.options(),
                packName: packInput.value, publisher: authorInput.value,
                onProgress: (txt, f) => setStatus(txt, f), signal,
              });
            } finally {
              if (p.temp) WAAS.release(p.an);
            }
          });
          c.result = res;
          c.key = k;
          revoke();
          const u1 = URL.createObjectURL(res.webp), u2 = URL.createObjectURL(res.ogg);
          c.urls.push(u1, u2);
          resImg.src = u1; resAudio.src = u2;
          renderResInfo();
          result.style.display = "";
          setStatus(t("stickerReady"), 1);
        } catch (e) {
          setStatus(e.name === "AbortError" ? t("aborted") : e.message, 0, e.name !== "AbortError");
        }
        api.onUpdate();
      });
      cancelBtn.addEventListener("click", () => { if (G.abort) G.abort.abort(); });

      togetherBtn.addEventListener("click", () => {
        if (!c.result) return;
        const u = resImg.src;
        resImg.src = "";
        resImg.src = u;
        resAudio.currentTime = 0;
        resAudio.play().catch((e) => log("warn", "ui:reproducir", e));
      });

      sendBtn.addEventListener("click", async () => {
        if (!c.result) return;
        const dest = destSel.value;
        try {
          await run("enviar", async () => {
            const i = c.result.info;
            if (packInput.value.trim() !== (i.paquete || "") || authorInput.value.trim() !== (i.autor || "")) {
              c.result.webp = await WAAS.retagWebp(c.result.webp, { packName: packInput.value, publisher: authorInput.value, soundSha: i.soundSha256 });
              i.paquete = packInput.value.trim(); i.autor = authorInput.value.trim();
              log("info", "metadatos:actualizados", { paquete: i.paquete, autor: i.autor, webpBytes: c.result.webp.size });
            }
            saveMeta();
            await WAAS.wa.publish(c.result, {
              destino: dest.startsWith("propio") ? "propio" : "abierto",
              abrirReenviar: dest === "abierto" || dest === "propio",
              onProgress: (txt) => setStatus(txt),
            });
          });
          setStatus(dest.endsWith("-solo") ? t("sentOk") : t("sentForwardOk"), 1);
          api.onSent();
        } catch (e) {
          setStatus(t("sendErr", { err: e.message }), 0, true);
        }
      });
      dlBtn.addEventListener("click", () => {
        if (!c.result) return;
        const base = getBase();
        download(c.result.webp, base + ".webp");
        download(c.result.ogg, base + ".ogg");
        log("info", "ui:descarga-archivos", { base });
      });

      const api = {
        body: [
          h("div", null, metaHint, h("div", { class: "meta" }, packLbl, packInput, authorLbl, authorInput)),
          h("div", { class: "row" }, createBtn, cancelBtn), h("div", { class: "bar" }, barIn), status, result,
        ],
        onSent: () => {},
        onUpdate: () => {},
        setStatus, clear, loadMeta,
        get hasResult() { return !!c.result; },
        refresh() {
          const stale = !!c.result && c.key !== fmt.fullKey();
          const done = !!c.result && !stale;
          createBtn.textContent = done ? t("createDoneBtn") : c.result ? t("createUpdateBtn") : t("createBtn");
          createBtn.disabled = G.busy || !fmt.ready || done;
          staleNote.style.display = stale ? "" : "none";
          resultBox.style.opacity = stale ? ".45" : "";
          sendBtn.disabled = G.busy || !c.result;
          cancelBtn.style.display = G.busy && api.isActive() ? "" : "none";
        },
        isActive: () => true,
      };
      updateLang();
      return api;
    }

    // ============================================================ form builder
    function makeForm({ id, titleKey, descKey, sources, fmt, getBase, onShow, stop }) {
      const create = makeCreate({ fmt, getBase });
      let n = 0;
      const secs = sources.map((s) => section(++n, s.titleKey, ...s.body));
      const secFmt = section(++n, "secFormat", ...fmt.body);
      const secCreate = section(++n, "secCreate", ...create.body);
      const descEl = h("div", { class: "muted tabdesc" });
      onLang(() => { descEl.textContent = t(descKey); });
      descEl.textContent = t(descKey);
      const el = h("div", { style: "display:none" }, descEl, ...secs, secFmt, secCreate);
      const form = {
        id, titleKey, el, create, stop,
        refresh() {
          sources.forEach((s, i) => {
            const ready = s.ready();
            secs[i].classList.toggle("off", G.busy || (s.enabled ? !s.enabled() : false));
            secs[i].classList.toggle("done", ready);
          });
          const allReady = sources.every((s) => s.ready());
          secFmt.classList.toggle("off", !allReady);
          secFmt.classList.toggle("done", fmt.ready);
          secCreate.classList.toggle("off", !fmt.ready && !create.hasResult);
          secCreate.classList.toggle("done", create.hasResult);
          fmt.refresh();
          create.refresh();
        },
        show(on) { el.style.display = on ? "" : "none"; if (on) { create.loadMeta(); if (onShow) onShow(); } else stop(); },
      };
      fmt.onUpdate = () => form.refresh();
      create.onUpdate = () => form.refresh();
      create.isActive = () => el.style.display !== "none";
      create.onSent = () => close(true);
      return form;
    }

    // ============================================================ tab 1: video with sound
    function videoForm() {
      const picker = makePicker("pickVideoBtn", "video/*,.mov,.mp4,.webm,.mkv", "pickVideoHint");
      const trimmer = makeTrimmer("video", "trimHintVideo");
      const s = { file: null, meta: null };
      const changed = () => form.refresh();
      const fmt = makeFormat({
        getParams: () => (s.file && trimmer.loaded ? {
          visual: { kind: "video", file: s.file, start: trimmer.start, dur: trimmer.len },
          audio: { file: s.file, start: trimmer.start, dur: trimmer.len }, fit: "repetir",
        } : null),
      });
      onLang(() => {
        if (s.file && s.meta) picker.setStatusText(`${s.file.name} · ${fmtT(s.meta.duration)} · ${s.meta.width}×${s.meta.height}`);
      });
      picker.onPick(async (f) => {
        trimmer.stop();
        fmt.reset();
        form.create.clear();
        picker.setStatusText(t("readingFile", { name: f.name }));
        await run("video", async () => {
          try {
            const meta = await WAAS.probeVideo(f);
            s.file = f; s.meta = meta;
            trimmer.load(f, meta);
            picker.setStatusText(`${f.name} · ${fmtT(meta.duration)} · ${meta.width}×${meta.height}`);
            log("info", "ui:video-elegido", { nombre: f.name, tipo: f.type, bytes: f.size, ...meta });
          } catch (e) {
            log("error", "ui:video-error", e);
            picker.setStatusText(t("cannotRead", { err: e.message }));
            s.file = null; s.meta = null;
            trimmer.clear();
          }
        });
        changed();
      });
      trimmer.onChange(changed);
      const form = makeForm({
        id: "video", titleKey: "tabVideo", descKey: "tabVideoDesc",
        sources: [
          { titleKey: "secVideo", body: [picker.el], ready: () => !!s.file },
          { titleKey: "secTrim", body: [trimmer.el], ready: () => trimmer.loaded, enabled: () => trimmer.loaded },
        ],
        fmt, getBase: () => baseName(s.file), stop: () => trimmer.stop(),
      });
      return form;
    }

    // ============================================================ tab 2: image + audio
    function imageForm() {
      const picker = makePicker("pickImageBtn", "image/*", "pickImageHint");
      const thumb = h("img", { class: "thumb", style: "display:none;margin-top:8px" });
      const animNote = h("div", { class: "note", style: "display:none" });
      const s = { file: null, meta: null, url: null };
      const changed = () => form.refresh();
      const audio = makeAudioSource(changed);
      const fmt = makeFormat({
        grid: false, sync: false, still: true,
        getParams: () => (s.file && audio.ready ? { visual: { kind: "image", file: s.file }, audio: audio.params, fit: "repetir" } : null),
      });
      onLang(() => {
        animNote.textContent = t("animImageNote");
        if (s.file && s.meta) picker.setStatusText(`${s.file.name} · ${s.meta.width}×${s.meta.height}`);
      });
      animNote.textContent = t("animImageNote");
      picker.onPick(async (f) => {
        form.create.clear();
        picker.setStatusText(t("readingFile", { name: f.name }));
        await run("imagen", async () => {
          try {
            const meta = await WAAS.probeImage(f);
            s.file = f; s.meta = meta;
            if (s.url) URL.revokeObjectURL(s.url);
            s.url = URL.createObjectURL(f);
            thumb.src = s.url; thumb.style.display = "";
            animNote.style.display = meta.animated ? "" : "none";
            picker.setStatusText(`${f.name} · ${meta.width}×${meta.height}`);
            log("info", "ui:imagen-elegida", { nombre: f.name, tipo: f.type, bytes: f.size, ...meta });
          } catch (e) {
            log("error", "ui:imagen-error", e);
            picker.setStatusText(t("cannotRead", { err: e.message }));
            s.file = null; s.meta = null;
            thumb.style.display = "none"; animNote.style.display = "none";
          }
        });
        changed();
      });
      const form = makeForm({
        id: "imagen", titleKey: "tabImage", descKey: "tabImageDesc",
        sources: [
          { titleKey: "secImage", body: [picker.el, thumb, animNote], ready: () => !!s.file },
          { titleKey: "secAudio", body: audio.els, ready: () => audio.ready },
        ],
        fmt, getBase: () => baseName(s.file), stop: () => audio.trimmer.stop(),
      });
      return form;
    }

    // ============================================================ tab 3: video or GIF + audio
    function mixForm() {
      const picker = makePicker("pickMixBtn", "video/*,image/gif,image/webp,image/apng,.gif,.mov,.mp4,.webm,.mkv", "pickMixHint");
      const vTrim = makeTrimmer("video", "trimHintVideo");
      vTrim.setMuted(true);
      const vWrap = h("div", { style: "display:none" }, vTrim.el);
      const gifImg = h("img", { class: "thumb", style: "display:none;margin-top:8px" });
      const s = { file: null, kind: null, gif: null, meta: null, url: null };
      const changed = () => { updateSummary(); form.refresh(); };
      const audio = makeAudioSource(changed);

      const FIT_KEY = "waas-ajuste-duracion-v2";
      const optIndep = h("option", { value: "independiente" });
      const optRepeat = h("option", { value: "repetir" });
      const optFreeze = h("option", { value: "congelar" });
      const fitSel = h("select", null, optIndep, optRepeat, optFreeze);
      fitSel.value = store.get(FIT_KEY, "independiente");
      fitSel.addEventListener("change", () => { store.set(FIT_KEY, fitSel.value); changed(); });
      const summary = h("div", { class: "summary" });
      const diffLbl = h("span");
      const fitRow = h("div", null, h("div", { class: "row" }, diffLbl, fitSel));
      const testBtn = h("button", { class: "sec" });
      const testHint = h("span", { class: "muted" });
      const vDur = () => (s.kind === "gif" ? s.gif.duration : vTrim.len);

      function updateSummary() {
        summary.textContent = "";
        if (!s.file || !audio.ready) { summary.append(t("mixSummaryEmpty")); fitRow.style.display = "none"; return; }
        const v = vDur(), a = audio.params.dur;
        const same = Math.abs(v - a) < 0.05;
        fitRow.style.display = same ? "none" : "";
        if (same) { summary.append(t("mixSummarySame", { t: fmtT(a) })); return; }
        const fit = fitSel.value;
        let txt = t("mixSummaryBase", { v: fmtT(v), a: fmtT(a) });
        if (fit === "independiente") txt += t("mixFitIndep");
        else if (v > a) txt += t("mixFitCut", { a: fmtT(a) });
        else if (fit === "repetir") txt += t("mixFitRepeat", { a: fmtT(a), n: (a / v).toFixed(1) });
        else txt += t("mixFitFreeze", { a: fmtT(a), v: fmtT(v) });
        summary.append(txt);
      }

      onLang(() => {
        optIndep.textContent = t("fitIndepOpt");
        optRepeat.textContent = t("fitRepeatOpt");
        optFreeze.textContent = t("fitFreezeOpt");
        diffLbl.textContent = t("ifDiffDur");
        testBtn.textContent = t("testTogether");
        testHint.textContent = t("testTogetherHint");
        if (s.file && s.kind === "gif" && s.gif) {
          picker.setStatusText(t("gifMeta", { name: s.file.name, dur: fmtT(s.gif.duration), frames: s.gif.frames, w: s.gif.width, h: s.gif.height }));
        } else if (s.file && s.kind === "video" && s.meta) {
          picker.setStatusText(`${s.file.name} · ${fmtT(s.meta.duration)} · ${s.meta.width}×${s.meta.height}`);
        }
        updateSummary();
      });
      optIndep.textContent = t("fitIndepOpt");
      optRepeat.textContent = t("fitRepeatOpt");
      optFreeze.textContent = t("fitFreezeOpt");
      diffLbl.textContent = t("ifDiffDur");
      testBtn.textContent = t("testTogether");
      testHint.textContent = t("testTogetherHint");

      testBtn.addEventListener("click", () => {
        if (!s.file || !audio.ready) return;
        const a = audio.params;
        if (s.kind === "gif") { const u = gifImg.src; gifImg.src = ""; gifImg.src = u; }
        else vTrim.play(vTrim.start, vTrim.end, true);
        audio.trimmer.play(a.start, a.start + a.dur, false);
      });

      const fmt = makeFormat({
        extras: [summary, fitRow, h("div", { class: "row" }, testBtn, testHint)],
        getParams: () => {
          if (!s.file || !audio.ready) return null;
          const visual = s.kind === "gif" ? { kind: "gif", file: s.file, start: 0, dur: s.gif.duration } : { kind: "video", file: s.file, start: vTrim.start, dur: vTrim.len };
          return { visual, audio: audio.params, fit: fitSel.value };
        },
      });

      picker.onPick(async (f) => {
        vTrim.stop();
        fmt.reset();
        form.create.clear();
        picker.setStatusText(t("readingFile", { name: f.name }));
        await run("animacion", async () => {
          try {
            if (s.url) { URL.revokeObjectURL(s.url); s.url = null; }
            if (WAAS.isAnimationFile(f) || (f.type || "").startsWith("image/")) {
              const g = await WAAS.probeAnimation(f);
              if (!g.animated) throw new Error(t("animImageNote"));
              s.file = f; s.kind = "gif"; s.gif = g; s.meta = null;
              s.url = URL.createObjectURL(f);
              gifImg.src = s.url; gifImg.style.display = ""; vWrap.style.display = "none";
              vTrim.clear();
              picker.setStatusText(t("gifMeta", { name: f.name, dur: fmtT(g.duration), frames: g.frames, w: g.width, h: g.height }));
              log("info", "ui:gif-elegido", { nombre: f.name, tipo: f.type, bytes: f.size, cuadros: g.frames, duracion: g.duration, ancho: g.width, alto: g.height });
            } else {
              const meta = await WAAS.probeVideo(f);
              s.file = f; s.kind = "video"; s.gif = null; s.meta = meta;
              vTrim.load(f, meta);
              vWrap.style.display = ""; gifImg.style.display = "none";
              picker.setStatusText(`${f.name} · ${fmtT(meta.duration)} · ${meta.width}×${meta.height}`);
              log("info", "ui:video-elegido", { nombre: f.name, tipo: f.type, bytes: f.size, sinSuAudio: true, ...meta });
            }
          } catch (e) {
            log("error", "ui:animacion-error", e);
            picker.setStatusText(t("cannotUse", { err: e.message }));
            s.file = null; s.kind = null; s.gif = null; s.meta = null;
            vTrim.clear(); vWrap.style.display = "none"; gifImg.style.display = "none";
          }
        });
        changed();
      });
      vTrim.onChange(changed);
      updateSummary();

      const form = makeForm({
        id: "mezcla", titleKey: "tabMix", descKey: "tabMixDesc",
        sources: [
          { titleKey: "secAnimation", body: [picker.el, vWrap, gifImg], ready: () => !!s.file },
          { titleKey: "secAudio", body: audio.els, ready: () => audio.ready },
        ],
        fmt, getBase: () => baseName(s.file),
        stop: () => { vTrim.stop(); audio.trimmer.stop(); },
      });
      return form;
    }

    // ============================================================ panel & controls
    G.forms = [videoForm(), imageForm(), mixForm()];
    const TAB_KEY = "waas-pestana";
    let active = Math.max(0, G.forms.findIndex((f) => f.id === store.get(TAB_KEY, "video")));
    const tabBtns = G.forms.map((f, i) => h("button", { onclick: () => selectTab(i) }));
    function selectTab(i) {
      if (G.busy) return;
      active = i;
      store.set(TAB_KEY, G.forms[i].id);
      G.forms.forEach((f, j) => f.show(j === i));
      G.refreshTabs();
      G.forms[i].refresh();
      log("info", "ui:pestana", { pestana: G.forms[i].id });
    }
    G.refreshTabs = () => tabBtns.forEach((b, i) => { b.classList.toggle("on", i === active); b.disabled = G.busy && i !== active; });

    const logBox = h("div", { class: "logbox" });
    const toolsSummary = h("summary");
    const diagBtn = h("button", { class: "sec", onclick: () => WAAS.wa.diagnose().catch((e) => log("error", "diagnostico:error", e)) });
    const saveLogBtn = h("button", { class: "sec", onclick: () => saveLog().catch((e) => log("error", "registro:error", e)) });
    const viewLogBtn = h("button", { class: "sec", onclick: () => window.postMessage({ __waas: "openLog" }, location.origin) });
    const tools = h("details", null, toolsSummary, h("div", { class: "row" }, diagBtn, saveLogBtn, viewLogBtn), logBox);

    const titleEl = h("h2");
    const subEl = h("div", { class: "muted" });
    const kofiEl = h("a", { class: "kofi", href: "https://ko-fi.com/shirtep", target: "_blank", rel: "noopener" });
    const btnAr = h("button", { textContent: "العربية", onclick: () => WAAS.setLang("ar") });
    const btnEn = h("button", { textContent: "EN", onclick: () => WAAS.setLang("en") });
    const langSeg = h("div", { class: "lang-seg" }, btnAr, btnEn);

    const THEME_KEY = "waas-theme";
    let themePref = store.get(THEME_KEY, "auto");
    const themeBtn = h("button", { class: "icon-btn", onclick: () => {
      const curDark = panel.classList.contains("dark");
      themePref = curDark ? "light" : "dark";
      store.set(THEME_KEY, themePref);
      applyTheme();
    } });
    const applyTheme = () => {
      const dark = themePref === "dark" ? true : themePref === "light" ? false : isWaDark();
      panel.classList.toggle("dark", dark);
      themeBtn.textContent = dark ? "☀️" : "🌙";
      themeBtn.title = dark ? t("themeLight") : t("themeDark");
    };

    const closeBtn = h("button", { class: "close", textContent: "×" });
    const panel = h("div", { class: "panel" },
      h("header", null,
        h("div", { class: "brand" },
          h("div", { class: "logo-badge", textContent: "🔊" }),
          h("div", null, titleEl, subEl)),
        h("div", { class: "hdr-r" }, langSeg, themeBtn, kofiEl, closeBtn)),
      h("nav", { class: "tabs" }, tabBtns),
      G.forms.map((f) => f.el), tools);
    const overlay = h("div", { class: "overlay", style: "display:none" }, panel);
    root.append(overlay);

    const applyPanelLang = () => {
      const lang = WAAS.getLang ? WAAS.getLang() : "ar";
      panel.dir = lang === "ar" ? "rtl" : "ltr";
      panel.lang = lang;
      btnAr.classList.toggle("on", lang === "ar");
      btnEn.classList.toggle("on", lang === "en");
      titleEl.textContent = t("appTitle");
      subEl.textContent = t("appSubtitle", { ver: WAAS.VERSION });
      kofiEl.textContent = t("supportBtn");
      kofiEl.title = t("supportTitle");
      closeBtn.title = t("closeTitle");
      tabBtns.forEach((b, i) => { b.textContent = t(G.forms[i].titleKey); });
      toolsSummary.textContent = t("toolsSummary");
      diagBtn.textContent = t("diagBtn");
      saveLogBtn.textContent = t("saveLogBtn");
      viewLogBtn.textContent = t("viewLogBtn");
      applyTheme();
      G.langUpdaters.forEach((fn) => { try { fn(lang); } catch (e) {} });
    };
    if (WAAS.onLangChange) WAAS.onLangChange(applyPanelLang);
    applyPanelLang();

    const showEntry = (e) => {
      logBox.append(h("div", { class: e.level === "error" ? "e" : e.level === "warn" ? "w" : "" },
        e.ts.slice(11, 19) + " " + e.event + (e.data != null ? " " + JSON.stringify(e.data).slice(0, 260) : "")));
      while (logBox.childNodes.length > 200) logBox.firstChild.remove();
      logBox.scrollTop = logBox.scrollHeight;
    };
    WAAS.entries.slice(-30).forEach(showEntry);
    WAAS.onLog(showEntry);

    const open = () => {
      applyTheme();
      overlay.style.display = "";
      G.forms[active].show(true);
      log("info", "ui:abrir", null);
    };
    function close(force) {
      if (G.busy && force !== true) return;
      G.forms.forEach((f) => f.stop());
      overlay.style.display = "none";
    }
    const toggle = () => (overlay.style.display === "none" ? open() : close());
    window.addEventListener("message", (ev) => {
      if (ev.source === window && ev.data && ev.data.__waas === "togglePanel") toggle();
    });
    window.addEventListener("keydown", (ev) => {
      if (ev.key === "Escape" && overlay.style.display !== "none") close();
    });
    WAAS.togglePanel = toggle;
    closeBtn.addEventListener("click", close);
    overlay.addEventListener("click", (e) => { if (e.target === overlay) close(); });

    selectTab(active);
    log("info", "ui:montada", null);
    window.postMessage({ __waas: "uiReady" }, location.origin);
  }

  WAAS.mountUI = mount;
})();
