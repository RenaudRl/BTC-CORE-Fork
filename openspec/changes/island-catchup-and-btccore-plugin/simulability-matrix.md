# Matrice de simulabilité — contrat proposé

| Système | Classe | Mécanisme | Coût de gameplay | Décision initiale |
|---|---|---|---|---|
| Cultures / croissance | rattrapable | loi statistique random tick, `offlineRate = 0.25 × effectiveRandomTickSpeed`, gamerule et upgrades persistés | croissance gratuite à 25 % de la vitesse effective ; distribution différente du calendrier vanilla | activé après validation de la loi |
| Machines | rattrapable | `TIMING.lastTick`, delta borné, stock/ops bornés | production pendant absence ; plafonds et ressources conservés | gratuit si machine éligible, preuve SoulFire |
| Fours / block entities vanilla | gelable par défaut | le four normal reste une block entity ; aucun catch-up vanilla acquis | cuisson arrêtée hors simulation | gel par défaut, audit RPGCore |
| Fours custom MachineExtension | rattrapable candidat | `RecipeProcessor`, slots multiples, fuel/énergie et upgrades ; coût contre vanilla non mesuré | nouvelle interface, nouvelles upgrades et burst de reprise | prototype conditionné à mesure A/B/A’ |
| Redstone | impossible | état dépendant des transitions, pas seulement du temps | circuit arrêté hors simulation | gel explicite |
| Hoppers / convoyeurs | gelable | ne pas reconstituer un réseau ; respecter l’annulation hopper/convoyeur actuelle | transport arrêté ; risque de divergence si rattrapé naïvement | gel par défaut |
| Mobs / fermes vanilla | impossible | aucune entité simulée ni fausse entité | pas de mobs/loot/XP hors présence | machine dédiée séparée, jamais implicite |
| Machine mobs en ligne | gelable / rattrapable candidat | `NativeBtcMobApi` peut créer des BTCMobs réels uniquement dans les régions chargées | coût d’entités et d’IA ; cap et coût de fonctionnement requis | test modulable, pas de catch-up entité |
| Machine mobs hors ligne | rattrapable candidat | frappeur périodique + ledger déterministe template/drop-table/XP + vacuum/réservoir XP ; sinon refus | production automatisée, intervalle/coût/capacité XP et rendement plafonné | conditionné à preuve loot/XP et idempotence |
| Vacuum items | rattrapable si monde chargé | collecte des `Item` proches vers les outputs ; le code actuel ne traite pas les orbes XP | loot disponible seulement si produit par un chemin autorisé | extension dédiée |
| Réservoir XP | non implémenté | nouveau stockage XP, unité et plafond à définir ; ne pas le traiter comme liquide arbitraire | XP stockée puis collectée, donc progression différée | audit économie puis lot séparé |
| Fluides / feu / gravité | gelable par défaut | aucune intégration temporelle tant que loi et sécurité absentes | propagation arrêtée | gel par défaut |
| Spawners vanilla | impossible par défaut | dépend du monde, joueurs, caps et entités | pas de production hors présence | machine dédiée conditionnée |
| Upgrades BTCSky / gamerules | rattrapable comme état | `DimensionData` persistée, relecture avant calcul | un changement d’upgrade modifie le taux futur, pas rétroactivement | obligatoire avant tout delta |
| RPGCore | non tranché | audit séparé des gates recettes, hoppers, XP, remplacement items et schedulers Folia | risque de double application ou de gate différent | audit séparé avant fours/rattrapage |
| Displays | gelable côté progression | couper les boucles d’affichage quand aucun viewer ; pas de simulation offline | rendu peut apparaître seulement à l’activation | à mesurer, rendu non gameplay |
