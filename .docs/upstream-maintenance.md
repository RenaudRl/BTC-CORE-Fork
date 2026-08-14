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

**Conflits `modify/delete`** — ceux-là sont évitables et se reproduiront à **chaque** mise à jour :

| fichier supprimé chez nous | modifié en amont |
|---|---|
| `aspaper-api/build.gradle.kts.patch` | oui |
| `aspaper-server/build.gradle.kts.patch` | oui |
| `aspaper-server/paper-patches/files/.../CraftServer.java.patch` | oui |

## La cause, et la seule décision qui compte ici

Ces trois patches ont été **supprimés et remplacés par des injections** de
`scripts/apply-btccore-patches.py`, qui réécrit les fichiers en place. D'où les `.gitignore` sur
`aspaper-server/build.gradle.kts`, `paper-server/`, `paper-api/` et `aspaper-server/src/minecraft/`.

Ce choix a trois conséquences, toutes payées à chaque version :

1. **Conflits `modify/delete` garantis** — l'amont continue de faire vivre des fichiers que nous
   avons supprimés.
2. **La conversion en patches Paperweight est impossible en l'état** — `rebuildMinecraftSourcePatches`
   diffe le work tree interne de Paperweight, pas l'arbre où le script injecte. Voir plus bas.
3. **Le pipeline devient maison** — donc invérifiable par l'outillage amont, et la CI a été rouge du
   2026-07-30 au 2026-08-14 sans que personne le voie.

**Recommandation, non appliquée à ce jour :** ramener ces trois injections vers de vrais patches
Paperweight, au lieu de supprimer les patches amont. C'est le seul changement qui réduit
structurellement le coût des montées de version. Tant qu'il n'est pas fait, chaque mise à jour
recommence la même résolution.

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

# 5. Rejouer la couche BTC et la vérifier — un hook dont l'ancre a dérivé est un no-op silencieux.
python scripts/apply-btccore-patches.py
python scripts/verify-btccore-patches.py       # doit sortir en 0

# 6. Construire et tester.
./gradlew :aspaper-server:test :aspaper-server:createPaperclipJar
```

**Le point de contrôle qui compte est l'étape 5.** Une montée de version qui compile ne prouve rien :
les hooks sont des substitutions de chaînes, et une ancre qui ne matche plus laisse le build vert,
l'option dans `btccore.yml`, et la fonctionnalité nulle part. `verify-btccore-patches.py` est la
seule autorité sur l'inventaire — ne jamais se fier à un compteur écrit en dur.

## Conversion en patches Paperweight — pourquoi elle échoue

Tentée le 2026-08-14 sur arbre propre. `rebuildMinecraftSourcePatches` tourne 12 minutes, annonce
« Rebuilt 5 patches », et ne grave **aucun** hook. Il existe deux arbres `src/minecraft/java` :

| arbre | hooks BTC | rôle |
|---|---|---|
| `aspaper-server/src/minecraft/java` | oui | ce que le script injecte, et ce qui compile |
| `.gradle/caches/paperweight/upstreams/server-work/paper/src/minecraft/java` | non | ce que Paperweight diffe pour produire les `.patch` |

Preuve : `minecraft-patches/sources/net/minecraft/server/level/ServerLevel.java.patch` ne contient
aucune ligne `btc`, alors que ce fichier porte trois hooks (lignes 846, 1423, 1920).

Faire aboutir la conversion suppose de rerouter l'injection vers le work tree interne, dans l'ordre
`applyMinecraftSourcePatches` → injection → `rebuildMinecraftSourcePatches`. Ce n'est pas une
commande : `OVERLAY` est codé en dur dans le script, et le work tree vit sous `.gradle/caches`.

**Piège :** régénérer sur un arbre incohérent graverait une édition à la main comme référence
canonique. C'est pour cela que la tentative a été arrêtée plutôt que forcée.

Le jour où elle aboutira, l'étape « Apply BTC-CORE hooks » de la CI devient redondante voire
nuisible — ancres déjà consommées. Les deux mécanismes ne peuvent pas coexister.
