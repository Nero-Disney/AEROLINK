# Rapport UI/UX : application universelle pour drones Wi-Fi

Nov 23, 2026 · @Ro

L'interface doit rendre visibles trois choses : ce que le drone sait réellement faire, l'état de sécurité du vol et le degré de certitude de l'identification. Le cockpit reste une vidéo plein écran avec des commandes tactiles larges, dans un thème noir adapté à l'écran Super AMOLED du Galaxy S9.

## Principes UX

Ces six règles découlent du plan directeur et tranchent les choix d'interface.

- **Des commandes honnêtes** : l'interface se construit à partir des capacités déclarées par l'adaptateur de protocole. Un bouton n'existe que si le drone sait faire l'action.
- **Une certitude affichée** : l'identification du drone montre un niveau de confiance et propose un choix manuel en cas d'ambiguïté.
- **La vidéo d'abord** : le cockpit est une vidéo plein écran en paysage, les commandes et indicateurs se posent par-dessus sans la masquer.
- **Une sécurité toujours visible** : l'état de vol est affiché en permanence, l'arrêt d'urgence a sa propre zone tactile et reste distinct de l'atterrissage normal.
- **Une IA qui informe** : en première version, les détections et le suivi n'ajoutent que des repères visuels. Aucun ordre de vol n'en découle.
- **Une performance mesurée** : latence vidéo, pertes de trames et rythme de l'IA sont visibles, pour que le pilote sache quand l'image est en retard.

## Parcours et écrans

&#91;embedded content: navigation entre les 8 écrans du plan · proposition\]

Le chemin principal va de l'accueil au cockpit en passant par la connexion. Les autres écrans se rattachent à une étape : le profil du drone après la détection, les enregistrements depuis le cockpit, le Centre IA en surcouche du cockpit. Les liens sont une proposition de navigation, le plan ne fixe que la liste des écrans.

## Assistant de connexion

C'est le premier contact avec le drone, et les avis de l'application d'origine y signalent des échecs de connexion. Il se présente en trois temps : choisir le réseau (SSID et puissance du signal), suivre les tests de détection, puis lire une fiche « Drone détecté » avec réseau, passerelle, famille, vidéo, état des commandes et niveau de confiance. La détection se limite aux opérations réseau et vidéo, sans aucune commande de vol.

| Situation | Message affiché | Action proposée |
| --- | --- | --- |
| Réseau du drone sans Internet | « Ce réseau n'a pas d'accès Internet, c'est normal pour un drone. » | Bouton « Rester connecté » |
| Caméra déjà prise par l'application propriétaire | « La caméra est occupée. Fermez l'application du drone. » | Bouton « Réessayer » |
| Famille identifiée, confiance élevée | Fiche « Drone détecté » complète | « Afficher la vidéo » |
| Identification ambiguë (même nom de réseau, protocoles différents) | « Plusieurs familles possibles », confiance moyenne ou faible | Choix manuel de la famille, tests non dangereux uniquement |
| Drone jamais validé | « Drone non validé : pilotage verrouillé » | Vidéo seule disponible |
| Vidéo reçue, commandes non vérifiées | « Vidéo OK, commandes à vérifier » | Pilotage verrouillé jusqu'à validation du profil |
| Connexion perdue | Bandeau « Connexion perdue », reconnexion en cours | Essais limités, bouton « Annuler » |

Le niveau de confiance se montre par un mot et une pastille, jamais par la couleur seule. Les journaux réseau restent à un tap de l'écran d'échec, exportables pour le diagnostic.

## Cockpit

&#91;embedded content: cockpit en paysage · filaire, proposition\]

La vidéo occupe tout l'écran et les commandes se posent dessus. Le Galaxy S9 est tenu à deux mains en paysage : les pouces tombent sur les coins du bas, l'arrêt d'urgence est donc placé en haut, loin des sticks et des boutons de vol.

