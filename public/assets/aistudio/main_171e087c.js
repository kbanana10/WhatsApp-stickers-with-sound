// Arranque: espera a que WhatsApp Web termine de cargar, registra un diagnóstico y monta el botón.
(() => {
  const WAAS = window.WAAS;
  if (!WAAS || WAAS._started) return;
  WAAS._started = true;
  const t = (k, v) => (WAAS.t ? WAAS.t(k, v) : k);

  async function waitForWA(maxMs = 180000) {
    const t0 = Date.now();
    while (Date.now() - t0 < maxMs) {
      try {
        if (WAAS.wa.hasLoader()) {
          const CC = WAAS.wa.ns("WAWebChatCollection");
          if (CC && CC.ChatCollection) return Date.now() - t0;
        }
      } catch (e) { /* loading */ }
      await new Promise((r) => setTimeout(r, 1000));
    }
    throw new Error(t("errWaLoadTimeout"));
  }

  (async () => {
    try {
      WAAS.mountUI();
    } catch (e) {
      WAAS.error("ui:error-montaje", e);
    }
    try {
      const ms = await waitForWA();
      WAAS.info("wa:listo", { ms });
      await new Promise((r) => setTimeout(r, 8000));
      await WAAS.wa.diagnose();
    } catch (e) {
      WAAS.error("wa:no-listo", e);
    }
  })();
})();
