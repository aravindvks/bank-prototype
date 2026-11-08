# Sutherland Banking Platform — Azure UK cell. Same shape as the OCI cell (infra/terraform/oci):
# the application images and Helm chart do not change; only this module and values-azure.yaml do.

variable "subscription_id" {
  description = "Azure subscription for this environment."
  type        = string
}

variable "location" {
  description = "Azure region. The UK cell runs in UK South (London)."
  type        = string
  default     = "uksouth"
}

variable "env" {
  description = "Environment name: dev, test or demo. Used in names and the Temporal namespace (pbp-<env>)."
  type        = string
  default     = "dev"
}

variable "vnet_cidr" {
  type    = string
  default = "10.40.0.0/16"
}

variable "kubernetes_version" {
  type    = string
  default = "1.30"
}

variable "node_vm_size" {
  description = "4 vCPU / 16 GiB, the same size as the OCI E4.Flex workers."
  type        = string
  default     = "Standard_D4s_v5"
}

variable "node_count" {
  description = "Worker nodes across availability zones 1-3 (the EHI responder spreads across zones)."
  type        = number
  default     = 3
}

variable "postgres_password" {
  description = "Admin password for PostgreSQL Flexible Server. Stored in Key Vault; never commit it."
  type        = string
  sensitive   = true
}

variable "webhook_secret" {
  description = "HMAC secret for vendor webhooks (LLD section 8)."
  type        = string
  sensitive   = true
}

variable "admin_cidrs" {
  description = "Addresses allowed to the Key Vault and Storage data planes besides the cluster (the machine running Terraform)."
  type        = list(string)
  default     = []
}

variable "thredd_ehi_source_cidrs" {
  description = "Thredd's published egress ranges; the WAF admits only these to the EHI host name."
  type        = list(string)
  default     = []
}

variable "web_hostname" {
  description = "Public host name of the web app and BFF, e.g. bank-dev.example.co.uk."
  type        = string
  default     = "bank-dev.example.co.uk"
}

variable "ehi_hostname" {
  description = "Host name registered with Thredd for EHI calls, e.g. ehi-dev.example.co.uk."
  type        = string
  default     = "ehi-dev.example.co.uk"
}

variable "web_certificate_secret_id" {
  description = "Key Vault secret ID of the web TLS certificate. Empty: no Application Gateway yet (upload the certificates to the Key Vault created here, then apply again)."
  type        = string
  default     = ""
}

variable "ehi_certificate_secret_id" {
  description = "Key Vault secret ID of the EHI TLS certificate."
  type        = string
  default     = ""
}

variable "web_private_ip" {
  description = "Fixed private IP of the web app's internal load balancer (values-azure.yaml azure.webPrivateIp); inside the AKS subnet."
  type        = string
  default     = "10.40.15.10"
}

variable "ehi_private_ip" {
  description = "Fixed private IP of the EHI responder's internal load balancer (values-azure.yaml azure.ehiPrivateIp)."
  type        = string
  default     = "10.40.15.11"
}

variable "services" {
  description = "Image repositories, for reference: ACR creates repositories on first push."
  type        = list(string)
  default = [
    "bff", "orchestrator", "mapping-service", "ehi-responder", "essence-adapter", "thredd-adapter",
    "atfinity-adapter", "credit-adapter", "recon-service", "vendor-simulator", "web",
  ]
}

variable "topics" {
  description = "Kafka topics (LLD section 9) as Event Hubs: name → partitions and retention days. A .DLT hub is created for each."
  type = map(object({
    partitions = number
    retention  = number
  }))
  default = {
    "payment-commands"    = { partitions = 12, retention = 7 }
    "onboarding-commands" = { partitions = 6, retention = 7 }
    "card-commands"       = { partitions = 6, retention = 7 }
    "customer-commands"   = { partitions = 6, retention = 7 }
    "kyc-events"          = { partitions = 6, retention = 30 }
    "card-events"         = { partitions = 12, retention = 30 }
    "payment-events"      = { partitions = 12, retention = 30 }
    "onboarding-events"   = { partitions = 6, retention = 30 }
  }
}

variable "compacted_topics" {
  description = "Compacted topics: mapping-events (latest mapping per key) and Kafka Connect's own topics."
  type        = map(number)
  default = {
    "mapping-events"       = 6
    "pbp-connect-configs"  = 1
    "pbp-connect-offsets"  = 25
    "pbp-connect-statuses" = 5
  }
}
