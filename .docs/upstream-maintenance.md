# Maintenance amont — ce qu'une montée de version coûte, et pourquoi

État mesuré le 2026-08-14 sur `feat/miniplaceholders`.

Ce document existe pour une raison précise : à chaque version de Minecraft, ce fork risque de
demander un travail de comparaison manuelle entre plusieurs projets. Ce qui suit mesure ce coût
plutôt que de le supposer, et nomme ce qui le fait monter.

## Les upstreams, et lequel est la base

Un seul est une **base**, les autres sont des **sources de patches**. Les confondre est ce qui mène
à comparer trois dépôts dans un navigateur pour retrouver un hook.

| remote | rôle | branche à suivre |
|---|---|---|
| `upstream` | **La base.** Advanced Slime Paper — ce fork en est une copie modifiée. | `dev/26.2` |
| `paper` | Amont d'ASP lui-même. Sert à relire le contexte d'origine d'un hook. | `main` |
| `purpur` | Source des hooks Purpur (~17 injections). | `ver/26.2` |
| `leaf` | Source des portages async (entity tracker, pathfinding). | `ver/26.2` |
| `pufferfish` | Source des optimisations d'entités. | `ver/1.21` |

Les remotes git ne sont **pas** portés par le dépôt : chaque clone doit les déclarer.

```bash
python scripts/setup-upstreams.py            # déclare et fetch
python scripts/setup-upstreams.py --status   # distance à chaque upstream
```

Bonne nouvelle : **Purpur et Leaf publient une branche `ver/26.2`**, exactement notre version. Un
hook Purpur ou Leaf peut donc être relu à la version correspondante, sans reconstitution.
Pufferfish, lui, n'a pas de branche 26.x — ses portages sont à traiter comme du code repris, pas
comme un amont vivant.

## Où nous en sommes

`upstream/dev/26.2` : **ils ont 14 commits que nous n'avons pas**, nous en avons 59 qu'ils n'ont pas.

Parmi les 14, plusieurs ne sont pas cosmétiques :

