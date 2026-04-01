provider "ovh" {
  endpoint           = "ovh-eu"
  application_key    = var.ovh_application_key
  application_secret = var.ovh_application_secret
  consumer_key       = var.ovh_consumer_key
}

resource "ovh_cloud_project_kube" "mcp_cluster" {
  service_name = var.ovh_project_id
  name         = var.cluster_name
  region       = var.region
  version      = "1.30"

  private_network_configuration {
    default_vrack_gateway              = ""
    private_network_routing_as_default = false
  }
}

resource "ovh_cloud_project_kube_nodepool" "default" {
  service_name  = var.ovh_project_id
  kube_id       = ovh_cloud_project_kube.mcp_cluster.id
  name          = "default-pool"
  flavor_name   = var.node_flavor
  desired_nodes = var.desired_nodes
  min_nodes     = 1
  max_nodes     = 3
  autoscale     = true

  template {
    metadata {
      labels = { "pool" = "default" }
    }
  }
}
