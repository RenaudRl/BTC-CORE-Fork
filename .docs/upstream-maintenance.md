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

### `CraftServer.java.patch` — le cas encore ouvert

Celui-là a bien été **supprimé et remplacé par une injection** de
`scripts/apply-btccore-patches.py` (règle `overworld-only`), qui réécrit le fichier en place. C'est
le motif qui reste à traiter, et il a deux effets payés à chaque version :

1. **Conflit `modify/delete` garanti** — l'amont continue de maintenir ce patch.
2. **La conversion en patches Paperweight reste impossible** — `rebuildMinecraftSourcePatches` diffe
   le work tree interne de Paperweight, pas l'arbre où le script injecte. Voir plus bas.

**Recommandation, non appliquée à ce jour :** ramener cette injection vers un vrai patch Paperweight
plutôt que de supprimer le patch amont.

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
| `paperRef` `19d83f9d` → `0ae1b423` | **nouvelle base Paper décompilée.** Les 53 hooks sont des substitutions de chaînes : une ancre qui a dérivé devient un no-op silencieux, build vert compris. Toute la couche BTC est à revérifier hook par hook. |
| `adventure` `4.26.1` → `5.1.1` | **changement de version majeur.** Les plugins du serveur en dépendent (Typewriter au premier chef) ; une rupture d'API ne se verrait qu'au démarrage, voire à l'exécution. |

**Décision :** ne pas mêler cette montée à une release corrective. Elle demande sa propre campagne —
merge, `verify-btccore-patches.py`, build, démarrage serveur, et un passage sur les plugins pour
Adventure 5. Elle est faisable, elle n'est pas urgente, et la faire à la hâte reviendrait à publier
un serveur dont on ne sait plus quels hooks sont vivants.

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
