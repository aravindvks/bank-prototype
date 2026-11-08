# The same outputs as the OCI and Azure cells, so values-<cloud>.yaml is filled the same way.

output "nat_egress_ip" {
  description = "Give this to Finastra, Thredd and Atfinity for their allow-lists."
  value       = aws_eip.nat.public_ip
}

output "cluster_name" {
  description = "aws eks update-kubeconfig --name <this> (from inside the VPC: the API endpoint is private)."
  value       = aws_eks_cluster.eks.name
}

output "postgres_endpoint" {
  description = "values-aws.yaml config.DB_HOST"
  value       = aws_db_instance.pg.address
}

output "postgres_replica_endpoint" {
  value = aws_db_instance.pg_replica.address
}

output "kafka_bootstrap" {
  description = "values-aws.yaml config.KAFKA_BOOTSTRAP (SASL_SSL, SCRAM-SHA-512)."
  value       = aws_msk_cluster.kafka.bootstrap_brokers_sasl_scram
}

output "cache_endpoint" {
  description = "values-aws.yaml config.REDIS_HOST (TLS)."
  value       = aws_elasticache_replication_group.cache.primary_endpoint_address
}

output "registry" {
  description = "Image prefix for the release pipeline (values-aws.yaml image.registry)."
  value       = "${data.aws_caller_identity.current.account_id}.dkr.ecr.${var.region}.amazonaws.com/pbp"
}

output "waf_acl_arn" {
  description = "values-aws.yaml aws.wafAclArn"
  value       = aws_wafv2_web_acl.web.arn
}

output "kms_key_arn" {
  value = aws_kms_key.master.arn
}
