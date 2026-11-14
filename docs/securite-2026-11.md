# Mise à jour sécurité — novembre 2026

Branche `dev/poiroux/securite-1411`, partie de `dev/poiroux/widget-notif-search` (49d07cd). Audit du 2026-11-14. Aucun push, aucune publication.

## Vulnérabilités avant / après

| Stack | Outil | Avant | Après |
|---|---|---|---|
| Python (`.venv`) | `pip-audit` | 26 vulns dans 5 paquets | 0 |
| npm (`app-android/`) | `npm audit` | 5 (1 critique, 1 haute, 3 modérées) | 5, inchangé (voir risques restants) |
| Gradle (`app-android/android/`) | pas d'outil d'audit dans le projet | revue manuelle | inchangé |
| JS vendorisé | revue manuelle | Leaflet 1.9.4 (dernière 1.x) | inchangé |

### Python

| Paquet | Avant | Après | Origine |
|---|---|---|---|
| aiohttp | 3.14.3 (8 CVE) | >= 3.14.4 | transitive (pywebpush) |
| certifi | 2026.7.22 (1 avis) | >= 2026.10.18 | transitive (requests) |
| multidict | 6.7.1 (1 avis) | >= 6.9.1 | transitive (aiohttp) |
| urllib3 | 2.7.0 (3 avis) | >= 2.8.0 | transitive (requests) |
| pip | 25.0.1 (6 avis) | 26.2+ | outil du venv seulement |

Le projet n'a pas de lockfile Python : les planchers sont ajoutés dans `pyproject.toml` pour qu'une installation neuve ne reprenne pas une version vulnérable. `pip` est mis à jour dans le venv local uniquement.

### Code

`api.py` `_require_admin` : le jeton admin est maintenant comparé avec `hmac.compare_digest` (temps constant) au lieu de `!=`.

## Secrets dans l'historique

- `gitleaks 8.30.1` sur tout l'historique (toutes les branches, valeurs masquées) : 1 alerte `generic-api-key` dans `src/sure_weather/web/app.js` (commit 2b69ae5a). C'est un **faux positif** : un nom de variable météo dans une table de config, pas un secret.
- Aucun fichier sensible jamais ajouté (`.env`, `*.jks`, `*.keystore`, `*.pem`, `*.key`, `*.p12`, `google-services.json`, base SQLite, `vapid.json`).
- Les secrets d'exécution sont hors dépôt : jeton admin via la variable d'env `SURE_WEATHER_ADMIN_TOKEN` ; clés VAPID générées dans `data/vapid.json` (`data/` est dans le gitignore) ; `.env` est dans le gitignore.

Aucune rotation de clé n'est nécessaire.

## Vérifications

- `pytest` : 54 tests verts.
- `app-android/build-apk.ps1` : APK debug construit (`certo-v1.6.1.apk`, BUILD SUCCESSFUL).
- Tests JUnit Android (`gradlew testDebugUnitTest`) : 10 tests verts.
- `pip-audit` après mise à jour : aucune vulnérabilité connue.

## Risques restants

1. **npm, `@capacitor/cli` 6.2.2 (dev seulement)** : il embarque `tar` 6.2.1 (critique, traversée de chemin à l'extraction), `rimraf` 4, `glob` 9 et `minimatch` 8. Le correctif amont n'existe que dans `@capacitor/cli` >= 7.5 / 8.x.
   - J'ai essayé des `overrides` npm (`tar` ^7.5.23, `rimraf` ^6.1.4) : l'audit passe à 0, mais `tar` 7 n'a plus d'export par défaut, ce qui casse `cap sync android` (`android/update.js:298` → `tar.default.extract`) et donc le build. Je les ai retirés.
   - Exposition réelle faible : la CLI n'ouvre que ses propres templates `.tar.gz` sur la machine de build, aucune archive venant d'un tiers. Rien de tout ça n'est embarqué dans l'APK.
   - Correctif : migrer vers Capacitor 8 (CLI, core, android, preferences). Ça impose minSdk 24, compileSdk/targetSdk 36, AGP 8.13+, JDK 21 et une vérification sur émulateur. C'est une tâche à part.
2. **Chaîne Gradle ancienne** : AGP 8.2.1, Gradle 8.2.1, compile/targetSdk 34 (versions de 2023). Elle ne sert qu'au build, mais le dossier `app-android/android/` est en grande partie hors git (seuls `app/build.gradle` et les sources sont suivis), donc une mise à jour ne serait pas versionnée. Mieux vaut la faire avec la migration Capacitor 8.
3. **Jeton admin en query string** (`?token=` sur les routes `/push/*` admin) : il peut finir dans les logs d'accès du reverse proxy. Il faudrait le passer dans un en-tête (`Authorization`).
4. **APK signé avec la clé debug** (point déjà connu) : toute personne ayant une clé debug peut signer une mise à jour par-dessus si elle a accès à l'appareil. Il faudrait une clé release dédiée, gardée hors du dépôt.
5. **Pas de lockfile Python** : les versions transitives dépendent de la date d'installation. Les planchers limitent le risque sans le supprimer (`uv lock` le réglerait).
