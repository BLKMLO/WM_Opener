// Script injecté dans la page wiki-masters.com/pulls.
//
// Déroulé observé sur le site (captures de l'utilisatrice, 2026-11-23) :
//   page « Ouvrir un paquet » avec un bouton « Ouvrir » et un compteur « 10 / 10 paquets disponibles »,
//   puis écran de révélation « Carte 1 / 5 » avec des boutons ronds (icônes, sans texte).
//
// Un paquet n'est compté comme ouvert que si le site le confirme : baisse du compteur
// « X / Y paquets disponibles » ou apparition de l'écran « Carte i / n ». Un clic sans effet
// est signalé, pas compté.
// Mode "diagnose" : ne clique rien, décrit la page (boutons, icônes, compteurs, animations).
(async function (cfg) {
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  const bridge = window.WMBridge;
  const report = (o) => { try { bridge.report(JSON.stringify(o)); } catch (e) {} };
  const progress = (o) => { try { bridge.progress(JSON.stringify(o)); } catch (e) {} };
  const trace = [];
  const log = (s) => { trace.push(s); if (trace.length > 60) trace.shift(); };

  // Regex invalide = arrêt (surtout l'exclusion : mieux vaut ne rien cliquer qu'un achat).
  let openRe, followRe, exclRe;
  try {
    openRe = new RegExp(cfg.open, "i");
    followRe = new RegExp(cfg.follow, "i");
    exclRe = cfg.exclude.trim() ? new RegExp(cfg.exclude, "i") : /(?!)/;
  } catch (e) {
    return report({ ok: false, reason: "bad_regex", error: String(e), url: location.href });
  }

  // Libellé : texte, sinon aria-label / title, sinon nom de l'icône Lucide (« [icône chevron-right] »).
  const iconName = (el) => {
    const svg = el.querySelector("svg[class*='lucide-']");
    const m = svg && (svg.getAttribute("class") || "").match(/lucide-([a-z0-9-]+)/g);
    const names = m ? m.map((x) => x.slice(7)).filter((x) => x !== "icon") : [];
    return names.length ? "[icône " + names[names.length - 1] + "]" : "";
  };
  const label = (el) =>
    ((el.innerText || "").replace(/\s+/g, " ").trim() ||
      el.getAttribute("aria-label") || el.getAttribute("title") || el.value || iconName(el) || "").trim();

  const visible = (el) => {
    const r = el.getBoundingClientRect();
    if (r.width === 0 || r.height === 0) return false;
    const cs = getComputedStyle(el);
    return cs.visibility !== "hidden" && cs.display !== "none" && cs.pointerEvents !== "none" && cs.opacity !== "0";
  };

  const clickables = () =>
    [...document.querySelectorAll('button,[role="button"],a[href],input[type="button"],input[type="submit"]')]
      .filter((el) => {
        if (el.disabled || el.getAttribute("aria-disabled") === "true") return false;
        if (el.tagName === "A") {
          try { if (new URL(el.href, location.href).host !== location.host) return false; } catch (e) { return false; }
        }
        return visible(el);
      });

  const find = (re) => clickables().find((el) => {
    const t = label(el);
    return t && t.length < 80 && re.test(t) && !exclRe.test(t);
  });

  // État de la page, lu dans le texte visible.
  const pageText = () => (document.body.innerText || "").replace(/\s+/g, " ");
  const available = () => {
    const m = pageText().match(/(\d+)\s*\/\s*(\d+)\s*paquets? disponibles?/i);
    return m ? { n: +m[1], max: +m[2] } : null;
  };
  const reveal = () => {
    const m = pageText().match(/Carte\s*(\d+)\s*\/\s*(\d+)/i);
    return m ? { i: +m[1], n: +m[2] } : null;
  };

  // Cartes : badge dont le texte est une rareté et dont le style ou la classe évoque « rarity »,
  // nom = premier titre du même bloc.
  const RARITY = /^(C|PC|R|SR|UR|L)$/;
  const cardsOnScreen = () => {
    const out = new Map();
    document.querySelectorAll("div,span").forEach((b) => {
      if (b.children.length) return;
      const rar = (b.textContent || "").trim();
      if (!RARITY.test(rar)) return;
      const hint = (b.getAttribute("style") || "") + " " + (b.getAttribute("class") || "");
      if (!/rarity/i.test(hint) || !visible(b)) return;
      let c = b.parentElement;
      for (let k = 0; k < 6 && c && !c.querySelector("h1,h2,h3,h4"); k++) c = c.parentElement;
      const h = c && c.querySelector("h1,h2,h3,h4");
      const name = h && h.textContent.replace(/\s+/g, " ").trim();
      if (name) out.set(rar + "|" + name, { rarity: rar, name });
    });
    return out;
  };

  // Les animations tournent-elles ? (une WebView sans fenêtre peut les suspendre)
  const rafWorks = () => new Promise((res) => {
    let done = false;
    requestAnimationFrame(() => { done = true; res(true); });
    setTimeout(() => { if (!done) res(false); }, 1000);
  });

  const onLogin = () => /^\/(login|signup|inscription)/.test(location.pathname);
  const listButtons = () => [...new Set(clickables().map(label).filter(Boolean))].slice(0, 80);
  const state = () => {
    const a = available(), r = reveal();
    return (a ? `dispo ${a.n}/${a.max}` : "dispo ?") + (r ? `, carte ${r.i}/${r.n}` : "");
  };

  try {
    for (let i = 0; i < 20 && clickables().length === 0; i++) await sleep(1000);
    await sleep(1500);
    const raf = await rafWorks();

    if (onLogin()) return report({ ok: false, reason: "login", url: location.href });
    if (cfg.mode === "diagnose") {
      return report({
        ok: true, mode: "diagnose", url: location.href, raf,
        available: available(), reveal: reveal(),
        buttons: listButtons(), cards: [...cardsOnScreen().values()],
      });
    }

    log(`départ : ${state()}, animations ${raf ? "OK" : "suspendues"}`);
    const found = new Map();
    const baseline = new Set(cardsOnScreen().keys());
    const collect = () => cardsOnScreen().forEach((v, k) => { if (!baseline.has(k)) found.set(k, v); });

    // Fait défiler l'écran « Carte i / n » jusqu'au bout.
    const finishReveal = async () => {
      for (let step = 0; step < 30; step++) {
        collect();
        const before = reveal();
        if (!before) return;
        const f = find(followRe);
        if (!f) { log(`révélation ${before.i}/${before.n} : aucun bouton de suite`); return; }
        log(`clic « ${label(f)} »`);
        f.click();
        await sleep(1300);
        const after = reveal();
        if (after && before.i === after.i) {
          await sleep(1500);
          const again = reveal();
          if (again && again.i === before.i) { log("clic sans effet sur la révélation"); return; }
        }
      }
    };

    let opened = 0, noEffect = 0;
    const clicks = [];
    if (reveal()) await finishReveal();   // révélation laissée en cours par un passage précédent

    for (let p = 0; p < cfg.maxPacks; p++) {
      let b = null;
      for (let w = 0; w < (p === 0 ? 8 : 5) && !b; w++) {
        b = find(openRe);
        if (!b) { const f = !reveal() && find(followRe); if (f) { log(`clic « ${label(f)} »`); f.click(); } await sleep(1000); }
      }
      if (!b) { log("plus de bouton d'ouverture"); break; }
      const a0 = available();
      if (a0 && a0.n === 0) { log("0 paquet disponible"); break; }

      clicks.push(label(b));
      log(`clic « ${label(b)} » (${state()})`);
      b.click();

      // Confirmation : baisse du compteur ou écran de révélation, sous 12 s.
      let confirmed = false;
      for (let w = 0; w < 24 && !confirmed; w++) {
        await sleep(500);
        const a1 = available();
        confirmed = !!reveal() || (a0 && a1 && a1.n < a0.n);
      }
      if (!confirmed) {
        noEffect++;
        log(`aucun effet visible après le clic (${state()})`);
        if (noEffect >= 2) break;
        continue;
      }
      opened++;
      progress({ opened });
      log(`paquet ouvert (${state()})`);
      await sleep(1200);
      await finishReveal();
      collect();
      await sleep(1500);
      if (onLogin()) break;
    }

    report({
      ok: true, opened, noEffect, raf, clicks, trace,
      cards: [...found.values()], available: available(), url: location.href, buttons: listButtons(),
    });
  } catch (e) {
    report({ ok: false, reason: "error", error: String(e), trace, url: location.href });
  }
})(__WM_CFG__);
