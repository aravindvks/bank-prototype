# Sutherland Banking Platform — AWS UK cell. Same shape as the OCI cell (infra/terraform/oci):
# the application images and Helm chart do not change; only this module and values-aws.yaml do.

variable "region" {
  description = "AWS region. The UK cell runs in Europe (London)."
  type        = string
  default     = "eu-west-2"
}

variable "env" {
  description = "Environment name: dev, test or demo. Used in names and the Temporal namespace (pbp-<env>)."
  type        = string
  default     = "dev"
}

variable "vpc_cidr" {
  type    = string
  default = "10.30.0.0/16"
}

variable "kubernetes_version" {
  type    = string
  default = "1.30"
}

variable "node_instance_type" {
  description = "4 vCPU / 16 GiB, the same size as the OCI E4.Flex workers."
  type        = string
  default     = "m6i.xlarge"
}

variable "node_count" {
  description = "Worker nodes, one per availability zone at least (the EHI responder spreads across zones)."
  type        = number
  default     = 3
}

variable "postgres_instance_class" {
  type    = string
  default = "db.m6g.large"
}

variable "postgres_password" {
  description = "Admin password for RDS PostgreSQL. Stored in Secrets Manager; never commit it."
  type        = string
  sensitive   = true
}

variable "kafka_password" {
  description = "SASL/SCRAM password for the services' MSK user. Stored in Secrets Manager."
  type        = string
  sensitive   = true
}

variable "webhook_secret" {
  description = "HMAC secret for vendor webhooks (LLD section 8)."
  type        = string
  sensitive   = true
}

variable "thredd_ehi_source_cidrs" {
  description = "Thredd's published egress ranges. Set the same list in values-aws.yaml (ehiLoadBalancer.sourceRanges): the EHI load balancer's security group admits only these."
  type        = list(string)
  default     = []
}

variable "services" {
  description = "Container image repositories in ECR."
  type        = list(string)
  default = [
    "bff", "orchestrator", "mapping-service", "ehi-responder", "essence-adapter", "thredd-adapter",
    "atfinity-adapter", "credit-adapter", "recon-service", "vendor-simulator", "web",
  ]
}
