# Platform services, one-for-one with the OCI cell:
#   OKE → EKS · OCI PostgreSQL → RDS PostgreSQL 16 · OCI Streaming → MSK · OCI Cache → ElastiCache
#   OCI Vault → KMS + Secrets Manager · Object Storage → S3 · OCIR → ECR · API Gateway + WAF → ALB + WAF
#   OCI Logging/APM → CloudWatch (traces through the OpenTelemetry Collector, unchanged)

resource "aws_kms_key" "master" {
  description             = "${local.name} master key: EKS secrets, RDS, MSK, ElastiCache, S3, Secrets Manager"
  enable_key_rotation     = true
  deletion_window_in_days = 30
}

resource "aws_kms_alias" "master" {
  name          = "alias/${local.name}"
  target_key_id = aws_kms_key.master.key_id
}

# ---------- EKS ----------

data "aws_iam_policy_document" "eks_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["eks.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "cluster" {
  name               = "${local.name}-eks-cluster"
  assume_role_policy = data.aws_iam_policy_document.eks_assume.json
}

resource "aws_iam_role_policy_attachment" "cluster" {
  role       = aws_iam_role.cluster.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonEKSClusterPolicy"
}

resource "aws_eks_cluster" "eks" {
  name     = local.name
  role_arn = aws_iam_role.cluster.arn
  version  = var.kubernetes_version

  vpc_config {
    subnet_ids              = aws_subnet.workers[*].id
    endpoint_private_access = true
    endpoint_public_access  = false
  }

  access_config {
    authentication_mode = "API"
  }

  encryption_config {
    resources = ["secrets"]
    provider {
      key_arn = aws_kms_key.master.arn
    }
  }

  depends_on = [aws_iam_role_policy_attachment.cluster]
}

data "aws_iam_policy_document" "node_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ec2.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "node" {
  name               = "${local.name}-eks-node"
  assume_role_policy = data.aws_iam_policy_document.node_assume.json
}

# ECR read-only on the node role: images pull without an image pull secret.
resource "aws_iam_role_policy_attachment" "node" {
  for_each = toset([
    "arn:aws:iam::aws:policy/AmazonEKSWorkerNodePolicy",
    "arn:aws:iam::aws:policy/AmazonEKS_CNI_Policy",
    "arn:aws:iam::aws:policy/AmazonEC2ContainerRegistryReadOnly",
  ])
  role       = aws_iam_role.node.name
  policy_arn = each.value
}

resource "aws_eks_node_group" "workers" {
  cluster_name    = aws_eks_cluster.eks.name
  node_group_name = "${local.name}-workers"
  node_role_arn   = aws_iam_role.node.arn
  subnet_ids      = aws_subnet.workers[*].id
  instance_types  = [var.node_instance_type]

  scaling_config {
    desired_size = var.node_count
    min_size     = var.node_count
    max_size     = var.node_count * 2
  }

  depends_on = [aws_iam_role_policy_attachment.node]
}

resource "aws_eks_addon" "addon" {
  for_each     = toset(["vpc-cni", "coredns", "kube-proxy", "eks-pod-identity-agent"])
  cluster_name = aws_eks_cluster.eks.name
  addon_name   = each.value
  depends_on   = [aws_eks_node_group.workers]
}

