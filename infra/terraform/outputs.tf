output "cluster_id" {
  value = ovh_cloud_project_kube.mcp_cluster.id
}

output "kubeconfig" {
  value     = ovh_cloud_project_kube.mcp_cluster.kubeconfig
  sensitive = true
}

output "api_server_url" {
  value = ovh_cloud_project_kube.mcp_cluster.url
}