- **Arrêt d'urgence isolé** : zone séparée, couleur d'alerte et libellé explicite, distincte de l'atterrissage.
- **Armement volontaire** : le décollage passe par une confirmation, et les commandes de vol sont désactivées par défaut pendant les tests vidéo.
- **Sticks** : grandes zones qui tolèrent un doigt qui dérive, retour haptique, retour au neutre au relâchement, sens des axes réglable par drone, disposition inversable pour les gauchers.
- **Cibles tactiles** : au moins 48 dp pour les boutons (recommandation Material Design, à valider sur le S9).
- **Capacités** : les boutons de photo, de vidéo, de changement de caméra ou de flip n'apparaissent que si le drone les gère.
- **Retard visible** : l'état du flux, la latence et les pertes de trames restent dans la barre du haut.

## États de vol et interface

Le plan prévoit une machine à états de vol. Chaque état a son apparence et ses actions permises, et le nom de l'état reste affiché dans la barre du haut.

| État | Ce que l'interface montre | Ce qu'elle autorise |
| --- | --- | --- |
| Disconnected | Bandeau « Non connecté », pas de vidéo | Retour à l'assistant de connexion |
| Connected | Vidéo, profil du drone, capacités | Vidéo, captures, réglages. Pilotage verrouillé tant que le profil n'est pas validé |
| Ready | Sticks au neutre, bouton d'armement | Armement avec confirmation volontaire |
| Arming | Indicateur d'armement en cours | Annulation uniquement |
| Flying | Sticks, indicateurs disponibles, état « En vol » | Pilotage, atterrissage, arrêt d'urgence |
| Landing | Mention « Atterrissage en cours » | Arrêt d'urgence |
| Emergency | Alerte plein bandeau, moteurs coupés | Acquittement avant tout nouvel armement |
| Fault | Message d'erreur et accès au journal | Diagnostic et reconnexion |

Les transitions exactes sont une proposition de conception, à valider pour chaque drone. L'atterrissage et l'arrêt d'urgence ont deux boutons, deux emplacements et deux libellés, car couper les moteurs en vol provoque une chute, comme le souligne le plan.

## Sélection et suivi des cibles

Le détecteur propose des cibles, le pilote en désigne une, puis la verrouille. L'application ne suit jamais une cible que le pilote n'a pas choisie, et en première version le suivi ne génère aucune commande de vol, comme le prévoit le plan.

**Désigner et verrouiller** : cinq façons de choisir, de la plus directe à la plus automatique.

| Méthode | Quand l'utiliser | Geste |
| --- | --- | --- |
| Toucher un cadre | Quelques sujets bien séparés | Un tap désigne, un appui long verrouille |
| Barre des cibles | Scène chargée, sujets petits ou qui se superposent | Un tap sur une vignette désigne, un appui long verrouille |
| Tracer un cadre | Objet inconnu du détecteur, ou un sujet parmi plusieurs semblables | Glisser un rectangle sur l'image |
| Raccourcis | Besoin rapide | « Le plus proche » ou « Au centre » |
| Personne enregistrée | Retrouver quelqu'un parmi plusieurs visages | Désignation automatique, si la reconnaissance est activée |

Un seul verrouillage est actif à la fois. Les autres sujets restent en cadres discrets pour qu'un changement de cible se fasse d'un tap, et un tap dans le vide annule la désignation.

**Perte et reprise** : une perte brève, par exemple un sujet masqué un instant, ne casse pas le verrouillage. Le cadre passe en pointillé et l'application cherche la cible. Après un délai réglable, la cible est libérée. Si deux candidats ressemblent à la cible retrouvée, l'application demande au pilote de trancher au lieu de choisir à sa place.

**Distance de suivi** : en première version, c'est un repère pour le pilote et non une consigne envoyée au drone. Un curseur règle la distance souhaitée, et une jauge montre l'écart avec la distance estimée, avec les indications « approcher » ou « reculer ».