# External Secrets Operator reads the service secrets through EKS Pod Identity (no keys in the cluster).
data "aws_iam_policy_document" "pod_identity_assume" {
  statement {
    actions = ["sts:AssumeRole", "sts:TagSession"]
    principals {
      type        = "Service"
      identifiers = ["pods.eks.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "external_secrets" {
  name               = "${local.name}-external-secrets"
  assume_role_policy = data.aws_iam_policy_document.pod_identity_assume.json
}

data "aws_iam_policy_document" "external_secrets" {
  statement {
    actions   = ["secretsmanager:GetSecretValue", "secretsmanager:DescribeSecret"]
    resources = ["arn:aws:secretsmanager:${var.region}:${data.aws_caller_identity.current.account_id}:secret:${local.name}-*"]
  }
  statement {
    actions   = ["kms:Decrypt"]
    resources = [aws_kms_key.master.arn]
  }
}

resource "aws_iam_role_policy" "external_secrets" {
  role   = aws_iam_role.external_secrets.id
  policy = data.aws_iam_policy_document.external_secrets.json
}

resource "aws_eks_pod_identity_association" "external_secrets" {
  cluster_name    = aws_eks_cluster.eks.name
  namespace       = "external-secrets"
  service_account = "external-secrets"
  role_arn        = aws_iam_role.external_secrets.arn
}

# ---------- RDS PostgreSQL 16 (one instance, a schema per service) ----------

resource "aws_db_subnet_group" "pg" {
  name       = "${local.name}-pg"
  subnet_ids = aws_subnet.data[*].id
}

# Debezium reads the outbox tables through logical replication (LLD section 9). The WAL cap stops a
# stalled replication slot from filling the disk.
resource "aws_db_parameter_group" "pg" {
  name   = "${local.name}-pg16"
  family = "postgres16"

  parameter {
    name         = "rds.logical_replication"
    value        = "1"
    apply_method = "pending-reboot"
  }
  parameter {
    name  = "max_slot_wal_keep_size"
    value = "10240"
  }
}

resource "aws_db_instance" "pg" {
  identifier                      = "${local.name}-pg"
  engine                          = "postgres"
  engine_version                  = "16"
  instance_class                  = var.postgres_instance_class
  allocated_storage               = 100
  max_allocated_storage           = 500
  storage_type                    = "gp3"
  storage_encrypted               = true
  kms_key_id                      = aws_kms_key.master.arn
  db_name                         = "pbp"
  username                        = "pbpadmin"
  password                        = var.postgres_password
  multi_az                        = true
  db_subnet_group_name            = aws_db_subnet_group.pg.name
  vpc_security_group_ids          = [aws_security_group.data.id]
  parameter_group_name            = aws_db_parameter_group.pg.name
  backup_retention_period         = 14
  copy_tags_to_snapshot           = true
  deletion_protection             = true
  final_snapshot_identifier       = "${local.name}-pg-final"
  performance_insights_enabled    = true
  performance_insights_kms_key_id = aws_kms_key.master.arn
}

# Read replica for the EHI responder's fallback card lookups, as on OCI.
resource "aws_db_instance" "pg_replica" {
  identifier             = "${local.name}-pg-replica"
  replicate_source_db    = aws_db_instance.pg.identifier
  instance_class         = var.postgres_instance_class
  storage_encrypted      = true
  kms_key_id             = aws_kms_key.master.arn
  vpc_security_group_ids = [aws_security_group.data.id]
  parameter_group_name   = aws_db_parameter_group.pg.name
  skip_final_snapshot    = true
}

# ---------- MSK (Kafka 3.x) with SASL/SCRAM ----------
# MSK has no Terraform resource for topics: the Helm chart's kafka-topics Job creates them
# (kafkaTopics.enabled in values-aws.yaml), with replication factor 3.

resource "aws_cloudwatch_log_group" "kafka" {
  name              = "/pbp/${var.env}/msk"
  retention_in_days = 30
}

resource "aws_msk_configuration" "kafka" {
  name           = "${local.name}-kafka"
  kafka_versions = ["3.7.x"]
  server_properties = join("\n", [
    "auto.create.topics.enable=false",
    "default.replication.factor=3",
    "min.insync.replicas=2",
    "log.retention.hours=168",
  ])
}

resource "aws_msk_cluster" "kafka" {
  cluster_name           = "${local.name}-kafka"
  kafka_version          = "3.7.x"
  number_of_broker_nodes = 3

  broker_node_group_info {
    instance_type   = "kafka.m7g.large"
    client_subnets  = aws_subnet.data[*].id
    security_groups = [aws_security_group.data.id]
    storage_info {
      ebs_storage_info {
        volume_size = 100
      }
    }
  }

  client_authentication {
    unauthenticated = false
    sasl {
      scram = true
    }
  }

  encryption_info {
    encryption_at_rest_kms_key_arn = aws_kms_key.master.arn
    encryption_in_transit {
      client_broker = "TLS"
      in_cluster    = true
    }
  }

  configuration_info {
    arn      = aws_msk_configuration.kafka.arn
    revision = aws_msk_configuration.kafka.latest_revision
  }

  logging_info {
    broker_logs {
      cloudwatch_logs {
        enabled   = true
        log_group = aws_cloudwatch_log_group.kafka.name
      }
    }
  }
}

# MSK requires SCRAM secrets to be named AmazonMSK_* and encrypted with a customer key.
resource "aws_secretsmanager_secret" "msk_scram" {
  name       = "AmazonMSK_${local.name}"
  kms_key_id = aws_kms_key.master.arn
}

resource "aws_secretsmanager_secret_version" "msk_scram" {
  secret_id     = aws_secretsmanager_secret.msk_scram.id
  secret_string = jsonencode({ username = "pbp", password = var.kafka_password })
}

resource "aws_msk_scram_secret_association" "kafka" {
  cluster_arn     = aws_msk_cluster.kafka.arn
  secret_arn_list = [aws_secretsmanager_secret.msk_scram.arn]
  depends_on      = [aws_secretsmanager_secret_version.msk_scram]
}

# ---------- ElastiCache (Redis) for the EHI card cache and daily spend ----------

resource "aws_elasticache_subnet_group" "cache" {
  name       = "${local.name}-cache"
  subnet_ids = aws_subnet.data[*].id
}

resource "aws_elasticache_replication_group" "cache" {
  replication_group_id       = "${local.name}-cache"
  description                = "EHI card cache and daily spend counters"
  engine                     = "redis"
  engine_version             = "7.1"
  node_type                  = "cache.m7g.large"
  num_cache_clusters         = 3
  automatic_failover_enabled = true
  multi_az_enabled           = true
  at_rest_encryption_enabled = true
  transit_encryption_enabled = true
  kms_key_id                 = aws_kms_key.master.arn
  subnet_group_name          = aws_elasticache_subnet_group.cache.name
  security_group_ids         = [aws_security_group.data.id]
  port                       = 6379
}

# ---------- Secrets Manager: the keys the Helm chart's ExternalSecret expects ----------
# Names match deploy/helm/pbp/templates/external-secret.yaml: pbp-<env>-<key in lower-kebab-case>.

locals {
  service_secrets = {
    "db-user"                                 = "pbpadmin"
    "db-password"                             = var.postgres_password
    "webhook-secret"                          = var.webhook_secret
    "spring-kafka-properties-sasl-jaas-config" = "org.apache.kafka.common.security.scram.ScramLoginModule required username=\"pbp\" password=\"${var.kafka_password}\";"
  }
}

resource "aws_secretsmanager_secret" "service" {
  for_each   = local.service_secrets
  name       = "${local.name}-${each.key}"
  kms_key_id = aws_kms_key.master.arn
}

resource "aws_secretsmanager_secret_version" "service" {
  for_each      = local.service_secrets
  secret_id     = aws_secretsmanager_secret.service[each.key].id
  secret_string = each.value
}

# ---------- S3: documents, settlement files, recon reports, archive ----------

resource "aws_s3_bucket" "bucket" {
  for_each = toset(["documents", "settlement", "recon", "archive"])
  bucket   = "${local.name}-${each.key}-${data.aws_caller_identity.current.account_id}"
}

resource "aws_s3_bucket_versioning" "bucket" {
  for_each = aws_s3_bucket.bucket
  bucket   = each.value.id
  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "bucket" {
  for_each = aws_s3_bucket.bucket
  bucket   = each.value.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm     = "aws:kms"
      kms_master_key_id = aws_kms_key.master.arn
    }
  }
}

resource "aws_s3_bucket_public_access_block" "bucket" {
  for_each                = aws_s3_bucket.bucket
  bucket                  = each.value.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

# Archived partitions and recon reports: kept 7 years, cannot be deleted early (S3 Object Lock, compliance mode).
resource "aws_s3_bucket" "archive_locked" {
  bucket              = "${local.name}-archive-locked-${data.aws_caller_identity.current.account_id}"
  object_lock_enabled = true
}

resource "aws_s3_bucket_versioning" "archive_locked" {
  bucket = aws_s3_bucket.archive_locked.id
  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_object_lock_configuration" "archive_locked" {
  bucket = aws_s3_bucket.archive_locked.id
  rule {
    default_retention {
      mode  = "COMPLIANCE"
      years = 7
    }
  }
  depends_on = [aws_s3_bucket_versioning.archive_locked]
}

resource "aws_s3_bucket_public_access_block" "archive_locked" {
  bucket                  = aws_s3_bucket.archive_locked.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

# ---------- ECR ----------

resource "aws_ecr_repository" "repo" {
  for_each             = toset(var.services)
  name                 = "pbp/${each.key}"
  image_tag_mutability = "MUTABLE"

  image_scanning_configuration {
    scan_on_push = true
  }

  encryption_configuration {
    encryption_type = "KMS"
    kms_key         = aws_kms_key.master.arn
  }
}

# ---------- WAF for the public web entry ----------
# The AWS Load Balancer Controller creates the internet-facing ALB from the chart's Ingress and attaches
# this web ACL (values-aws.yaml aws.wafAclArn). Thredd's EHI calls use a separate NLB whose security
# group admits only Thredd's ranges, as the OCI cell does with its EHI load balancer.

resource "aws_wafv2_web_acl" "web" {
  name  = "${local.name}-web"
  scope = "REGIONAL"

  default_action {
    allow {}
  }

  # 50 requests a second per client IP, as the OCI API Gateway's rate limit (WAF counts over 5 minutes).
  rule {
    name     = "rate-limit"
    priority = 1
    action {
      block {}
    }
    statement {
      rate_based_statement {
        limit              = 15000
        aggregate_key_type = "IP"
      }
    }
    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "${local.name}-rate-limit"
      sampled_requests_enabled   = true
    }
  }

  rule {
    name     = "aws-common"
    priority = 2
    override_action {
      none {}
    }
    statement {
      managed_rule_group_statement {
        name        = "AWSManagedRulesCommonRuleSet"
        vendor_name = "AWS"
      }
    }
    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "${local.name}-aws-common"
      sampled_requests_enabled   = true
    }
  }

  visibility_config {
    cloudwatch_metrics_enabled = true
    metric_name                = "${local.name}-web"
    sampled_requests_enabled   = true
  }
}

# ---------- Observability ----------
# Prometheus, Grafana, Tempo and Loki run in-cluster exactly as on OCI (LLD section 15). Service logs
# also go to CloudWatch; the OpenTelemetry Collector can add an X-Ray exporter beside Tempo.

resource "aws_cloudwatch_log_group" "app" {
  name              = "/pbp/${var.env}/app"
  retention_in_days = 90
}
