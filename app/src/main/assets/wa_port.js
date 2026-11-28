/* Ported from Shirtep/WA-audio-stickers (PolyForm Noncommercial) — adapted for Android WebView */
(() => {
  const WAAS = (window.WAAS = window.WAAS || {});
  WAAS.VERSION = "android-port-1.0";
  WAAS.log = WAAS.log || ((l, e, d) => { try { console.log("[WAAS]", l, e, d || ""); } catch (_) {} });
  WAAS.maskIds = WAAS.maskIds || (s => s);
  WAAS.capabilities = WAAS.capabilities || (async () => ({}));
  WAAS._internals = WAAS._internals || {};
  WAAS._internals.sha256b64 = WAAS._internals.sha256b64 || (async bytes => {
    const h = new Uint8Array(await crypto.subtle.digest("SHA-256", bytes));
    let s = ""; for (const b of h) s += String.fromCharCode(b); return btoa(s);
  });
})();

(() => {
  const WAAS = window.WAAS;
  const log = (l, e, d) => WAAS.log(l, e, d);
  const W = (WAAS.wa = {});
  const SOUND_PROPS = [
    "sound_stickers_consumption_enabled",
    "sound_stickers_creation_enabled",
    "sound_stickers_audio_playing_enabled"
  ];

  function ns(name) {
    if (typeof window.importNamespace === "function") {
      try { const m = window.importNamespace(name); if (m) return m; } catch (_) {}
    }
    return window.require(name);
  }
  function def(name) {
    const m = ns(name);
    return m && m.default !== undefined ? m.default : m;
  }
  async function load(name, timeoutMs = 15000) {
    try { return ns(name); } catch (_) {}
    if (typeof window.requireLazy !== "function") throw new Error("module " + name + " unavailable");
    await new Promise((res, rej) => {
      const t = setTimeout(() => rej(new Error("timeout " + name)), timeoutMs);
      window.requireLazy([name], () => { clearTimeout(t); res(); });
    });
    return ns(name);
  }

  // Enable sound stickers gating locally so WhatsApp Web keeps the stickerAudio payload
  W.forceSoundStickers = function () {
    if (W._forced) return true;
    try {
      const AB = ns("WAWebABProps");
      const orig = AB.getABPropConfigValue;
      if (!orig.__waas) {
        const patched = function (name) {
          if (SOUND_PROPS.includes(name)) return true;
          return orig.apply(this, arguments);
        };
        patched.__waas = true;
        AB.getABPropConfigValue = patched;
      }
    } catch (e) {
      log("warn", "gating:abprops", e);
    }
    let G;
    try { G = ns("WAWebSoundStickerGating"); } catch (_) {}
    const ok = () => {
      try {
        return G.isSoundStickerCreationEnabled() === true &&
          G.isSoundStickerConsumptionEnabled() === true;
      } catch (_) {
        return false;
      }
    };
    if (G && !ok()) {
      try {
        G.isSoundStickerConsumptionEnabled = () => true;
        G.isSoundStickerCreationEnabled = () => true;
        G.isSoundStickerPlaybackEnabled = () => true;
      } catch (_) {}
    }
    W._forced = !!G && ok();
    return W._forced;
  };

  W.getActiveChat = () => ns("WAWebChatCollection").ChatCollection.getActive() || null;

  W.getSelfChat = async function () {
    const Me = ns("WAWebUserPrefsMeUser");
    const CC = ns("WAWebChatCollection").ChatCollection;
    let firstWid = null;
    for (const g of ["getMaybeMePnUser", "getMeUserOrThrow", "getMaybeMeLidUser"]) {
      try {
        const wid = Me[g] && Me[g]();
        if (!wid) continue;
        firstWid = firstWid || wid;
        const c = CC.get(wid) || CC.get(String(wid._serialized || wid));
        if (c) return c;
      } catch (_) {}
    }
    if (!firstWid) throw new Error("no-self-number");
    const r = await ns("WAWebFindChatAction").findOrCreateLatestChat(firstWid, "forwardSelectedModals");
    return r && (r.chat || r);
  };

  function toB64(v) {
    if (v == null) return v;
    if (typeof v === "string") return v;
    const bytes = v instanceof ArrayBuffer ? new Uint8Array(v) : new Uint8Array(v.buffer || v);
    let s = "";
    for (const b of bytes) s += String.fromCharCode(b);
    return btoa(s);
  }
  const noopQpl = new Proxy({}, { get: () => () => undefined });

  W.uploadAudio = async function (oggBlob, seconds, chat) {
    const MT = ns("WAWebMmsMediaTypes").MEDIA_TYPES;
    let uploadOrigin = 1;
    try { uploadOrigin = def("WAWebMediaGetUploadOriginForChat")(chat); } catch (_) {}
    const params = {
      blob: oggBlob,
      type: MT.AUDIO,
      signal: new AbortController().signal,
      uploadQpl: noopQpl,
      uploadOrigin,
      fileOrigin: undefined,
      userUploadAttemptCount: 0,
      forwardedFromWeb: false,
      isViewOnce: false,
      isHdPhoto: false
    };
    let res;
    try {
      const metrics = def("WAWebCreateMediaUploadMetrics")(params);
      res = await def("WAWebUploadManagerMainThread").encryptAndUpload(params, metrics);
    } catch (_) {
      res = await def("WAWebUploadManager").encryptAndUpload(params);
    }
    const bytes = new Uint8Array(await oggBlob.arrayBuffer());
    const filehash = await WAAS._internals.sha256b64(bytes);
    const stickerAudio = {
      deprecatedMms3Url: res.url || undefined,
      directPath: res.directPath,
      mediaKey: toB64(res.mediaKey),
      mediaKeyTimestamp: res.mediaKeyTimestamp,
      filehash,
      encFilehash: toB64(res.encFilehash),
      size: bytes.length,
      mimetype: "audio/ogg; codecs=opus",
      duration: seconds
    };
    let complete = null;
    try {
      complete = ns("WAWebSoundStickerUtils").isCompleteStickerAudio(stickerAudio);
    } catch (e) {
      complete = "err:" + e.message;
    }
    if (complete !== true) throw new Error("audio-invalid(" + complete + ")");
    return stickerAudio;
  };

  W.sendSticker = async function (chat, webpBlob, info, stickerAudio) {
    const Opaque = def("WAWebMediaOpaqueData");
    const MediaPrep = def("WAWebMediaPrep");
    const opaque = await Opaque.createFromData(webpBlob, "image/webp");
    const data = {
      type: "sticker",
      mediaBlob: opaque,
      mimetype: "image/webp",
      size: webpBlob.size,
      fullWidth: info.lado || 512,
      fullHeight: info.lado || 512,
      isAnimated: !!info.isAnimated,
      emojis: ["🔊"]
    };
    const prep = new MediaPrep("sticker", Promise.resolve(data));
    const res = await prep.sendToChat({ chat, options: stickerAudio ? { stickerAudio } : {} });
    const OK = ns("WAWebSendMsgResultAction").SendMsgResult.OK;
    if (!(res && res.messageSendResult === OK)) {
      throw new Error("send-failed(" + (res && res.messageSendResult) + ")");
    }
    return res.msg;
  };

  W.publish = async function ({ webp, ogg, info }, { destino = "propio" } = {}) {
    if (!W.forceSoundStickers()) log("warn", "gating", "not-forced");
    const chat = destino === "abierto" ? W.getActiveChat() : await W.getSelfChat();
    if (!chat) throw new Error("no-chat");
    const stickerAudio = (ogg && ogg.size > 0)
      ? await W.uploadAudio(ogg, info.segundosAudio, chat)
      : null;
    const msg = await W.sendSticker(chat, webp, info, stickerAudio);
    return { chat, msg };
  };
})();

