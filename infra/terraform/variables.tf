variable "ovh_project_id" {
  description = "OVH Cloud project ID (service_name)"
  type        = string
}

variable "ovh_application_key" {
  type      = string
  sensitive = true
}

variable "ovh_application_secret" {
  type      = string
  sensitive = true
}

variable "ovh_consumer_key" {
  type      = string
  sensitive = true
}

variable "cluster_name" {
  type    = string
  default = "mcp-data-gateway"
}

variable "region" {
  description = "OVH region — GRA7 (Gravelines, France) for data residency"
  type        = string
  default     = "GRA7"
}

variable "node_flavor" {
  description = "4 vCPU / 8GB RAM — adequate for demo workload"
  type        = string
  default     = "b3-8"
}

variable "desired_nodes" {
  type    = number
  default = 2
}
