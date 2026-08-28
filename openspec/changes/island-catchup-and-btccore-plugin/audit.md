# Audit factuel — 19/08/2026

## Mesures réutilisables

| Scénario | Résultat | Preuve | Statut |
|---|---:|---|---|
| 50 joueurs / 50 îles | 33,8 ms MSPT | mémoire `btccore-locator-bar-coupee-2026-08-16` | mesuré, campagne close |
| 50 îles lourdes sans joueur | 37,9 ms | mémoire `btccore-batterie-charge-50-iles` | mesuré |
| Coût moyen île lourde | ~0,80 ms | même campagne | mesuré dans ce banc |
| Coût chunk entity-ticking | ~3,3 µs | même campagne | mesuré dans ce banc |
| `/forceload` niveau 31 | +0,85 ms / 225 chunks | mémoire `btccore-activation-ile-tickets` | A/B/A’ mesuré |
| JFR chunks | 6,0 % après optimisations | mémoire `btccore-profilage-50-joueurs-2026-08-15` | mesuré, autre campagne |
| JFR random ticks | 1,9 % après optimisations | même profil | mesuré, autre campagne |
| JFR block entities | 3,7 % avant/après indiqué | même profil | mesure historique, attribution à refaire |
| JFR mob AI | 0,8 % | même profil | mesuré, non prioritaire |

## Décomposition actuelle

Le chiffre 37,9 ms est un total de monde chargé/tické ; il ne permet pas encore d’attribuer
séparément block ticks, random ticks, block entities, activation range, displays et extensions.
Le profil JFR disponible attribue historiquement chunks/random ticks/block entities/IA, mais pas le
résidu extension par extension. `ServerScoreboard` ~2,4 %, Koin `Scope.resolve` ~2,2 % et
`LaserGridDisplay.traceRay` ~2,1 % sont des pistes du profil, pas des gains démontrés.

Le banc actuel porte `simulation-distance=12`, `view-distance=12`, ticking Paper activé et une
règle BTC-CORE `loadbench_*: view=8, simulation=4`. La valeur par joueur existe dans Paper, mais
aucun chemin BTC-CORE audité ne l’applique ; la capacité effectivement prouvée est par monde.

## Retirer le tick

Le mécanisme est prouvé dans le code, le gain chiffré ne l’est pas encore : Paper/Moonrise ne
parcourt plus les chunks entity-ticking si aucun ticket de ce niveau ne les maintient. En revanche,
le `.slime` reste en mémoire tant que le monde est chargé. Le résidu exact « monde chargé, zéro
tick » est donc **à mesurer**, et ne doit pas être assimilé au scénario world unload.

## Protocole manquant

Mesurer A/B/A’ sur le même monde préparé, même JVM chaude et mêmes 225 chunks : A ticket niveau 31,
B monde chargé mais aucun tick, A’ ticket réappliqué. Relever MSPT, counts Paper chunkinfo, JFR par
poste et heap après GC contrôlé. Purger la fenêtre de log avant chaque campagne ; parser `/mspt`
après suppression des codes `§.` et utiliser `quiet_for=1.5`. Le serveur est exclusivement
`H:\Serveurs Minecraft\serveur btc`.

## Limites confirmées côté extensions

- Le déchargement d’un chunk SlimeWorld coupe le tick mais ne libère pas le stockage du monde ; le
  gain mémoire doit donc être attribué uniquement au world unload/GC, jamais au chunk unload.
- Le four vanilla n’est pas capturé par MachineExtension : seuls les items de machine marqués par
  PDC créent une `MachineInstance`. Le coût d’un four MachineExtension est donc une comparaison à
  mesurer, pas un gain acquis.
- MachineExtension dispose d’un `RecipeProcessor` rattrapable, de slots multiples, d’upgrades et de
  coûts énergie/carburant ; cela rend le four custom techniquement plausible, mais son chemin ajoute
  des lookups Typewriter, stockage d’artifact et modifiers. Toute conclusion de coût reste une
  hypothèse jusqu’à un banc A/B/A’ dédié.
- Le bridge actuel est un second plugin `BTCBridge`, séparé du plugin ASWM `ASPaperPlugin`, et le
  transport BTCVelocity dépend d’un joueur porte-canal. La fusion devra donc prouver à la fois le
  démarrage autonome et l’activation optionnelle du transport.