/* Entry points called from Android Kotlin via WebView.evaluateJavascript */
(() => {
  function b64ToBlob(b64, type) {
    if (!b64) return null;
    const bin = atob(b64);
    const a = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) a[i] = bin.charCodeAt(i);
    return new Blob([a], { type });
  }

  window.WAAS_sendStickerB64 = async function (webpB64, oggB64, infoJson, destino) {
    try {
      if (!window.WAAS || !window.WAAS.wa) return "ERR:wa-not-loaded";
      const info = typeof infoJson === "string" ? JSON.parse(infoJson) : (infoJson || {});
      const webp = b64ToBlob(webpB64, "image/webp");
      const ogg = b64ToBlob(oggB64, "audio/ogg; codecs=opus");
      const r = await window.WAAS.wa.publish({ webp, ogg, info }, { destino: destino || "propio" });
      const msg = r && r.msg;
      return "OK:" + (msg && msg.id ? String(msg.id._serialized || msg.id) : "");
    } catch (e) {
      return "ERR:" + String((e && e.message) || e);
    }
  };

  window.__WAAS_SEND_STATE = window.__WAAS_SEND_STATE || "IDLE";
  window.WAAS_startSendB64 = function (webpB64, oggB64, infoJson, destino) {
    if (window.__WAAS_SEND_STATE === "PENDING") return "BUSY";
    window.__WAAS_SEND_STATE = "PENDING";
    window.WAAS_sendStickerB64(webpB64, oggB64, infoJson, destino)
      .then(function (res) { window.__WAAS_SEND_STATE = String(res || "ERR:empty"); })
      .catch(function (err) { window.__WAAS_SEND_STATE = "ERR:" + String((err && err.message) || err); });
    return "STARTED";
  };
})();
