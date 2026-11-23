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
  `Bridge` (n'accepte que si l'URL courante est sur wiki-masters.com), `runHeadless` (WebView hors écran, attachée à une
  fenêtre de superposition transparente si autorisée), `record` (historique et journal), verrou `busy`.
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

## À faire / pistes
- Ajuster les regex par défaut quand l'utilisatrice enverra la sortie du Diagnostic.
- Si l'ouverture se fait par glissement (et non par clic), adapter `opener.js` (événements pointer).
