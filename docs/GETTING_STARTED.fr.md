# MCP Data Gateway — Prise en main

Guide pas-à-pas pour connecter Claude Code à la passerelle de démonstration.
Aucune compétence technique requise.

[🇬🇧 English](GETTING_STARTED.md) | 🇫🇷 Français

---

## Avant de commencer — les clés de démo

La passerelle utilise des clés API pour contrôler les accès. Deux clés de démonstration sont disponibles :

| Clé | Niveau d'accès |
|-----|---------------|
| `demo-readonly-key-001` | Employés (sans salaires), documents PUBLIC + INTERNAL |
| `demo-admin-key-001` | Tout, y compris les salaires et les documents CONFIDENTIAL |

> Ces clés sont publiques — toute personne disposant de ce guide peut les utiliser.
> Elles sont prévues uniquement à des fins de démonstration.
> Contactez [Ancoris](https://www.ancoris.fr) si vous avez besoin d'une clé privée pour votre organisation.

---

## Étape 1 — Installer Claude Code

Si vous n'avez pas encore Claude Code :

- **Mac ou Windows :** téléchargez depuis [claude.ai/code](https://claude.ai/code) et installez l'application
- **VS Code / JetBrains :** installez l'extension Claude Code depuis le marketplace
- **Terminal :** `npm install -g @anthropic-ai/claude-code` (nécessite Node.js 18+)

Ouvrez un terminal et lancez `claude` pour vérifier que l'installation fonctionne.

---

## Étape 2 — Ajouter le serveur MCP

Exécutez cette commande une seule fois dans votre terminal :

```bash
claude mcp add mcp-data-gateway --transport http https://mcp.37.59.24.118.nip.io/mcp
```

Puis démarrez Claude Code :

```bash
claude
```

---

## Étape 3 — S'authentifier

Lors de la première connexion, Claude Code ouvre automatiquement une **fenêtre de navigateur** pour demander votre clé API.

1. Une page intitulée **"MCP Data Gateway — Authenticate"** s'ouvre automatiquement
2. Saisissez l'une des clés de démonstration du tableau ci-dessus
3. Cliquez sur **Authorise**
4. Le navigateur affiche un message de succès
5. Le statut Claude Code passe à **✔ connected**

Si le navigateur ne s'ouvre pas automatiquement, lancez `/mcp` dans Claude Code et sélectionnez **Re-authenticate**.

Votre token est valable **une heure**. À expiration, Claude Code vous demandera de vous réauthentifier.

---

## Étape 4 — Tester

Une fois connecté, tapez simplement en langage naturel — Claude utilise les outils de la passerelle en votre nom.

**Se repérer :**
```
Quelles données sont disponibles dans la passerelle, et qu'est-ce que je peux consulter ?
```

**Parcourir les employés :**
```
Liste tous les employés et leurs départements.
```
```
Qui travaille dans le département IT ?
```

**Rechercher des documents :**
```
Trouve des documents mentionnant l'investissement dans l'infrastructure numérique.
```
```
Y a-t-il des documents sur les bonnes pratiques en matière de sécurité informatique ?
```

**Recherche sémantique** — trouve des idées liées même sans mots-clés exacts :
```
Cherche du contenu sur l'efficacité énergétique et les datacentres.
```
```
Trouve tout contenu sur le recrutement ou l'évaluation des compétences.
```

**Question transversale :**
```
Donne-moi une synthèse de l'organisation à partir de la liste des employés
et des documents accessibles.
```

---

## Données disponibles dans la démo

### Employés

| Nom | Département |
|-----|------------|
| Alice Martin | RH |
| Bob Dupont | IT |
| Claire Morin | Finance |
| David Leroy | IT |
| Emma Bernard | RH |

> Les salaires ne sont visibles qu'avec la clé admin.

### Documents

| Document | Classification | Contenu |
|----------|---------------|---------|
| `rapport-annuel-2024.txt` | INTERNAL | Rapport annuel — investissement numérique, énergie, résultats |
| `note-technique-securite.txt` | PUBLIC | Guide des bonnes pratiques en sécurité informatique |
| `politique-rh-v3.txt` | **CONFIDENTIAL** | Politique RH de recrutement et d'évaluation |

> Le document CONFIDENTIAL n'est retourné qu'avec la clé admin.

---

## Niveaux d'accès en un coup d'œil

| | Clé lecture seule | Clé admin |
|---|---|---|
| Noms et départements des employés | Oui | Oui |
| Emails des employés | Oui | Oui |
| Salaires des employés | Non | Oui |
| Documents PUBLIC | Oui | Oui |
| Documents INTERNAL | Oui | Oui |
| Documents CONFIDENTIAL | Non | Oui |

Pour voir la différence concrètement, essayez :
```
Que dit la politique RH sur le recrutement ?
```
- Clé lecture seule → "Aucun document trouvé"
- Clé admin → retourne le contenu de `politique-rh-v3.txt`

---

## Résolution des problèmes

| Symptôme | Solution |
|----------|---------|
| Le serveur MCP affiche "✘ failed" | Lancez `/mcp` → **Reconnect**, puis **Re-authenticate** si le problème persiste |
| Le navigateur ne s'ouvre pas | Lancez `/mcp` → **Re-authenticate** pour déclencher manuellement le flux |
| "Aucun document trouvé" sur les sujets RH | Vous utilisez la clé lecture seule — passez à `demo-admin-key-001` |
| Token expiré | Lancez `/mcp` → **Re-authenticate** |
| Connecté mais aucun outil n'apparaît | Redémarrez Claude Code |

---

## Vous voulez connecter vos propres données ?

Cette démo utilise des données statiques d'exemple. Ancoris configure le pipeline d'ingestion —
chargement de vos documents réels, bases de données et données CRM — dans le cadre d'un engagement client.

[Contactez Ancoris](https://www.ancoris.fr)
