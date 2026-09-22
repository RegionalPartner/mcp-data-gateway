# Clôture de l'hébergement OVH — 22 septembre 2026

## Décision

La démo hébergée sur OVHcloud (Managed Kubernetes, région GRA9) est arrêtée et son
projet Public Cloud clôturé. Le code reste déployable par quiconque suit
[`OVH_DEPLOYMENT.md`](OVH_DEPLOYMENT.md) : il faudra un nouveau projet Public Cloud,
de nouveaux identifiants API, un nouveau bucket d'état Terraform et un nouveau
`.deploy.env` (`make up` le génère).

## Constat au 22 septembre 2026

- Serveur d'API du cluster : la connexion est fermée sans réponse.
- IP publiques du load balancer : plus aucune réponse.
- Bucket d'état Terraform : n'existe plus ; la clé S3 associée est inconnue d'OVH.
- Identifiants API OVH utilisés par Terraform : invalides.
- Dernière consommation facturée par OVH : 10 juillet 2026 ; rien depuis.

## Ce qui a été retiré du dépôt

- Jobs CI `deploy-dev` (déploiement continu vers le cluster) et `e2e-gate`
  (tests de bout en bout distants) dans `.github/workflows/ci.yaml`.
- Section « démo en ligne » des README et des guides de prise en main, remplacée
  par le mode local (`docker compose` + profil `dev`).
- Secret GitHub `OVH_KUBECONFIG` : à supprimer (kubeconfig d'un cluster disparu).
  L'environnement `ovh-cluster` est conservé pour l'historique des déploiements.

## Ce qui reste valable

- `infra/terraform/`, `k8s/` et le `Makefile` permettent de redéployer une instance
  neuve ; le guide OVH est toujours exact.
- Le test `McpRemoteEndToEndIT` s'ignore de lui-même quand aucune passerelle
  distante ne répond (`OVH_BASE_URL`).

## Côté poste de travail

Les fichiers locaux d'accès (kubeconfig OVH, identifiants S3 du bucket d'état,
identifiants API OVH, `.deploy.env`) n'ont plus aucune valeur et peuvent être
supprimés.
