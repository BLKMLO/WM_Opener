// Script injecté dans la page wiki-masters.com/pulls.
// Il cherche les boutons dont le texte correspond aux expressions régulières
// configurées dans l'appli, les clique, puis renvoie un rapport JSON via
// window.WMBridge.report(...).
// Mode "diagnose" : ne clique rien, liste seulement les boutons visibles.
(async function (cfg) {
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  const rx = (s) => { try { return new RegExp(s, "i"); } catch (e) { return /a^/; } };
  const openRe = rx(cfg.open), followRe = rx(cfg.follow), exclRe = rx(cfg.exclude);
  const report = (o) => { try { window.WMBridge.report(JSON.stringify(o)); } catch (e) {} };

  const label = (el) =>
    (el.innerText || el.getAttribute("aria-label") || el.getAttribute("title") || el.value || "")
      .replace(/\s+/g, " ").trim();

  const clickables = () =>
    [...document.querySelectorAll('button,[role="button"],a,input[type="button"],input[type="submit"]')]
      .filter((el) => {
        if (el.disabled || el.getAttribute("aria-disabled") === "true") return false;
        const r = el.getBoundingClientRect();
        const cs = getComputedStyle(el);
        return r.width > 0 && r.height > 0 && cs.visibility !== "hidden" &&
          cs.display !== "none" && cs.pointerEvents !== "none";
      });

  const find = (re) => clickables().find((el) => {
    const t = label(el);
    return t && t.length < 80 && re.test(t) && !exclRe.test(t);
  });

  const onLogin = () => /^\/(login|signup|inscription)/.test(location.pathname);
  const listButtons = () => [...new Set(clickables().map(label).filter(Boolean))].slice(0, 80);

  try {
    // Attendre l'hydratation de l'appli Next.js (max 20 s).
    for (let i = 0; i < 20 && clickables().length === 0; i++) await sleep(1000);
    await sleep(1500);

    if (onLogin()) return report({ ok: false, reason: "login", url: location.href });
    if (cfg.mode === "diagnose") {
      return report({ ok: true, mode: "diagnose", url: location.href, buttons: listButtons() });
    }

    let opened = 0;
    const clicks = [];
    for (let p = 0; p < cfg.maxPacks; p++) {
      let b = null;
      for (let w = 0; w < (p === 0 ? 8 : 5) && !b; w++) { b = find(openRe); if (!b) await sleep(1000); }
      if (!b) break;
      clicks.push(label(b));
      b.click();
      opened++;
      await sleep(2500);

      // Boutons de suite (révéler, continuer…) jusqu'à ce qu'il n'y en ait plus.
      for (let j = 0; j < 40; j++) {
        let f = find(followRe);
        if (!f) { await sleep(1500); f = find(followRe); if (!f) break; }
        clicks.push(label(f));
        f.click();
        await sleep(1200);
      }
      await sleep(1500);
      if (onLogin()) break;
    }
    report({ ok: true, opened, clicks, url: location.href, buttons: listButtons() });
  } catch (e) {
    report({ ok: false, reason: "error", error: String(e), url: location.href });
  }
})(__WM_CFG__);
