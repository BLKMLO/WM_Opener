# CLAUDE.md : mémoire du projet WM Opener

À lire en début de session, pour ne pas réanalyser le site ni relire tout le code.

## Objectif
Appli Android personnelle (sideload) qui ouvre automatiquement les paquets ("decks") de cartes sur
https://www.wiki-masters.com, en arrière-plan. L'utilisatrice écrit en français et veut des réponses en français.
Préférences : ne jamais inventer de chiffre, signaler les données manquantes, citer les sources.

## Ce qu'on sait du site (relevé le 2026-11-23 sur les pages publiques)
- Next.js (turbopack) hébergé sur Vercel, auth Supabase (projet `cyrxjeppjqsxxjayfrur`), session en cookies (`@supabase/ssr`).
- La connexion exige un captcha Cloudflare Turnstile, donc pas de login automatisé : l'utilisatrice se connecte dans la WebView.
- `manifest.webmanifest` : `start_url` = `/pulls` (la page des paquets). `/pulls` redirige vers `/login` sans session.
- Capture de l'utilisatrice (2026-11-23) : l'ouverture d'un paquet affiche « Carte 1 / 5 » (5 cartes par paquet), une
  carte en grand (badge de rareté, nom en titre, description, attaque et défense), et en dessous des boutons ronds sans texte visible.
  La barre du site contient Paquets / Collection / Échanges / Marché / Plus, plus un compteur de monnaie « W ».
- 2e série de captures (13:15) : page `/pulls` = titre « Ouvrir un paquet », image du paquet, bouton texte « Ouvrir »,
  compteur « 10 / 10 paquets disponibles ». En 1.0.x, l'historique comptait 3 paquets alors que la page affichait encore 10/10
  (page peut-être pas rechargée) et aucune carte repérée : d'où la 1.1.0 (confirmation par le compteur ou par « Carte i / n »).
- **Inconnu** : le DOM de `/pulls` et les libellés des boutons (page réservée aux comptes connectés). Les regex par défaut
  sont des suppositions, d'où le bouton Diagnostic.
