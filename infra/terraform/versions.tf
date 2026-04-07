terraform {
  required_version = ">= 1.6"

  required_providers {
    ovh = {
      source  = "ovh/ovh"
      version = "~> 0.40"
    }
    openstack = {
      source  = "terraform-provider-openstack/openstack"
      version = "~> 1.54"
    }
  }

  # OVH Object Storage as Terraform state backend
  # Configure via env vars: AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY
  backend "s3" {
    bucket                      = "mcp-tf-state"
    key                         = "mcp-data-gateway/terraform.tfstate"
    region                      = "gra"
    endpoints = {
      s3 = "https://s3.gra.perf.cloud.ovh.net"
    }
    skip_credentials_validation = true
    skip_metadata_api_check     = true
    skip_region_validation      = true
    skip_requesting_account_id  = true
    force_path_style            = true
  }
}
