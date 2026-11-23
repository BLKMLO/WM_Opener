// Script injecté dans la page wiki-masters.com/pulls.
// Clique le bouton d'ouverture, puis les boutons de suite (révéler, continuer…),
// relève les cartes apparues et renvoie un rapport JSON via window.WMBridge.
// Mode "diagnose" : ne clique rien, liste seulement les boutons visibles.
(async function (cfg) {
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  const bridge = window.WMBridge;
  const report = (o) => { try { bridge.report(JSON.stringify(o)); } catch (e) {} };
  const progress = (o) => { try { bridge.progress(JSON.stringify(o)); } catch (e) {} };

  // Regex : une regex invalide arrête tout (surtout l'exclusion : mieux vaut ne rien
  // cliquer que risquer un achat). Exclusion vide = rien n'est exclu.
  let openRe, followRe, exclRe;
  try {
    openRe = new RegExp(cfg.open, "i");
    followRe = new RegExp(cfg.follow, "i");
    exclRe = cfg.exclude.trim() ? new RegExp(cfg.exclude, "i") : /(?!)/;
  } catch (e) {
    return report({ ok: false, reason: "bad_regex", error: String(e), url: location.href });
  }

  const label = (el) =>
    (el.innerText || el.getAttribute("aria-label") || el.getAttribute("title") || el.value || "")
      .replace(/\s+/g, " ").trim();

  const visible = (el) => {
    const r = el.getBoundingClientRect();
    if (r.width === 0 || r.height === 0) return false;
    const cs = getComputedStyle(el);
    return cs.visibility !== "hidden" && cs.display !== "none" && cs.pointerEvents !== "none";
  };

  const clickables = () =>
    [...document.querySelectorAll('button,[role="button"],a[href],input[type="button"],input[type="submit"]')]
      .filter((el) => {
        if (el.disabled || el.getAttribute("aria-disabled") === "true") return false;
        // Ne jamais suivre un lien qui sort du site.
        if (el.tagName === "A") {
          try { if (new URL(el.href, location.href).host !== location.host) return false; } catch (e) { return false; }
        }
        return visible(el);
      });

  const find = (re) => clickables().find((el) => {
    const t = label(el);
    return t && t.length < 80 && re.test(t) && !exclRe.test(t);
  });

  // Cartes : badge de rareté (style var(--color-rarity-xx)) + titre de la carte.
  // Structure relevée sur la page d'accueil publique ; non vérifiée sur /pulls.
  const RARITY = /^(C|PC|R|SR|UR|L)$/;
  const cardsOnScreen = () => {
    const out = new Map();
    document.querySelectorAll('[style*="--color-rarity-"]').forEach((b) => {
      const rar = (b.textContent || "").trim();
      if (!RARITY.test(rar) || !visible(b)) return;
      let c = b.parentElement;
      for (let k = 0; k < 6 && c && !c.querySelector("h1,h2,h3,h4"); k++) c = c.parentElement;
      const h = c && c.querySelector("h1,h2,h3,h4");
      const name = h && h.textContent.replace(/\s+/g, " ").trim();
      if (name) out.set(rar + "|" + name, { rarity: rar, name });
    });
    return out;
  };

  const onLogin = () => /^\/(login|signup|inscription)/.test(location.pathname);
  const listButtons = () => [...new Set(clickables().map(label).filter(Boolean))].slice(0, 80);

  try {
    // Attente de l'hydratation de l'appli Next.js (max 20 s).
    for (let i = 0; i < 20 && clickables().length === 0; i++) await sleep(1000);
    await sleep(1500);

    if (onLogin()) return report({ ok: false, reason: "login", url: location.href });
    if (cfg.mode === "diagnose") {
      return report({ ok: true, mode: "diagnose", url: location.href, buttons: listButtons(), cards: [...cardsOnScreen().values()] });
    }

    const baseline = new Set(cardsOnScreen().keys());
    const found = new Map();
    const collect = () => cardsOnScreen().forEach((v, k) => { if (!baseline.has(k)) found.set(k, v); });

    let opened = 0;
    const clicks = [];
    for (let p = 0; p < cfg.maxPacks; p++) {
      let b = null;
      for (let w = 0; w < (p === 0 ? 8 : 5) && !b; w++) { b = find(openRe); if (!b) await sleep(1000); }
      if (!b) break;
      clicks.push(label(b));
      b.click();
      opened++;
      progress({ opened });
      await sleep(2500);
      collect();

      for (let j = 0; j < 40; j++) {
        let f = find(followRe);
        if (!f) { await sleep(1500); collect(); f = find(followRe); if (!f) break; }
        clicks.push(label(f));
        f.click();
        await sleep(1200);
        collect();
      }
      await sleep(1500);
      collect();
      if (onLogin()) break;
    }
    report({ ok: true, opened, clicks, cards: [...found.values()], url: location.href, buttons: listButtons() });
  } catch (e) {
    report({ ok: false, reason: "error", error: String(e), url: location.href });
  }
})(__WM_CFG__);
