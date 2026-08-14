# DivineMC ou Folia ? — arbitrage

> Statut : **décidé le 2026-08-14. Ni l'un ni l'autre comme base. Emprunt ciblé à DivineMC.**

## Le point de départ, factuel

Le README annonçait Folia. C'était faux : le fork ne contient **aucun patch Folia**, aucun
regionised threading. Corrigé depuis. La question posée était donc neuve, pas une migration.

L'objectif exprimé pour Folia était **paralléliser le monde principal**. C'est précisément ce que
Folia fait — découper un monde en régions tickées en parallèle — et c'est aussi ce qui le rend
coûteux : chaque plugin doit être écrit pour lui.

DivineMC propose autre chose : le **Parallel World Ticking**, un thread par monde. Sur un Skyblock
où chaque île est un monde Slime, l'axe correspond bien mieux à notre charge.

## Pourquoi ce n'est quand même pas la base

**1. Ce serait un troisième amont, pas une simplification.**
DivineMC est un fork de **Purpur**, pas de Paper. Notre lignée est ASP → Paper. Adopter DivineMC
comme base voudrait dire rebaser tout le fork sur une lignée Purpur, et rouvrir exactement le
problème Frankenstein qu'on vient de refermer en convertissant les hooks en feature patches.

**2. Sa faiblesse connue tombe pile sur notre boucle de jeu.**
La documentation DivineMC reconnaît que les opérations **inter-mondes** pendant un tick — typiquement
téléporter un joueur ou une entité d'un monde à un autre — posent problème. Sur un Skyblock, le
hub ↔ île est le trajet le plus emprunté du serveur. Le gain porte sur des mondes indépendants ;
notre coût se concentre là où ils cessent de l'être.

**3. La soupape existe et elle est un aveu.**
`settings.parallel-world-ticking.disable-hard-throw` permet de faire taire les exceptions
hors-thread. La documentation elle-même déconseille de l'activer : les contrôles qu'on désactive
sont ceux qui empêchent la corruption de données. Une option pareille dit ce que vaut la garantie.

**4. Folia coûte encore plus cher.**
Le regionised threading impose que **toute** la pile de plugins soit Folia-aware. Nos extensions
Typewriter passent déjà par `Bukkit.getRegionScheduler()`, donc elles sont portables — mais elles ne
sont pas la pile entière.

## Ce qu'on fait à la place

La conversion en feature patches change la donne : emprunter une optimisation à un autre fork n'est
plus une injection de chaîne mais un patch, avec une lignée, un conflit visible et un `git am` qui
tranche. C'est le bon moment pour emprunter à DivineMC **des optimisations précises**, sans en
prendre la base :

- pathfinding et entity tracking asynchrones — déjà présents chez nous (hooks 30 et 31), à comparer ;
- entity visibility culling (bande passante) — pas de contrepartie chez nous ;
- regionized chunk ticking — à évaluer isolément, c'est la partie la moins risquée de leur travail.

**Le préalable est le point 2** : monter la base ASP. Emprunter à un fork tiers avant d'être à jour
sur son propre amont, c'est ce qui a produit la situation qu'on vient de corriger.

## Sources

- [DivineMC — dépôt](https://github.com/BX-Team/DivineMC)
- [Parallel World Ticking](https://bxteam.org/docs/divinemc/features/parallel-world-ticking/)
- [Regionized Chunk Ticking](https://bxteam.org/docs/divinemc/features/regionized-chunk-ticking)
- [Folia](https://github.com/PaperMC/Folia)
