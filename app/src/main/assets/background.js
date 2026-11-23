// Injecté au tout début de la page, UNIQUEMENT pour l'ouverture en arrière-plan.
// La WebView d'arrière-plan n'est attachée à aucune fenêtre : Chromium considère la page
// comme cachée et cesse d'y produire des images (requestAnimationFrame). Les animations du
// site (apparition des cartes, carrousel) restent alors figées.
// Ce script : 1) annonce une page visible ; 2) relaie requestAnimationFrame par une minuterie
// quand le moteur ne délivre plus d'images. Il ne touche ni à la vérification anti-robot ni
// aux requêtes du site.
(function () {
  if (window.__wmBg) return;
  window.__wmBg = { req: 0, native: 0, timer: 0, anims: 0, finished: 0 };
  try {
    Object.defineProperty(Document.prototype, "visibilityState", { configurable: true, get: () => "visible" });
    Object.defineProperty(Document.prototype, "hidden", { configurable: true, get: () => false });
    Document.prototype.hasFocus = function () { return true; };
  } catch (e) {}
  const nRaf = window.requestAnimationFrame.bind(window);
  const nCancel = window.cancelAnimationFrame.bind(window);
  const pending = new Map();
  let seq = 0;
  window.requestAnimationFrame = function (cb) {
    window.__wmBg.req++;
    const id = ++seq;
    let done = false;
    const run = (t, src) => {
      if (done) return;
      done = true;
      const p = pending.get(id);
      pending.delete(id);
      if (p) { if (src === "native") clearTimeout(p[1]); else nCancel(p[0]); }
      window.__wmBg[src]++;
      cb(t);
    };
    const a = nRaf((t) => run(t, "native"));
    const b = setTimeout(() => run(performance.now(), "timer"), 20);
    pending.set(id, [a, b]);
    return id;
  };
  window.cancelAnimationFrame = function (id) {
    const p = pending.get(id);
    if (p) { nCancel(p[0]); clearTimeout(p[1]); pending.delete(id); }
  };

  // Animations natives (Web Animations, transitions et animations CSS) : elles avancent au
  // rythme des images du moteur, donc jamais dans une page cachée, et leur fin (promesse
  // « finished », évènements transitionend/animationend) n'arrive pas. Le site attend cette fin
  // pour afficher la carte et ses flèches. On termine donc chaque animation finie dès qu'elle
  // existe : les évènements de fin partent normalement.
  const finishAll = () => {
    let list = [];
    try { list = document.getAnimations ? document.getAnimations() : []; } catch (e) {}
    for (const a of list) {
      if (a.playState === "finished") continue;
      try {
        const end = a.effect && a.effect.getComputedTiming ? a.effect.getComputedTiming().endTime : Infinity;
        if (!isFinite(end)) continue;          // animation infinie (indicateur de chargement…)
        a.finish();
        window.__wmBg.finished++;
      } catch (e) {}
    }
  };
  if (Element.prototype.animate) {
    const nAnimate = Element.prototype.animate;
    Element.prototype.animate = function () {
      const a = nAnimate.apply(this, arguments);
      window.__wmBg.anims++;
      setTimeout(finishAll, 0);
      return a;
    };
  }
  setInterval(finishAll, 150);
})();