- **Taille apparente** : la distance se déduit de la taille du cadre, de la focale de la caméra et de la taille réelle supposée de la cible (méthode géométrique classique, de mémoire). La focale d'une caméra de jouet est inconnue, donc un assistant de calibration la mesure avec un objet de taille connue placé à une distance connue. La taille supposée de la cible reste modifiable.
- **Profondeur monoculaire** : un réseau estime une carte de profondeur à partir d'une seule image. Le modèle [Depth Anything 3 Small en version LiteRT](https://huggingface.co/litert-community/Depth-Anything-3-Small) produit une carte de 896×504, sa page ne précise pas si les valeurs sont des distances réelles, et les temps publiés vont d'environ 0,9 s sur un Pixel 8a (GPU) à 138 ms sur un Galaxy S26 (NPU). Le S9 étant bien plus ancien, mieux vaut l'employer ponctuellement qu'à chaque image, après mesure sur l'appareil.
- **Affichage honnête** : la distance s'affiche avec « ≈ » et un niveau de confiance. Sans calibration, seulement des repères relatifs (proche, moyen, loin). Cible hors cadre ou estimation impossible : « — ».

**Reconnaissance** : trois niveaux à ne pas confondre. La détection dit qu'il y a un visage ou une personne, le suivi dit que c'est le même sujet d'une image à l'autre, la reconnaissance dit de qui il s'agit. Seule la dernière est biométrique. Le plan l'exclut de la première version, donc ce serait un module facultatif, désactivé par défaut, avec un modèle d'empreinte faciale à choisir et à évaluer en plus de la détection. S'il est activé : enrôlement volontaire avec l'accord de chaque personne, données conservées uniquement sur le téléphone, suppression par personne ou globale, et indicateur visible quand le module tourne. Les règles sur les données biométriques varient selon les pays. La reconnaissance « générale » reste de la détection d'objets par catégorie ; pour isoler un objet précis parmi plusieurs semblables, on s'appuie sur le suivi et sur le cadre tracé à la main.

**Réglages avancés hors du cockpit** : le cockpit n'expose que l'essentiel (activer le calque, désigner, verrouiller, libérer, régler la distance). Le reste vit dans le Centre IA, en sous-écrans.

- **Détecteurs** : activation, catégories, seuil de confiance.
- **Suivi** : délai avant abandon, taille de la zone de recherche, nombre maximal de cadres affichés.
- **Distance** : calibration de la caméra, taille réelle de référence, unité.
- **Personnes** : enrôlement, liste, suppression.
- **Affichage** : étiquettes, trajectoire, écart au centre.
- **Performance** : cadence et résolution d'analyse, avec la performance mesurée de chaque modèle.

## Orientation

Le paysage est le mode de vol par défaut. Le portrait est un choix de l'utilisateur, dans Paramètres, Affichage : Paysage, Portrait, Automatique selon le capteur, ou Suivre le système.

- **Cockpit en portrait** : la vidéo en haut sur toute la largeur, un panneau de commandes en bas avec les deux sticks côte à côte et les boutons de vol, la barre d'état tout en haut. L'arrêt d'urgence garde sa zone séparée. Un bouton passe la vidéo en plein écran avec les commandes en surimpression.
- **Sans coupure à la rotation** : la connexion, le flux vidéo et la cible verrouillée vivent hors de l'écran affiché (service ou état de l'application), donc tourner le téléphone ne réinitialise rien. C'est une recommandation d'architecture à valider.
- **Réglages** : en paysage, la liste des sous-écrans à gauche et le détail à droite ; en portrait, une pile d'écrans avec retour.

## Langage visuel et thèmes

