## Décisions

### 1. Enveloppe bridge V2

Le wire format canonique porte exactement version=2, messageId, type, sourceBackend,
targetBackend, issuedAt, expiresAt et payload. type est l'un des types de commande ou de réponse
documentés dans la spec ; kind et correlationId ne sont pas des alias acceptés. Les messages de
contrôle ne sont acceptés que depuis une connexion backend ServerConnection dont le nom est
enregistré et autorisé. Les messages issus d'un Player sont rejetés. Cette frontière est
l'authentification du protocole ; elle suppose que les ports backend ne sont pas exposés
directement et que la configuration des serveurs est l'autorité des allowlists. Une HMAC pourra
être ajoutée dans un delta séparé si cette frontière réseau ne peut pas être garantie.

Les cibles sont une allowlist, jamais une chaîne fournie directement par le client.

Le codec impose une taille maximale avant parsing, un nombre maximal de membres pour party warp,
des longueurs maximales de chaînes et un rejet stable des champs absents/inconnus. Une fenêtre de
déduplication bornée empêche le double transfert. Les réponses ACK/NACK portent le même messageId
et une catégorie d'erreur sans exception brute.

### 2. Preload et scheduler

Le preload n'envoie WorldLoaded qu'après preuve de monde chargé et disponible. Toute erreur
retourne WorldLoadFailed. Le chemin d'I/O SlimeWorld reste asynchrone ; l'accès Bukkit monde
reste sur le contexte global/région approprié. Aucune tâche BukkitScheduler nouvelle ne doit être
introduite.

### 3. Manifeste runtime

Un snapshot immuable est produit au démarrage et exposé dans le log structuré et l'API interne.
Il contient les versions et capacités, jamais les secrets. Un mismatch d'artefact est un état
visible DEGRADED, pas un no-op silencieux.

### 4. Télémétrie

Les compteurs sont opt-in, agrégés par extension et resettable. Les API sont non bloquantes et
bornées : compteurs, durée de handler, résolution Koin/monde, scheduling, paquets et backoff.
La télémétrie ne lit pas le contenu des messages joueurs et ne fait aucune I/O sur le thread de
jeu.

### 5. Contrat de banc

Le contrat documente la variante btccore, les deux artefacts, l'installation des extensions,
le protocole A/B/A', au moins 30 échantillons MSPT après 60 secondes de chauffe, un intervalle de
mesure d'au moins 90 secondes et les parseurs de codes couleur. Il exige
un manifest de version et interdit de piloter la campagne avec RCON list.
