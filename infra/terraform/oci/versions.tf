terraform {
  required_version = ">= 1.6"
  required_providers {
    oci = {
      source  = "oracle/oci"
      version = "~> 6.0"
    }
  }
  # State in OCI Object Storage (S3-compatible). Fill in backend.hcl and run:
  #   terraform init -backend-config=backend.hcl
  backend "s3" {}
}

provider "oci" {
  region = var.region
}