La direction est sobre et claire, avec une profondeur obtenue par des contours fins, des ombres douces et des cartes. Deux inspirations. Windows 11 décrit l'élévation en niveaux, avec des contours d'un pixel et des ombres plus larges et plus douces à mesure que la surface monte ([Microsoft](https://learn.microsoft.com/en-ie/windows/apps/design/signature-experiences/layering)). macOS 27 « Golden Gate » (c'est ainsi que j'ai lu votre référence) a atténué l'effet Liquid Glass pour la lisibilité et ajouté un curseur d'opacité valable pour toute l'interface ([GSMArena](https://m.gsmarena.com/macos_27_golden_gate_arrives_with_siri_ai_and_liquid_glass_dial-news-73191.php)).

| Niveau | Usage | Contour | Ombre |
| --- | --- | --- | --- |
| Fond | Arrière-plan des écrans | Aucun | Aucune |
| Carte | Groupes de réglages, fiches, vignettes | Fin, proche du ton du fond | Très douce |
| Contrôle | Boutons, curseurs, interrupteurs | Fin | Légère, réduite à l'appui |
| Flottant | Menus, barre des cibles, HUD | Fin | Douce et plus large |
| Dialogue | Armement, confirmations, réinitialisation | Fin | Large et diffuse |

Ce tableau est une proposition de jetons de conception, à affiner sur l'appareil.

- **Couleurs douces** : fonds neutres, un seul accent désaturé, et les couleurs d'état (succès, avertissement, critique) réservées aux états de vol et aux alertes. Contraste d'au moins 4,5 : 1 ([guide Android Automotive](https://developers.google.com/cars/design/automotive-os/design-system/color.md.txt)).
- **Formes** : trois rayons de coins seulement, appliqués partout, et des contours d'épaisseur fine.
- **Mouvement** : transitions courtes et sans rebond, presque aucune animation en vol hors alertes, et respect du réglage système de réduction des animations.
- **Verre** : transparence réglable par un curseur, comme dans macOS 27. Le flou réel demande une version récente d'Android (Android 12, de mémoire) ; sur un S9 resté sous Android 10, on imite le verre par un fond semi-opaque.
- **OLED** : noir pur pour le fond du cockpit et les bandes autour de la vidéo, avec une variante gris très foncé contre les traînées ([LookUp Design](https://medium.com/lookup-design/designing-a-dark-theme-for-oled-iphones-e13cdfea7ffe)), texte gris clair plutôt que blanc pur, joysticks en contours, indicateurs qui s'estompent et décalage lent du HUD contre la rétention d'image ([Digital Trends](https://www.digitaltrends.com/tablets/how-to-fix-screen-burn/)). Un interrupteur « Mode OLED » est nécessaire, car Android n'expose pas de moyen fiable de connaître le type d'écran.
- **Plein soleil** : un thème clair à fort contraste pour le vol en extérieur.

**Éditeur de thème** (Paramètres, Apparence), en sous-écrans, avec un aperçu en direct sur une miniature du cockpit et sur une carte de réglages :

- **Mode** : clair, sombre, automatique, OLED.
- **Couleurs** : accent, fond, surfaces, états ; sélecteur et saisie hexadécimale, avec un contrôleur qui avertit sous 4,5 : 1.
- **Formes et profondeur** : rayon des coins, intensité des contours et des ombres.
- **Transparence** : le curseur de verre.
- **Texte et mouvement** : échelle de taille, animations normales, réduites ou coupées.
- **Préréglages** : Clair, Sombre, OLED, Soleil et les vôtres, avec export et import par fichier local, et réinitialisation.

En Compose, un thème se définit par un `ColorScheme`, une `Typography` et des `Shapes` passés à `MaterialTheme` ([Android Developers](https://developer.android.com/develop/ui/compose/designsystems/material3)). Les couleurs dynamiques tirées du fond d'écran exigent Android 12 ou plus : si le S9 reste sous Android 10 (à confirmer), l'éditeur personnalisé est la seule voie. Les niveaux d'élévation et la transparence sont des jetons propres à l'application, ajoutés à ce thème.

## Paramètres en sous-fenêtres

L'écran racine ne montre que six familles, sans aucun réglage détaillé. Chaque famille ouvre sa propre fenêtre, et les réglages rares restent derrière un interrupteur « Réglages avancés » dans chaque sous-écran. Un champ de recherche en haut mène directement à un réglage. Cette arborescence est une proposition.

## État de l'application

Tout réglage a une définition unique : une clé, un type, une valeur par défaut, une portée, une règle de validation et un groupe. L'interface, la sauvegarde et la réinitialisation se construisent à partir de ce registre, donc ajouter un réglage ne demande qu'une entrée.

| Portée | Contenu | Stockage proposé |
| --- | --- | --- |
| Application | Thème, orientation, langue, unités, mode OLED | DataStore |
| Drone | Profil, sens des axes, sensibilité, trim, calibration de la caméra | Room, une fiche par drone |
| IA | Détecteurs, seuils, suivi, distance, affichage | DataStore |
| Session | Cible verrouillée, état de vol, connexion, journaux | En mémoire, jamais restaurée automatiquement |
| Personnes | Enrôlements facultatifs | Room, chiffré, supprimable |

Deux familles de comportements sont à bien séparer. La Session vit en mémoire et n'est pas restaurée au redémarrage : un drone reconnecté ne reprend pas un verrouillage ou un état d'armement qui date d'avant. Les autres portées sont persistantes.

- **Sauvegarde** : chaque modification s'enregistre immédiatement, sans bouton « Enregistrer », avec une brève confirmation et un bouton « Annuler ».
- **Profils de réglages** : plusieurs jeux nommés par portée (par exemple « Vol en salle » et « Extérieur »), duplicables, renommables, activables en un tap.
- **Réinitialisation à trois niveaux** : un réglage (retour à sa valeur par défaut), un groupe, tout. Un indicateur marque les réglages modifiés et la réinitialisation demande confirmation avec le nombre d'éléments touchés.
- **Export et import** : fichier local lisible, avec numéro de version. Une version plus ancienne est migrée, un champ inconnu est ignoré et signalé, une valeur invalide est refusée avec une explication.
- **Migrations** : chaque changement de schéma a une migration testée, car on doit pouvoir mettre l'application à jour sans perdre de profils.
- **Cohérence** : une valeur dépendant d'une capacité du drone (par exemple le flip) est mémorisée, mais grisée avec la raison quand le drone connecté ne la gère pas.
- **Sécurité** : les réglages de sécurité (armement, comportement à la perte de connexion) ont des plages limitées et un avertissement à la modification ; leur réinitialisation ne se fait jamais en vol.

Côté code, l'état d'écran suit un flux de données à sens unique : un état immuable par écran, des intentions de l'utilisateur, et un dépôt de réglages unique qui expose un `Flow`. C'est cohérent avec les Coroutines et Flow du plan.

## Plan UI par phase

Chaque phase du plan directeur livre un morceau d'interface testable sur le S9, dans le même ordre que le plan : vidéo d'abord, pilotage ensuite.

| Phase du plan | Livrable UI |
| --- | --- |
| 0. Identification | Modèle de la fiche « Drone détecté » |
| 1. Validation vidéo | Écran Diagnostic : état du flux, journaux, statistiques |
| 2. Base Android | Accueil et assistant de connexion |
| 3. Adaptateur vidéo | Vue FPV plein écran avec état du flux et reconnexion |
| 4. Cockpit | HUD vidéo, captures, enregistrements, thème sombre |
| 5. Banc de mesure IA | Gestion des modèles avec performances mesurées |
| 6 et 7. IA et suivi | Calque IA et Centre IA |
| 8. Pilotage | Sticks, états de vol, arrêt d'urgence, verrouillages |
| 9 et 10. Multi-protocoles et stabilisation | Paramètres du drone, profils, réglage d'axes, accessibilité |

## Sources

- Plan directeur de l'application universelle pour drones Wi-Fi (fichier fourni par l'utilisateur)
- [How to fix screen burn-in, Digital Trends](https://www.digitaltrends.com/tablets/how-to-fix-screen-burn/)
- [Designing a dark theme for OLED iPhones, LookUp Design](https://medium.com/lookup-design/designing-a-dark-theme-for-oled-iphones-e13cdfea7ffe)
- [Android Automotive dark theme color guidance](https://developers.google.com/cars/design/automotive-os/design-system/color.md.txt)

Les principes d'ergonomie tactile (zones, taille des cibles) sont des recommandations de conception, à valider par des essais sur le S9.