- `d601aaba` Memory improvements for chunks (#197)
- `6b6cbf0f` get rid of safe slime reference — décrit comme un gros gain mémoire
- `c3f65b2b` improve converter speed and memory usage
- `5f825683` fix: server recalculating spawn location every time
- `f8b0bc31` / `144ff3e4` update to latest 26.2

## Ce qu'une mise à jour coûte aujourd'hui

Mesuré, sans toucher à l'arbre :

```bash
git merge-tree --write-tree --name-only HEAD upstream/dev/26.2
```

→ **17 fichiers en conflit.** Le détail importe plus que le nombre, parce que les conflits ne sont
pas tous de même nature :

**Conflits de contenu** (normaux, un fork en produit) : `SlimeChunk.java`,
`SimpleDataFixerConverter.java`, `SlimeNMSBridgeImpl.java`, `SlimeInMemoryWorld.java`,
`SlimeLevelInstance.java`, `AnvilWorldReader.java`, `gradle.properties`, `libs.versions.toml`.

**Conflits `modify/delete`** — l'amont fait vivre des fichiers que nous avons supprimés. Ils sont de
deux natures très différentes, et une seule des deux était légitime.

| fichier | statut |
|---|---|
| `aspaper-api/build.gradle.kts.patch` | résolu — voir ci-dessous |
| `aspaper-server/build.gradle.kts.patch` | résolu — voir ci-dessous |
| `aspaper-server/paper-patches/files/.../CraftServer.java.patch` | **ouvert** |

### Les deux `build.gradle.kts` — le dépôt n'était pas reconstructible

`aspaper-server/build.gradle.kts` et `aspaper-api/build.gradle.kts` étaient **gitignorés**, et **rien
ne les génère** : les deux `patchDir` les excluent explicitement
(`excludes = setOf("build.gradle.kts")`), et `applyBTCCorePatches` ne fait que les éditer
`if (exists())`. Ce sont des **sources** qui étaient traitées comme des produits de build.

Conséquence : sur un clone neuf, pas de `build.gradle.kts` ⇒ pas de `forks.register("aspaper")` ⇒ la
tâche `applyAllServerPatches` n'existe pas ⇒ **le build s'arrête là**. Cela ne se voyait pas en local,
où ces fichiers survivaient d'un build antérieur — et c'est la raison pour laquelle la CI était rouge
depuis le 2026-07-30 alors que tous les builds locaux passaient.

**Corrigé** : les deux fichiers sont désormais versionnés, et leurs `.patch` (que **aucun** `patchDir`
ne lisait) supprimés.

### `CraftServer.java` — réglé

L'injection qui réécrivait ce fichier en place est devenue un patch Paperweight ordinaire,
`aspaper-server/paper-patches/features/0007-BTC-CORE-hooks.patch`. Le conflit `modify/delete`
garanti à chaque version disparaît avec elle : l'amont maintient son patch, nous maintenons le
nôtre, et `git am` arbitre.

## Verdict sur la mise à jour du 2026-08-14 : pertinente, mais c'est un chantier à part

Le merge a été **réellement tenté** sur une branche dédiée, puis abandonné en connaissance de cause.
Le contenu est intéressant, le coût n'est pas celui d'une synchronisation.

**Ce qu'on gagnerait** — plusieurs des 14 commits sont de vraies optimisations, et elles tombent
exactement sur notre profil d'usage (beaucoup de mondes Slime) : `d601aaba` memory improvements for
chunks, `6b6cbf0f` get rid of safe slime reference, `c3f65b2b` converter speed and memory,
`273bc550` avoid the jvm using an iterator. Plus `5f825683` (recalcul du spawn à chaque fois) et la
commande `/swm add`.

**Ce que ça coûte réellement** — 16 fichiers en conflit, **20 blocs** à arbitrer, ce qui reste
modeste. Le vrai coût est ailleurs, dans deux changements que le merge embarque :

| changement | pourquoi ce n'est pas anodin |
|---|---|
| `paperRef` `19d83f9d` → `0ae1b423` | **nouvelle base Paper décompilée.** Depuis la conversion en feature patches, ce risque a changé de nature : un hook qui ne tombe plus juste fait **échouer `git am`** au lieu de disparaître en silence. Plus de no-op, mais un vrai travail de résolution sur les 57 hunks. |
| `adventure` `4.26.1` → `5.1.1` | **changement de version majeur.** Les plugins du serveur en dépendent (Typewriter au premier chef) ; une rupture d'API ne se verrait qu'au démarrage, voire à l'exécution. |

**Décision :** ne pas mêler cette montée à une release corrective. Elle demande sa propre campagne —
merge, résolution des conflits `git am`, build, démarrage serveur, et un passage sur les plugins
pour Adventure 5.

## Procédure de mise à jour

```bash
# 1. Rien de ce qui suit n'est sûr sur un arbre sale.
git status --porcelain          # doit être vide

# 2. Mesurer AVANT de fusionner.
python scripts/setup-upstreams.py --status
git merge-tree --write-tree --name-only HEAD upstream/dev/<version>

# 3. Fusionner sur une branche dédiée, jamais sur celle de travail.
git switch -c chore/upstream-<version>
git merge upstream/dev/<version>

# 4. Les conflits sur les .patch se résolvent en gardant la version amont puis en réappliquant
#    la couche BTC, pas en recollant les deux à la main.

# 5. Contrôler la forme des patches AVANT de lancer Gradle : un en-tête de hunk faux ne se voit
#    qu'à l'application, sur un fichier temporaire, avec un numéro de ligne inexploitable.
python scripts/check-btccore-patches.py --markdown

# 6. Réappliquer. `git am` échoue bruyamment si un hook ne tombe plus juste.
python scripts/register-aspaper-fork.py
./gradlew :aspaper-server:applyAllServerPatches

# 7. Construire et tester.
./gradlew :aspaper-server:test :aspaper-server:createPaperclipJar
```

**Le point de contrôle qui compte est l'étape 6.** Un hook qui ne s'applique plus arrête le build au
lieu de disparaître — c'est tout l'intérêt de la conversion en feature patches. En cas de conflit,
éditer l'arbre de travail (`aspaper-server/src/minecraft/java`, `paper-server`, `paper-api`, tous
trois des dépôts git), commiter, puis régénérer avec `rebuild*FeaturePatches`. **Ne jamais éditer un
`.patch` à la main** : les comptes de hunk ne se recalculent pas tout seuls.

## Conversion en patches Paperweight — faite

Réalisée le 2026-08-14. Les hooks BTC ne sont plus des substitutions de chaînes.

Le blocage supposé n'existait pas. Les trois arbres cibles sont **tous des dépôts git**, et les
hooks y vivaient déjà en modifications non commitées, posées sur la chaîne `git am` des feature
patches ASP. Il suffisait de commiter et de régénérer :

| arbre de travail | patch produit |
|---|---|
| `aspaper-server/src/minecraft/java` | `aspaper-server/minecraft-patches/features/0003-BTC-CORE-hooks.patch` |
| `paper-server` | `aspaper-server/paper-patches/features/0007-BTC-CORE-hooks.patch` |
| `paper-api` | `aspaper-api/paper-patches/features/0002-BTC-CORE-hooks.patch` |

**Piste morte, ne pas y revenir :** `rebuildMinecraftSourcePatches` ne lit **jamais** l'overlay
(prouvé par rerun forcé, hash identique avant/après). Elle gère les *file patches*, au format
Paperweight (`@@ -606,8 +_,35 @@`, avec un `_`), et re-dérive les patches depuis les patches. Aucune
édition de l'overlay ne peut en sortir. La bonne tâche est `rebuildMinecraftFeaturePatches`.

Ce qui reste dans `scripts/register-aspaper-fork.py` — et seulement cela — est l'enregistrement du
fork dans les `build.gradle.kts` générés : fichiers gitignorés, exclus des deux `patchDir`, donc
membres d'aucun arbre de patches, et nécessaires *avant* que les tâches du fork existent.
