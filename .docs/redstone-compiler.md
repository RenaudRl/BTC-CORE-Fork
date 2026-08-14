# Compilateur redstone BTC-CORE

Remplacement du couple mort `rpg.redstone.static-graph` + `performance.redstone-throttle` par un
vrai compilateur de circuits, inspiré de [RedPiler](https://github.com/MCHPR/MCHPRS)
(`docs/Redpiler.md`).

## Pourquoi

`ALTERNATE_CURRENT` (Paper) optimise la *propagation dans la poussière*, mais continue de lire et
d'écrire des blocs et de déclencher des block updates. Un compilateur supprime tout cela : le
circuit devient un graphe orienté pondéré tické en mémoire, et le monde n'est touché qu'à la fin.
C'est un changement d'ordre de grandeur, pas un gain marginal.

Contrepartie : ce n'est gagnant que sur des circuits **statiques**. D'où la restriction aux mondes
whitelistés (`rpg.redstone.compiler.whitelisted-worlds`).

## Décisions

| Sujet | Décision |
|---|---|
| Déclenchement | Automatique, par boîte englobante, quand un circuit devient actif. Aucune commande. |
| Édition joueur | Jamais bloquée. Une pose/casse écrase le graphe dans le monde, le joueur édite en vanilla, la zone se recompile après un délai d'inactivité. |
| Pistons / blocs mobiles | Nœuds de **sortie** uniquement (`NodeType.PISTON`). Le monde exécute le mouvement ; tout déplacement d'un bloc du graphe invalide la zone. |
| Écriture dans le monde | Différée à la **quiescence** (file de ticks vide). Pendant une rafale on ne touche à rien ; à l'arrêt on écrit tous les nœuds `dirty`, couleurs de poussière comprises. Le joueur voit le bon état final. |
| Portée | Mondes whitelistés seulement. Ailleurs, redstone Paper normale. |

## État

### Fait — noyau, sans dépendance NMS, compile

`aspaper-server/src/main/java/dev/btc/core/redstone/graph/`

- `NodeType` — types de nœuds compilables et leurs capacités (entrée / sortie).
- `Link` — arête `(side, source, weight)`. Le poids encode l'atténuation : c'est le BFS de
  compilation remplacé par une soustraction au runtime.
- `Node` — champs plats mutables (pas de record : le runtime les touche des millions de fois/s),
  état runtime + données par type (délai de répéteur, verrouillage, mode de comparateur,
  override de conteneur, `facingDiode`).
- `TickPriority` — les 4 tiers vanilla (`HIGHEST/HIGHER/HIGH/NORMAL`), dans l'ordre d'exécution.
- `TickQueue` — anneau tournant `délai x priorité` de files FIFO. Planification et consommation
  en O(1) sans allocation, là où vanilla fait un insert trié.
- `CompiledGraph` — nœuds, index position→nœud, boîte englobante, quiescence.
- `GraphRuntime` — sémantique vanilla exacte : séparation *update* / *tick*, propagation par liste
  de travail explicite (pas de récursion : une ligne de poussière ferait sauter la pile),
  priorités de répéteur, lampe instantanée à l'allumage et 2 ticks à l'extinction, torche
  inverseuse, comparateur compare/subtract avec override de conteneur.

### Fait — lot 1, compilateur (NMS), compile

`aspaper-server/src/main/java/dev/btc/core/redstone/compile/`

- `GraphCompiler` — `compile(level, origin, maxNodes, maxExtent)` renvoie une `Compilation`, ou
  `null` quand le circuit sort du domaine compilable (l'appelant laisse alors tourner la redstone
  vanilla, qui est toujours juste).
- `Compilation` — le graphe, plus les liaisons conteneur→comparateur que le gestionnaire devra
  rafraîchir (`containerOverride`).

**Passe `IdentifyNodes`** — remplissage par diffusion depuis un bloc, suivant la connectivité
redstone. Les blocs pleins sont traversés (ils transmettent la puissance forte) mais jamais
enchaînés l'un à l'autre : c'est ce qui empêche la diffusion de partir dans tout le décor. La
quasi-connectivité des pistons (bloc du dessus) est suivie. Chaque nœud est initialisé sur l'état
réel du monde, donc le graphe démarre synchronisé.

**Passe `InputSearch`** — pour chaque nœud, un BFS *à travers la poussière* jusqu'aux composants qui
le pilotent vraiment. Il n'y a **aucun lien poussière→poussière** : la traversée est faite une fois
ici et son coût devient le poids du lien. La profondeur du graphe devient le nombre de composants,
pas le nombre de blocs. La poussière reste un nœud, uniquement pour le write-back (couleur).

**Point de conception clé** — aucune règle par bloc n'est réécrite ici. Pour savoir si un bloc émet
dans une direction, on interroge une *copie sous tension* de son `BlockState`
(`POWERED`/`LIT`/`POWER` forcés) via `getSignal` / `getDirectSignal`. La topologie est donc lue chez
vanilla, pas dupliquée — un torch qui ne nourrit pas le bloc du dessous, un répéteur qui n'émet
qu'en face, une poussière qui n'alimente que ce qu'elle pointe : tout reste juste sans table maison.

**Optimisations** — `ClampWeights` (poids ≥ 15 : rien ne survit), `ConstantFold` (lien issu d'une
source fixe déjà atténuée à zéro), `DedupLinks` (liens parallèles → le plus court, back et side
comptés séparément), `PruneOrphans` (nœud qui ne nourrit rien et que rien ne nourrit) + réindexation.

**Domaine de compilation (volontairement étroit)** : poussière, répéteur, comparateur, torche,
lampe, levier, bouton, plaque de pression, bloc de redstone, piston (sortie), blocs pleins comme
transmission. Tout le reste qui touche à la redstone — observateur, distributeur, entonnoir, porte,
rail, capteur de lumière, TNT — **abandonne la compilation**. Élargir le domaine, c'est apprendre au
write-back à restaurer l'état de chaque nouveau bloc, pas assouplir ce test.

### Fait — lot 2, gestionnaire de zones, compile

`dev/btc/core/redstone/RedstoneCompilerManager.java` — une instance par `ServerLevel`, à ne toucher
que depuis le thread qui tique ce monde.

- **Détection d'activité** — les updates redstone sont comptées par chunk sur une fenêtre glissante ;
  au-delà du seuil, on tente une compilation depuis la position en cause. Échec ⇒ cooldown sur ce
  chunk, on ne réessaie pas à chaque tick.
- **Index par chunk** — `zonesByChunk` : le chemin chaud (`absorbNeighborChanged`, appelé pour
  *toute* update de voisinage du monde) reste une seule recherche de map.
- **Absorption** — une update visant une position d'une zone compilée est avalée : vanilla ne touche
  plus à ces blocs. Un comparateur ainsi visé relit son conteneur (`updateNeighbourForOutputSignal`
  passe exactement par là) et rafraîchit `containerOverride`.
- **Entrées** — les leviers, boutons et plaques restent pilotés par le monde. Leur changement d'état
  arrive par `sendBlockUpdated` et devient un `setInput`. Les boutons et plaques gardent leur
  minuterie vanilla (leurs délais dépassent la profondeur de la `TickQueue`), d'où
  `ownsBlockTick` qui ne rend au graphe que les ticks de poussière/répéteur/comparateur/torche/lampe.
- **Invalidation** — tout autre changement de bloc dans la boîte (pose, casse, écriture étrangère sur
  un bloc du graphe) écrit la zone dans le monde puis la relâche, avec un cooldown : la recompilation
  n'a lieu qu'une fois les éditions terminées **et** le circuit à nouveau actif.
- **Write-back** — à la quiescence, en deux phases : d'abord les blocs porteurs d'état
  (`POWER`, `POWERED`, `LIT`) en `UPDATE_CLIENTS`, ensuite seulement les sorties (pistons) qu'on
  réveille par une update de voisinage — ce qu'elles lisent autour d'elles est alors déjà juste.
- **Réveil au relâchement** (`wake`) — sans ça un circuit lâché en pleine course s'arrêterait : le
  graphe détenait ses ticks en attente et vanilla n'en a aucun. Une update par composant suffit à
  faire replanifier chaque diode.
- Ajout au noyau : `GraphRuntime.refresh(node)`, seul moyen de réévaluer un nœud dont une entrée a
  bougé hors graphe (le conteneur d'un comparateur).

### Fait — lot 3, patchs NMS, compile

5 greffes dans `ServerLevel`, portées par
`aspaper-server/minecraft-patches/features/0003-BTC-CORE-hooks.patch` :

| Point | Rôle |
|---|---|
| champ `btcRedstoneCompiler` + `tick(BooleanSupplier)` | une instance par monde, tickée en tête de tick de monde |
| `neighborChanged(pos, block, orientation)` | absorbe l'update quand la position est compilée |
| `neighborChanged(state, pos, block, orientation, moved)` | idem (c'est par là que passent les conteneurs) |
| `sendBlockUpdated(pos, old, current, flags)` | seul point de passage de tout changement de bloc : entrées et invalidation |
| `tickBlock(pos, type)` | supprime le tick vanilla des blocs que le graphe tique lui-même |

### Fait — lot 4, config, les deux options mortes supprimées

`performance.redstone-throttle` et `rpg.redstone.static-graph` ont disparu **partout** dans le même
changement : `btccore.yml`, `BTCCoreConfig` (champs + chargement + `isStaticGraphEnabledFor`),
`PerformanceManager.shouldProcessRedstoneUpdate` et son compteur, `BTCCoreAPI` / `BTCCoreAPIImpl`,
`BTCCoreDebugCommand`, la définition d'injection `patch_rthrottle` et la greffe déjà posée dans
`Level.neighborChanged`, rendue à son corps vanilla vide.

À leur place, `rpg.redstone.compiler` : `enabled`, `whitelisted-worlds`, `activity-threshold`,
`activity-window-ticks`, `recompile-delay-ticks`, `max-nodes`, `max-extent`.
API : `isRedstoneCompilerEnabledFor(worldName)`.

### Fait — lot 5, validation du noyau et instrumentation

**Tests JUnit** — `aspaper-server/src/test/java/dev/btc/core/redstone/graph/GraphRuntimeTest.java`,
12 tests, tous verts. Le noyau n'ayant aucune dépendance NMS, les quatre circuits de référence sont
montés à la main dans la forme exacte que `GraphCompiler` produit, et la sémantique vanilla est
vérifiée **tick par tick** — la seule chose qu'un observateur en jeu puisse réellement comparer :

| Circuit | Ce qui est prouvé |
|---|---|
| Horloge torche+répéteur | n'atteint jamais la quiescence ; période = `2 x (délai + 1)` pour les délais 1 à 4 |
| Chaîne de répéteurs | un étage par tick exactement ; lampe allumée instantanément, éteinte 2 ticks après ; quiescence atteinte une fois le signal passé |
| Comparateur | soustraction (avec clamp à 0), mode comparaison (le côté ne coupe que s'il est *strictement* supérieur), override de conteneur qui remplace l'entrée arrière |
| Verrouillage latéral | un répéteur verrouillé ignore son entrée arrière ; la valeur retenue s'applique dès la levée du verrou |

Le source set de test existait déjà (infra JUnit de Paper) mais ne contenait aucun test ; son filtre
`include("**/**TestSuite.class")` a été élargi au package du noyau. **`--offline` ne suffit pas pour
`:aspaper-server:test`** : les dépendances de test héritées de Paper (mockito, hamcrest,
suite-engine, junit-pioneer) ne sont pas dans le cache.

**Mesure MSPT** — `RedstoneProfiler` + `/btccore redstone bench [ticks]` (défaut 200, 20..12000).
Le bench mesure le **même circuit dans le même monde**, deux fois : d'abord compilé, puis en coupant
le master switch (ce qui fait relâcher les zones et rend les blocs à la redstone vanilla). Les deux
compteurs sont des nanosecondes wall-clock sur le thread du monde, donc directement comparables et
convertis en contribution MSPT de la même façon :

- chemin compilé — tout ce que fait `RedstoneCompilerManager.tick()` ;
- chemin vanilla — chaque `handleNeighborChanged`, c'est-à-dire là où `ALTERNATE_CURRENT` travaille.

Deux précautions de conception : les sondes comparent le monde **par identité** (`level ==
benchLevel`), donc la redstone des autres mondes ne pollue pas l'échantillon ; et `endOfTick` est
appelé en **tête** du tick du monde mesuré, seul moment où les deux compteurs du tick précédent sont
complets (le compilé tourne en tête de tick, les updates de voisinage tout au long). 40 ticks de
chauffe sont jetés à chaque changement de phase.

### Fait — lot 6, une instrumentation qui ne peut plus mentir

Les deux premiers benchs en jeu (2026-08-08) ont rapporté `0 zone(s)` **et** `vanilla 0,0000 ms/tick`.
Un zéro de durée disait deux choses incompatibles — « trop rapide pour être chronométré » et « ça n'a
jamais eu lieu » — et rien ne permettait de trancher. Trois corrections :

**Le monde mesuré est prouvé, pas supposé.** `RedstoneProfiler.endOfTick` est la seule chose qui fasse
avancer la machine à états, et elle n'est atteinte que depuis le `RedstoneCompilerManager` de
`benchLevel`. Un bench qui imprime son rapport a donc démontré en chemin que le `ServerLevel` rendu par
`((CraftWorld) player.getWorld()).getHandle()` est bien l'objet qui tique et dont le manager tourne —
`SlimeLevelInstance extends ServerLevel` sans redéfinir `tick()`, l'identité tient. L'hypothèse
« mauvais monde » est écartée par construction.

**Les updates sont comptées, pas seulement chronométrées.** La sonde NMS s'arme désormais sur
`sampling()` seul et appelle `recordVanilla(level, nanos)` : le monde mesuré est chronométré, tous les
autres sont **comptés par nom**. Le rapport donne donc les updates de chaque phase dans le monde
mesuré, et la liste des updates vues ailleurs. Zéro update dans le monde mesuré est déclaré INVALIDE
avant tout le reste, puisque c'est aussi ce qui explique zéro zone.

**Le whitelist rapporté est la réponse live de la config**, `BTCCoreConfig.isRedstoneCompilerEnabledFor`
au moment du rapport, plus le champ caché `RedstoneCompilerManager.whitelisted` — jamais calculé tant
qu'aucune zone n'existe, et c'est lui qui affirmait qu'un monde whitelisté ne l'était pas.

### Fait — lot 6, le compilateur dit pourquoi il refuse

`GraphCompiler.compile()` renvoyait `null` sans raison. Il renvoie maintenant un `CompileResult`
(jamais `null`) : soit la `Compilation`, soit une cause nommée. Le drapeau muet `aborted` est remplacé
par un champ `refusal` renseigné au premier abandon — bloc **et** position :

| Point d'abandon | Cause rapportée |
|---|---|
| bloc hors domaine | `minecraft:observer at 12,64,-30 takes part in redstone but is outside the compilable domain` |
| `max-nodes` | `circuit exceeds max-nodes (16384), still growing at ...` |
| `max-extent` | `circuit spans more than max-extent (128) blocks on an axis, reaching ...` |
| cadre d'objet sur comparateur | `an item frame at ... drives the comparator at ...; entities cannot be compiled` |
| rien de compilable | `no compilable redstone component around ...` / `every component around ... was pruned` |

Le manager retient `lastRefusal` et le bench l'imprime. Surtout, **`/btccore redstone probe`** compile à
sec le circuit contenant le bloc regardé et rapporte soit `COMPILABLE: N node(s), box ...`, soit
`REFUSED: <cause>`. C'est le seul moyen de poser la question sur un circuit calme : le bench ne peut
rapporter qu'un refus que le seuil d'activité a bien voulu déclencher.

### Fait — lot 6, la greffe d'absorption était posée au mauvais endroit

Premier bench exploitable (2026-08-08, `palier1`, 200 ticks) : **vanilla 0,0327 ms/tick, 3655 updates
par phase** — le chemin vanilla est enfin non nul. Et il rend un verdict net :
`0 zone(s); 0 compile attempt(s)`. 3655 updates vues, zéro tentative de compilation.

Cause : `absorbNeighborChanged` était greffé sur les **deux surcharges `ServerLevel.neighborChanged`**.
Or la poussière et les diodes poussent par `Level.updateNeighborsAt` /
`updateNeighborsAtExceptFromFacing`, qui vont **droit au `neighborUpdater`** sans jamais passer par ces
surcharges. Le compilateur ne voyait donc qu'une fraction du trafic, n'atteignait jamais
`activity-threshold`, et ne compilait rien. Double conséquence : détection d'activité aveugle, **et**
trou de correction — une zone compilée aurait laissé vanilla écrire sur ses propres blocs.

Corrigé en déplaçant la greffe sur `NeighborUpdater.executeUpdate`, l'entonnoir unique : les trois
implémentations (`Collecting`, `Instant`, la surcharge 2 args) y convergent toutes, et
`handleNeighborChanged` n'est appelé nulle part ailleurs. C'est le même raisonnement qui y avait déjà
amené `zero-features.block-updates`. Les 2 greffes `ServerLevel.neighborChanged` sont supprimées dans
le même changement (5 hooks redstone → 4), et le nouveau patch est **ancré sur la ligne injectée par
zero-features**, pour qu'une dérive amont échoue bruyamment au lieu de réinstaller un chemin non greffé.

### Fait — lot 6, la cause racine : un débordement de sentinelle

Greffe déplacée, serveur redémarré, et le bench rapportait **encore** `0 compile attempt(s)` — pour
3655 updates traversant désormais le bon entonnoir. La sonde, elle, répondait
`COMPILABLE: 10 node(s), box -39,62,-17 to -34,64,-12` : le compilateur *savait* compiler ce circuit,
mais on ne le lui demandait jamais.

`isWhitelisted()` testait `tick - whitelistCheckedTick >= WHITELIST_REFRESH_TICKS` avec
`whitelistCheckedTick` amorcé à `Long.MIN_VALUE`. Pour tout tick ≥ 0 cette soustraction **déborde en
négatif**, donc la condition n'est jamais vraie : le champ `whitelisted` conservait son défaut `false`
à vie, et `recordActivity()` sortait à la première ligne sur *chaque* update. Le compilateur ne pouvait
rien compiler, dans aucun monde, depuis le premier jour — et c'est aussi la vraie explication du flag
« not whitelisted » qui mentait dans les premiers rapports.

Corrigé par un drapeau explicite `whitelistChecked` plutôt qu'une sentinelle temporelle. Règle à
retenir : **ne jamais amorcer un « jamais encore fait » avec `Long.MIN_VALUE` quand on lui soustrait
ensuite un compteur.** Un booléen ne déborde pas.

### Fait — lot 6, l'origine de compilation était un spectateur

Débordement corrigé, le compilateur tente enfin : **26 tentatives, 26 refus**, cause nommée
`no compilable redstone component around -36,61,-14`. Le circuit vit en y=63 ; y=61 est le sol.

`recordActivity` compilait depuis *la position de l'update qui franchissait le seuil*, laquelle tombe
la plupart du temps sur un bloc spectateur — le sol sous un fil, l'air à côté d'une torche. Le flood
fill n'y trouve rien et abandonne. Corrigé : `Activity` retient la dernière position **du chunk qui
portait réellement un composant** (`GraphCompiler.isComponent`, nouveau) et c'est de là que part la
compilation ; un chunk actif dont le trafic n'a jamais touché de composant part en cooldown sans
tentative. L'état du bloc est passé à `absorbNeighborChanged(pos, state)` — `executeUpdate` l'a déjà
en main, donc le test ne coûte aucune lecture de monde.

### Fait — lot 7, validation différentielle (`/btccore redstone verify [ticks]`)

`GraphCompiler` produit un graphe à partir de vrais blocs — la sonde le prouve. Ça ne prouve **pas**
que ce graphe est le *même circuit* : une topologie mal lue compile parfaitement, puis exécute autre
chose. C'est ce qu'un harnais différentiel tranche.

**Le harnais évident est impossible ici.** Snapshot → N ticks compilé → restauration → N ticks vanilla
ne tient pas : restaurer des blocs ne restaure pas les *ticks planifiés* de vanilla. Une horloge remise
en place en milieu de cycle s'arrête, et la moitié vanilla de la comparaison mesure un circuit mort —
exactement le piège déjà rencontré avec `wake()`.

**Donc le graphe tourne à côté du monde, pas à sa place.** Aucune zone installée, aucun write-back, le
monde continue en vanilla et sert de référence sans jamais être perturbé — une vérification ratée ne
peut donc pas abîmer une construction. Chaque tick, en tête de tick (même raison que
`RedstoneProfiler.endOfTick`) : les entrées pilotées par le monde (levier, bouton, plaque) sont relues
dans le graphe, puis chaque nœud est comparé au bloc qu'il représente, puis le graphe avance d'un tick.
Un joueur qui actionne un levier n'est donc pas une divergence ; un désaccord sur une force de fil, une
sortie de répéteur ou de comparateur, un état de torche ou de lampe en est une vraie.

Le rapport donne le nombre de ticks d'accord et de désaccord, et nomme jusqu'à 5 positions fautives
avec la valeur du monde et celle du graphe. **Réserve assumée** : une horloge libre démarrée à un tick
de déphasage est en désaccord presque partout tout en étant juste. D'où un verdict nuancé plutôt que
binaire — `agreed == 0` est signalé comme possiblement un déphasage, un mélange d'accords et de
désaccords désigne bien la topologie. Le test qui a du sens est un circuit à entrées stabilisées.

La compilation est suspendue dans le monde vérifié (`RedstoneVerifier.active`), et la vérification est
refusée si des zones y sont déjà installées — sinon le « monde de référence » serait déjà piloté par le
graphe.

### Reste à faire

1. **Vérifier avant de mesurer.** `/btccore redstone verify` sur le circuit de test. Tant qu'il n'est
   pas PASS, un chiffre de perf ne vaut rien : un gain sur un graphe faux n'est pas un gain.
2. **Puis re-mesurer.** `/btccore redstone bench` — première occasion réelle pour le compilateur de
   s'engager. Attention : la variance vanilla mesurée est d'environ 50 % sur ce circuit (0,0327 puis
   0,0495 ms/tick pour 3655 updates identiques). Un ratio sous 1,5× ne veut rien dire ; prévoir
   plusieurs runs ou `bench 2000`.
3. **Deux points à trancher au vu du runtime** :
   - `GraphRuntime.applyTick(BUTTON)` est devenu inatteignable, le bouton gardant sa minuterie
     vanilla. À supprimer du noyau si la validation confirme ce partage.
   - `NodeType.GENERIC_OUTPUT` n'est produit par aucun `classify` : c'est le point d'extension du
     domaine (portes, distributeurs, rails), pas du code mort — chacun demande son propre write-back.