- Cartes (page d'accueil) : badge de rareté `style="background-color:var(--color-rarity-xx)"` avec le texte C/PC/R/SR/UR/L,
  et le nom dans un `<h3>` du même bloc. L'ordre de rareté C<PC<R<SR<UR<L est **supposé** (non documenté par le site).
- Couleurs du site (CSS) : bg #0c0d0c, surface #131615, surface-light #1b1f1d, border #2e3431, fg #f2f4f3, accent #34d399 ;
  raretés C #b8f2d5, PC #b1cff2, R #c6a7f2, SR #ed6fa3, UR #fa9931, L #ffe144. Police de titres : Outfit (OFL).
- Les règles du site (/rules §3) interdisent les bots ; l'utilisatrice en est informée (risque de bannissement).
- Ne pas sonder l'API Supabase directement : le classifieur de sécurité l'a refusé. On automatise l'UI, pas l'API.

## Architecture (app/src/main)
- `assets/opener.js` : script injecté. Il clique le bouton d'ouverture (regex `open`), puis les boutons de suite
  (`follow`) jusqu'à épuisement, et relève les cartes nouvelles (différence avec celles déjà affichées). Le rapport passe par
  `WMBridge.report/progress`. Regex invalide = rien n'est cliqué ; liens hors site ignorés ; mode `diagnose` = lecture seule.
- `AutoOpener.kt` : configuration de la WebView, `SiteClient` (domaines autorisés, le reste part dans le navigateur),
  `Bridge` (n'accepte que si l'URL courante est sur wiki-masters.com), `runHeadless` (WebView hors écran, sans fenêtre,
  taille 1080x2340), `record` (historique et journal), verrou `busy`.
- `OpenerWorker.kt` : WorkManager périodique (`wm_auto_open`, 15 min au minimum) et passage unique (`wm_open_once`, KEEP).
  Service de premier plan `dataSync` pendant l'ouverture (notification ID_RUN non effaçable).
- `Notifs.kt` : canaux status (permanente tant que l'auto est actif, ID 1), run (ID 2), result (ID 3).
- `History.kt` : `filesDir/history.json`, 500 entrées max, écriture atomique.
- `Prefs.kt` : réglages, valeurs par défaut des regex, liste des domaines autorisés.
- `MainActivity.kt` + `res/layout/*` : onglets Jeu / Auto / Historique, UI en vues natives (sans Material ni Compose).

## Build, tests, release
- SDK : `/opt/android-sdk` (à réinstaller dans un nouveau conteneur : cmdline-tools, puis `platforms;android-35` et
  `build-tools;35.0.0`). `local.properties` n'est pas versionné.
- Maven Central renvoie parfois des 429 : le miroir `maven-central.storage-download.googleapis.com` est déclaré dans
  settings.gradle.kts et sert aussi à Robolectric (propriété système dans app/build.gradle.kts).
- `./gradlew assembleRelease` (R8 activé, environ 370 Ko). Captures d'écran : `./gradlew recordRoborazziDebug` → `docs/screenshots/`.
- Test du script hors appli : Playwright sur une page factice (voir l'historique git ; pas de fichier de test versionné).
- Release : le proxy Git de la session refuse les push de tags (403). Lancer le workflow avec l'outil GitHub
  `actions_run_trigger` (run_workflow, workflow_id `build.yml`, ref = branche de travail, inputs.release = `vX.Y.Z`) :
  l'action crée elle-même le tag et la release. Un push de tag `vX.Y.Z` fait par l'utilisatrice déclenche aussi `.github/workflows/build.yml`, qui crée la release GitHub avec l'APK
  (`RELEASE_NOTES.md`). Signature stable via les secrets `WM_KEYSTORE_B64`, `WM_KEYSTORE_PASSWORD`, `WM_KEY_ALIAS`,
  `WM_KEY_PASSWORD`, sinon clé de debug jetable. Le dépôt est **public** : ne jamais y commiter de keystore.
- Penser à incrémenter `versionCode` / `versionName` dans app/build.gradle.kts avant chaque tag.

## Historique des versions
- 1.0.0 (versionCode 2) : première release, avec l'UI soignée, l'historique, les notifications et la passe sécurité.
  APK signé avec la clé de l'utilisatrice (secrets en place depuis le 2026-11-23, empreinte SHA-256 AD:C9:04:1A:…:25:01:10).
- 1.0.1 (versionCode 3) : bord à bord sous Android 15. `applyInsets()` dans MainActivity réserve barre d'état, barre de
  navigation et clavier. Les captures Robolectric n'ont pas d'insets : le test `insets` les simule (rendu dans app/build/).

- 1.1.0 (versionCode 4) : ouverture confirmée par le site (compteur « X / Y paquets disponibles » ou « Carte i / n »),
  boutons-icônes nommés « [icône <nom lucide>] » (regex `follow` par défaut : chevron-right, etc. ; migration de l'ancienne
  valeur par défaut dans Prefs), `trace` du déroulé dans le rapport et le journal, test de `requestAnimationFrame` (`raf`),
  « Ouvrir ici » (ouverture visible depuis le Diagnostic), rechargement de l'onglet Jeu après un passage.
  Maquette Playwright du déroulé réel : un clic « Ouvrir » → « Carte 1/5 » … 5/5 → retour, compteur décrémenté.

- 1.1.1 (versionCode 5) : superposition (SYSTEM_ALERT_WINDOW) supprimée, car bloquée par Android pour les applis
  installées hors Play Store, et inutile (constaté : 4 paquets ouverts en arrière-plan, compteur passé de 10/10 à 6/10). Sur l'écran
  « Carte i / n », tout badge de rareté visible est accepté. Relevé automatique de l'écran de révélation dans `trace`.
- Diagnostic réel (2026-11-23, page d'accueil /pulls) : boutons « 0 » (monnaie W), « Notifications », « Paquets »,
  « Collection », « Échanges », « Marché », « ••• Plus », « Comment ça marche ? », « Ouvrir » (bien reconnu par la regex
  `open`). Animations actives dans la WebView visible.
- Écran « Carte i / 5 » (bilan « Ouvrir ici », 2026-11-23) : badge `<div class="absolute top-2 left-2 px-2 py-0.5 rounded-md
  text-xs font-bold z-30" style="background-color: var(--color-rarity-c); …">C</div>`, nom dans `<h3>` (ex. « Andrew Bathgate »),
  bouton étoile « Ajouter aux favoris (verrouille la carte : ni défausse, ni vente, ni échange) ». **Aucun bouton de suite
  au début** : « Continuer » apparaît après quelques secondes (après l'animation). Chaque carte doit être vue avant le paquet suivant.

- 1.2.0 (versionCode 6) : `finishReveal` attend « Continuer » jusqu'à 15 s par carte, vérifie que « Carte i » avance,
  et touche la carte en dernier recours. Exclusion par défaut : + favori|défausse|vendre|vente (migration de l'ancienne valeur).
  Délai d'un passage en arrière-plan : 9 min (avant, 3 min). Un bilan partiel est envoyé après chaque paquet, et il est
  enregistré si le délai est dépassé (`partial`). Maquette : « Continuer » après 3 s, 2 paquets en 37 s, favoris jamais cliqué.

- Capture du 2026-11-23 14:21 : l'écran « Carte i / 5 » est un **carrousel**. Sous la carte : flèche ‹, 5 points, flèche ›
  (ronde), puis un bouton vert « Encore 4 cartes ». L'utilisatrice : l'appli « n'arrive pas à swiper », et ça repart quand
  elle glisse elle-même. Ni la flèche › ni « Encore N cartes » n'apparaissaient dans les boutons cliquables du bilan
  (balise non-bouton ou désactivés : inconnu).
- 1.3.0 (versionCode 7) : `ADVANCE` dans opener.js = flèche › (svg lucide-chevron-right, quelle que soit la balise),
  « Encore N cartes », `WMBridge.swipe` (vrais MotionEvent dispatchés à la WebView, coordonnées CSS × scale),
  glissement JS (PointerEvent/TouchEvent, tactile puis souris), toucher. La méthode qui marche est mémorisée (`method`).
  Le changement de carte est détecté par la signature « Carte i/n » + nom de la carte au centre de l'écran. Maquette
  « carrousel » (5 slides dans le DOM, Continuer après 5 vues) : OK avec la flèche (div) et avec le glissement seul.

- 2026-11-23 14:30 : le site affiche parfois « Vérification rapide — On vérifie que tu es bien humain » (Turnstile)
  au moment d'ouvrir. **Ne pas contourner** (décision : contournement anti-bot exclu). 1.3.1 (versionCode 8) :
  `passVerification()` attend jusqu'à 45 s sans interagir, sinon `blocked = "verification"` et arrêt. Les cartes sont
  envoyées via `progress` dès qu'elles apparaissent (`collect`). `Entry.note` (champ JSON « o ») = résumé quand aucune
  carte n'a été repérée. Un passage auto à 14:31 (version probablement 1.3.0) : 1 paquet, aucune carte ; cause inconnue,
  journal demandé.

- 15:25 : entrées auto de 14:48, 15:07 et 15:24 avec l'ancien libellé « Cartes non repérées sur la page », donc
  probablement encore en 1.3.0. Hypothèse retenue : dans la WebView sans fenêtre, les slides n'ont pas de taille, et `visible()`
  les écartait. 1.3.2 (versionCode 9) : un badge `style*=rarity` est accepté même invisible (le carrousel contient les 5 cartes
  dès l'ouverture). Maquette « slides invisibles + carrousel bloqué » : 5 cartes relevées. `Entry.trace` (JSON « d »)
  s'affiche en touchant l'entrée. Notifications : `Prefs.notifStatus/notifRun/notifResult`. Si `notifRun` est coupé, pas de
  `setForeground`. L'utilisatrice voit encore la vérification du site, qui est gérée (attente passive).

- Déroulé réel 15:55 (1.3.2, arrière-plan) : vérification passée en 15 s, paquet ouvert, puis « écran révélation » sans
  badge ni titre visibles, toutes les méthodes `ADVANCE` sans effet, 1 seule carte lue (Catequilla). Diagnostic : WebView
  sans fenêtre = page cachée pour Chromium, plus d'images (rAF), animations figées (carte à opacité 0, flèche › désactivée).
  Reproduit en Playwright (rAF neutralisé + document.hidden) : même déroulé, état « flèches [button désactivée …] ».
- 1.4.0 (versionCode 10) : `assets/background.js` injecté au début du document (androidx.webkit
  `addDocumentStartJavaScript`, origines wiki-masters ; sinon evaluateJavascript à onPageStarted/onPageFinished),
  **uniquement** dans `runHeadless` : visibilityState « visible », hidden=false, et rAF doublé d'un setTimeout(20 ms)
  (compteurs `__wmBg.native/timer` affichés dans « état : … » en cas de blocage). Avec ce correctif, la même maquette défile
  les 5 cartes. Fréquence 10 min : chaîne de OneTimeWork `wm_auto_chain` (APPEND_OR_REPLACE en fin de passage) + garde
  périodique 1 h (`watchdog`). `Prefs.intervalMinutes` : minimum 10.

- Déroulé réel 19:45 (1.4.0) : « images moteur 1, minuterie 0 » (une seule image demandée par la page), « flèches [] »,
  « Encore » présent, une carte (Ponor (Pirot)). Conclusion : le site n'anime pas par rAF mais par **Web Animations**
  (probablement la bibliothèque Motion), figées dans une page cachée. La promesse `finished` n'arrive jamais, donc les flèches
  ne sont jamais rendues.
- 1.4.1 (versionCode 11) : background.js termine (`finish()`) toute animation à durée finie (document.getAnimations,
  toutes les 150 ms et après chaque `Element.animate`), ce qui couvre aussi les transitions et animations CSS. Compteurs :
  req/native/timer/anims/finished. `find()` préfère les boutons à texte aux « [icône …] » (sinon la flèche › était cliquée
  en boucle sur 5/5 au lieu de « Continuer »). Clé des cartes = n° de paquet + rareté|nom (doublons comptés). Attente de
  « Continuer » : 5 s avant les méthodes ADVANCE. Maquette WAAPI figée : sans correctif, même déroulé qu'en réel ; avec
  correctif, 2 paquets et 10 cartes. Les maquettes précédentes (carrousel, Continuer après 3 s, vérification) passent toujours.

- 2026-11-24 : avec la 1.4.1, ça marche le plus souvent (historique de 70 paquets, 5 cartes par paquet). Échec rare
  (07:56) : « Carte 1/5 » et « Encore » présents, mais **badges 0, titres 0** (carte absente du DOM), « en cours 1 »
  (animation infinie, probablement un indicateur de chargement), « dispo ? » au départ. Hypothèse (non prouvée) :
  contenu de la carte encore en chargement (réseau ou image lazy jamais chargée dans une page sans écran).
- 1.4.2 (versionCode 12) : `finishReveal` attend jusqu'à 30 s que `[style*=--color-rarity-]` apparaisse ; en cas d'échec,
  `stuckReveal: true` → `runHeadless` recharge /pulls une fois (si > 60 s restantes) avec `maxPacks` réduit, puis
  `merge()` réunit les deux bilans (trace « — page rechargée, reprise — »). background.js passe `img[loading=lazy]` en
  eager (MutationObserver). `stuckState` liste les animations en cours (nom + cible, classes spin/pulse/load) et
  l'état des images. Maquettes : carte arrivant après 8 s → 5 cartes ; carte jamais chargée → stuckReveal avec
  « en cours 1 [spin sur div.animate-spin] ». Le rechargement Kotlin n'est pas testable hors appareil.

- 2026-11-28 : l'utilisatrice trouve l'appli « quasiment parfaite » ; seul reproche : les sons en arrière-plan.
  1.4.3 (versionCode 13) : background.js (arrière-plan uniquement) force HTMLMediaElement muted/volume 0 (accesseurs
  redéfinis + play) et enveloppe AudioContext/webkitAudioContext pour que `destination` soit un GainNode à 0. Notification
  de résultat en `setSilent(true)`. Vérifié en Playwright : audio muet malgré muted=false/volume=1, destination = gain 0.
- Question iOS (2026-11-28), réponse donnée : pas d'équivalent fiable. iOS ne permet pas de tâche périodique garantie
  (BGAppRefreshTask décidé par le système, environ 30 s), une WKWebView en arrière-plan est suspendue, l'installation hors App Store
  passe par un compte développeur ou AltStore (re-signature tous les 7 jours), et le build exige macOS/Xcode (impossible ici).
  Seule option réaliste : ouverture quand l'appli est au premier plan.

## À faire / pistes
- Ajuster les regex par défaut quand l'utilisatrice enverra la sortie du Diagnostic ou d'« Ouvrir ici »
  (noms réels des icônes de l'écran « Carte i / 5 » et de son bouton de fin).
- Si l'ouverture se fait par glissement (et non par clic), adapter `opener.js` (événements pointer).
