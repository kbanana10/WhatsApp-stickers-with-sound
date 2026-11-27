const $ = (id) => document.getElementById(id);
let onlyErrors = false;
let entries = [];
let lang = "ar";
try {
  const saved = localStorage.getItem("waas-lang");
  if (saved === "ar" || saved === "en") lang = saved;
} catch (e) {}

const I18N = {
  ar: {
    pageTitle: "ملصقات بصوت: السجل",
    subtitle: "إذا حدث أي خطأ، حمّل السجل وشاركه للمساعدة في حل المشكلة. أرقام الهواتف مخفية تلقائياً.",
    dl: "تحميل السجل (.json)",
    copy: "نسخ",
    copied: "تم النسخ ✓",
    onlyErr: "الأخطاء فقط",
    showAll: "عرض الكل",
    clear: "مسح السجل",
    confirmClear: "هل تريد مسح السجل بالكامل؟",
    summary: (n, e, w, s) => `${n} سجل · ${e} أخطاء · ${w} تحذيرات · ${s} جلسات`,
  },
  en: {
    pageTitle: "Audio Stickers: Log",
    subtitle: "If something fails, download the log and share it for troubleshooting. Phone numbers are masked.",
    dl: "Download log (.json)",
    copy: "Copy",
    copied: "Copied ✓",
    onlyErr: "Errors only",
    showAll: "Show all",
    clear: "Clear log",
    confirmClear: "Clear the entire log?",
    summary: (n, e, w, s) => `${n} entries · ${e} errors · ${w} warnings · ${s} sessions`,
  },
};

function applyLang() {
  const L = I18N[lang];
  document.documentElement.lang = lang;
  document.documentElement.dir = lang === "ar" ? "rtl" : "ltr";
  document.title = L.pageTitle;
  $("title").textContent = L.pageTitle;
  $("subtitle").textContent = L.subtitle;
  $("dl").textContent = L.dl;
  $("copy").textContent = L.copy;
  $("errors").textContent = onlyErrors ? L.showAll : L.onlyErr;
  $("clear").textContent = L.clear;
  $("lang-ar").classList.toggle("on", lang === "ar");
  $("lang-en").classList.toggle("on", lang === "en");
  render();
}

function render() {
  const L = I18N[lang];
  const box = $("log");
  box.textContent = "";
  const list = (onlyErrors ? entries.filter((e) => e.level !== "info") : entries).slice(-2000);
  for (const e of list) {
    const div = document.createElement("div");
    div.className = e.level === "error" ? "e" : e.level === "warn" ? "w" : "";
    div.textContent = `${e.ts.replace("T", " ").slice(0, 19)} [${e.level}] ${e.event}${e.data != null ? " " + JSON.stringify(e.data) : ""}`;
    box.append(div);
  }
  box.scrollTop = box.scrollHeight;
  const errs = entries.filter((e) => e.level === "error").length;
  const warns = entries.filter((e) => e.level === "warn").length;
  const sessions = new Set(entries.map((e) => e.session)).size;
  $("summary").textContent = L.summary(entries.length, errs, warns, sessions);
}

function load() {
  chrome.storage.local.get({ log: [] }, (res) => { entries = res.log || []; render(); });
}

$("lang-ar").addEventListener("click", () => {
  lang = "ar";
  try { localStorage.setItem("waas-lang", "ar"); } catch (e) {}
  applyLang();
});
$("lang-en").addEventListener("click", () => {
  lang = "en";
  try { localStorage.setItem("waas-lang", "en"); } catch (e) {}
  applyLang();
});

$("dl").addEventListener("click", () => {
  const content = JSON.stringify({ exported: new Date().toISOString(), version: chrome.runtime.getManifest().version, ua: navigator.userAgent, entries }, null, 1);
  const a = document.createElement("a");
  a.href = URL.createObjectURL(new Blob([content], { type: "application/json" }));
  a.download = "audio-stickers-log-" + new Date().toISOString().replace(/[:.]/g, "-").slice(0, 19) + ".json";
  a.click();
});
$("copy").addEventListener("click", async () => {
  await navigator.clipboard.writeText(JSON.stringify(entries, null, 1));
  $("copy").textContent = I18N[lang].copied;
  setTimeout(() => ($("copy").textContent = I18N[lang].copy), 1500);
});
$("errors").addEventListener("click", () => {
  onlyErrors = !onlyErrors;
  $("errors").textContent = onlyErrors ? I18N[lang].showAll : I18N[lang].onlyErr;
  render();
});
$("clear").addEventListener("click", () => {
  if (!confirm(I18N[lang].confirmClear)) return;
  chrome.storage.local.set({ log: [] }, load);
});
chrome.storage.onChanged.addListener((changes) => { if (changes.log) { entries = changes.log.newValue || []; render(); } });
applyLang();
load();
