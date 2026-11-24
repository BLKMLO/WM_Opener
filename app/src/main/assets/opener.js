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

  // Les boutons à texte (« Continuer ») passent avant les boutons-icônes (« [icône …] ») :
  // sur la dernière carte, la flèche › reste présente mais ne mène nulle part.
  const find = (re) => {
    const ok = clickables().filter((el) => {
      const t = label(el);
      return t && t.length < 80 && re.test(t) && !exclRe.test(t);
    });
    return ok.find((el) => !label(el).startsWith("[icône")) || ok[0];
  };

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
      // Badge du site (style var(--color-rarity-xx)) : pris même s'il n'est pas « visible ».
      // Le carrousel contient les 5 cartes dès l'ouverture, et dans la page invisible de
      // l'arrière-plan les cartes non affichées (ou pas encore animées) n'ont pas de taille.
      // Sans cet indice, on n'accepte qu'un badge visible sur l'écran « Carte i / n ».
      if (!/rarity/i.test(hint) && !(reveal() && visible(b))) return;
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

  // Éléments « feuilles » dont le texte est une rareté : balise, classe et style abrégés.
  const rarityCandidates = () => [...document.querySelectorAll("*")]
    .filter((e) => !e.children.length && RARITY.test((e.textContent || "").trim()) && visible(e))
    .slice(0, 6)
    .map((e) => `${(e.textContent || "").trim()} <${e.tagName.toLowerCase()}> class="${(e.getAttribute("class") || "").slice(0, 70)}" style="${(e.getAttribute("style") || "").slice(0, 70)}"`);
  const headings = () => [...document.querySelectorAll("h1,h2,h3,h4")].filter(visible)
    .map((h) => h.tagName.toLowerCase() + " " + h.textContent.replace(/\s+/g, " ").trim().slice(0, 40)).slice(0, 6);
  let revealLogged = false;
  const logReveal = () => {
    if (revealLogged || !reveal()) return;
    revealLogged = true;
    log("écran révélation, boutons : " + JSON.stringify(listButtons()));
    log("écran révélation, badges : " + JSON.stringify(rarityCandidates()));
    log("écran révélation, titres : " + JSON.stringify(headings()));
  };

  // Contrôle anti-robot du site (« Vérification rapide… On vérifie que tu es bien humain »).
  // On n'y touche pas : on attend qu'il se termine seul, sinon on arrête le passage.
  let blocked = null;
  const verifying = () => /vérification rapide|bien humain|verify (that )?you are human/i.test(pageText());
  const passVerification = async () => {
    if (!verifying()) return true;
    log("vérification anti-robot du site affichée : attente (sans interaction)");
    for (let t = 0; t < 45; t++) { await sleep(1000); if (!verifying()) { log(`vérification terminée après ${t + 1} s`); return true; } }
    log("vérification toujours affichée après 45 s : arrêt du passage");
    blocked = "verification";
    return false;
  };

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
        available: available(), reveal: reveal(), badges: rarityCandidates(), headings: headings(),
        buttons: listButtons(), cards: [...cardsOnScreen().values()],
      });
    }

    log(`départ : ${state()}, animations ${raf ? "OK" : "suspendues"}`);
    const found = new Map();
    const baseline = new Set(cardsOnScreen().keys());
    let openedSoFar = 0;
    // Chaque nouvelle carte est transmise aussitôt : rien n'est perdu si le passage est interrompu.
    const collect = () => {
      const before = found.size;
      // Clé par paquet : une même carte tirée dans deux paquets compte deux fois.
      cardsOnScreen().forEach((v, k) => { if (!baseline.has(k)) found.set(openedSoFar + "|" + k, v); });
      if (found.size > before) progress({ opened: openedSoFar, cards: [...found.values()] });
    };

    // ── Écran « Carte i / n » : un carrousel (flèches ‹ ›, 5 points, bouton « Encore N cartes »).
    // On avance en glissant la carte ; « Continuer » n'apparaît qu'une fois les 5 cartes vues.
    const inView = (el) => {
      const r = el.getBoundingClientRect(), cx = r.left + r.width / 2;
      return r.width > 0 && cx > 0 && cx < innerWidth;
    };
    // Carte au centre de l'écran : bloc qui contient un titre et un badge de rareté.
    const currentCard = () => {
      let best = null, bestD = 1e9;
      document.querySelectorAll("h1,h2,h3,h4").forEach((h) => {
        if (!visible(h) || !inView(h)) return;
        let c = h.parentElement;
        for (let k = 0; k < 6 && c && !c.querySelector('[style*="--color-rarity-"],[class*="rarity"]'); k++) c = c.parentElement;
        if (!c || !c.querySelector('[style*="--color-rarity-"],[class*="rarity"]')) return;
        const r = c.getBoundingClientRect(), d = Math.abs(r.left + r.width / 2 - innerWidth / 2);
        if (d < bestD) { bestD = d; best = { el: c, name: h.textContent.replace(/\s+/g, " ").trim() }; }
      });
      return best;
    };
    const signature = () => { const r = reveal(), c = currentCard(); return (r ? r.i + "/" + r.n : "-") + "|" + (c ? c.name : ""); };
    const changedFrom = async (sig, ms) => {
      for (let t = 0; t < ms; t += 300) { await sleep(300); collect(); if (!reveal() || signature() !== sig) return true; }
      return false;
    };

    const fire = (el, x, y, type, ptr, buttons) => el.dispatchEvent(new PointerEvent(type, {
      bubbles: true, cancelable: true, composed: true, pointerId: 7, pointerType: ptr, isPrimary: true,
      clientX: x, clientY: y, screenX: x, screenY: y, button: 0, buttons }));
    const tap = (el) => {
      const r = el.getBoundingClientRect(), x = r.left + r.width / 2, y = r.top + r.height / 2;
      const o = { bubbles: true, cancelable: true, clientX: x, clientY: y };
      fire(el, x, y, "pointerdown", "touch", 1); el.dispatchEvent(new MouseEvent("mousedown", o));
      fire(el, x, y, "pointerup", "touch", 0); el.dispatchEvent(new MouseEvent("mouseup", o));
      el.dispatchEvent(new MouseEvent("click", o));
    };
    // Glissement simulé en JavaScript (évènements pointer, puis touch si disponibles).
    const jsSwipe = async (el, ptr) => {
      const r = el.getBoundingClientRect(), y = r.top + r.height / 2;
      const x0 = r.left + r.width * 0.85, x1 = r.left + r.width * 0.1;
      const touch = (type, x) => {
        try {
          const t = new Touch({ identifier: 7, target: el, clientX: x, clientY: y, pageX: x, pageY: y });
          const list = type === "touchend" ? [] : [t];
          el.dispatchEvent(new TouchEvent(type, { bubbles: true, cancelable: true, touches: list, targetTouches: list, changedTouches: [t] }));
        } catch (e) {}
      };
      fire(el, x0, y, "pointerdown", ptr, 1); if (ptr === "touch") touch("touchstart", x0);
      for (let k = 1; k <= 12; k++) {
        const x = x0 + (x1 - x0) * k / 12;
        fire(el, x, y, "pointermove", ptr, 1); if (ptr === "touch") touch("touchmove", x);
        await sleep(16);
      }
      fire(el, x1, y, "pointerup", ptr, 0); if (ptr === "touch") touch("touchend", x1);
    };

    // Méthodes pour passer à la carte suivante, essayées dans l'ordre ; la première qui marche
    // est retenue pour les cartes suivantes.
    const ADVANCE = {
      "flèche ›": async () => {
        for (const svg of document.querySelectorAll("svg[class*='lucide-chevron-right'],svg[class*='lucide-arrow-right']")) {
          if (!visible(svg) || !inView(svg)) continue;
          const t = svg.closest('button,[role="button"],a') || svg.parentElement;
          if (!t || t.disabled || t.getAttribute("aria-disabled") === "true") continue;
          tap(t); return true;
        }
        return false;
      },
      "« Encore N cartes »": async () => {
        const el = [...document.querySelectorAll("button,[role='button'],a,div,span")]
          .find((e) => visible(e) && /^encore\s+\d+\s+cartes?$/i.test((e.innerText || "").trim()));
        if (!el) return false;
        const t = el.closest('button,[role="button"],a') || el;
        if (t.disabled) return false;
        tap(t); return true;
      },
      "glissement natif": async () => {
        const c = currentCard(); if (!c || !bridge.swipe) return false;
        const r = c.el.getBoundingClientRect(), y = r.top + r.height / 2;
        try { bridge.swipe(r.left + r.width * 0.85, y, r.left + r.width * 0.1, y); } catch (e) { return false; }
        await sleep(500); return true;
      },
      "glissement JS tactile": async () => { const c = currentCard(); if (!c) return false; await jsSwipe(c.el, "touch"); return true; },
      "glissement JS souris": async () => { const c = currentCard(); if (!c) return false; await jsSwipe(c.el, "mouse"); return true; },
      "toucher la carte": async () => { const c = currentCard(); if (!c) return false; tap(c.el); return true; },
    };
    let method = null;   // méthode retenue (conservée d'un paquet à l'autre)

    // Ce qui bloque, pour le déroulé : flèches, bouton « Encore », images produites.
    const stuckState = () => {
      const arrows = [...document.querySelectorAll("svg[class*='lucide-chevron-right']")].map((svg) => {
        const t = svg.closest('button,[role="button"],a') || svg.parentElement;
        const r = t.getBoundingClientRect(), cs = getComputedStyle(t);
        return `${t.tagName.toLowerCase()}${t.disabled ? " désactivée" : ""} ${Math.round(r.width)}x${Math.round(r.height)} opacité ${cs.opacity}`;
      });
      const enc = [...document.querySelectorAll("button,div,span")].find((e) => /^encore\s+\d+/i.test((e.innerText || e.textContent || "").trim()));
      const w = window.__wmBg;
      const bg = w ? `images demandées ${w.req}, moteur ${w.native}, minuterie ${w.timer}, animations créées ${w.anims}, terminées ${w.finished}` : "page visible";
      let running = 0; try { running = document.getAnimations().filter((a) => a.playState === "running").length; } catch (e) {}
      // Ce qui tourne encore (indicateur de chargement ?) et état des images.
      let what = [];
      try {
        what = document.getAnimations().filter((a) => a.playState === "running").slice(0, 3).map((a) => {
          const t = a.effect && a.effect.target;
          const cls = t ? (t.getAttribute("class") || "").split(/\s+/).filter((c) => /spin|pulse|load|animate|skeleton/.test(c)).join(".") : "";
          return (a.animationName || a.transitionProperty || "anim") + (t ? " sur " + t.tagName.toLowerCase() + (cls ? "." + cls : "") : "");
        });
      } catch (e) {}
      const imgs = [...document.images];
      const imgState = `images ${imgs.length} (chargées ${imgs.filter((i) => i.complete && i.naturalWidth).length}, lazy ${imgs.filter((i) => i.loading === "lazy").length})`;
      return `flèches [${arrows.join(" ; ")}], « Encore » ${enc ? "présent" : "absent"}, ${bg}, en cours ${running} [${what.join(" ; ")}], ${imgState}, ` +
        `badges ${document.querySelectorAll('[style*="--color-rarity-"]').length}, titres ${document.querySelectorAll("h3").length}, document ${document.visibilityState}`;
    };

    // Carte présente dans la page (même invisible) ?
    const cardInDom = () => document.querySelector('[style*="--color-rarity-"]') !== null;
    let stuckReveal = false;

    const finishReveal = async () => {
      let fails = 0, waitedLoad = false;
      for (let step = 0; step < 40; step++) {
        if (!(await passVerification())) return;
        logReveal();
        collect();
        const r0 = reveal();
        if (!r0) return;

        // Écran « Carte i / n » affiché mais carte absente : elle se charge encore (réseau, image).
        if (!cardInDom() && !waitedLoad) {
          waitedLoad = true;
          log(`carte ${r0.i}/${r0.n} : carte pas encore chargée, attente (max 30 s)`);
          let t = 0;
          for (; t < 60 && !cardInDom() && reveal(); t++) await sleep(500);
          log(cardInDom() ? `carte chargée après ${Math.round(t / 2)} s` : "carte toujours absente après 30 s");
          collect();
        }

        // « Continuer » (ou autre bouton de suite) : apparaît quand toutes les cartes ont été vues.
        let f = null;
        for (let w = 0; w < 10 && !f; w++) { f = find(followRe); if (!f) { await sleep(500); collect(); if (!reveal()) return; } }
        const sig0 = signature();
        if (f) {
          log(`carte ${r0.i}/${r0.n} : clic « ${label(f)} »`);
          f.click();
          if (await changedFrom(sig0, 6000)) { fails = 0; continue; }
        }

        const names = Object.keys(ADVANCE);
        const order = method ? [method, ...names.filter((m) => m !== method)] : names;
        let ok = false;
        for (const m of order) {
          if (!(await ADVANCE[m]())) continue;
          if (await changedFrom(sig0, 2500)) {
            if (m !== method) log(`carte ${r0.i}/${r0.n} : avance par ${m}`);
            method = m; ok = true; break;
          }
        }
        if (ok) { fails = 0; continue; }
        if (++fails >= 3) {
          log(`carte ${r0.i}/${r0.n} : impossible d'avancer (${order.join(", ")} sans effet)`);
          log("état : " + stuckState());
          stuckReveal = true;   // l'appli rechargera la page une fois pour reprendre
          return;
        }
        await sleep(2000);   // laisse le temps à « Continuer » d'apparaître
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

      if (!(await passVerification())) break;
      clicks.push(label(b));
      log(`clic « ${label(b)} » (${state()})`);
      b.click();

      // Confirmation : baisse du compteur ou écran de révélation, sous 12 s
      // (plus le temps d'une éventuelle vérification anti-robot).
      let confirmed = false;
      for (let w = 0; w < 24 && !confirmed; w++) {
        await sleep(500);
        if (verifying() && !(await passVerification())) break;
        const a1 = available();
        confirmed = !!reveal() || (a0 && a1 && a1.n < a0.n);
      }
      if (blocked) break;
      if (!confirmed) {
        noEffect++;
        log(`aucun effet visible après le clic (${state()})`);
        if (noEffect >= 2) break;
        continue;
      }
      opened++;
      openedSoFar = opened;
      progress({ opened, cards: [...found.values()] });
      log(`paquet ouvert (${state()})`);
      await sleep(1200);
      await finishReveal();
      collect();
      // Bilan partiel : conservé par l'appli si le passage est interrompu (délai dépassé).
      progress({ opened, cards: [...found.values()] });
      await sleep(1500);
      if (onLogin() || blocked) break;
    }

    report({
      ok: true, opened, noEffect, raf, clicks, trace, blocked, stuckReveal,
      cards: [...found.values()], available: available(), url: location.href, buttons: listButtons(),
    });
  } catch (e) {
    report({ ok: false, reason: "error", error: String(e), trace, url: location.href });
  }
})(__WM_CFG__);
