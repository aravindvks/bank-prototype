# The same outputs as the OCI and AWS cells, so values-<cloud>.yaml is filled the same way.

output "nat_egress_ip" {
  description = "Give this to Finastra, Thredd and Atfinity for their allow-lists."
  value       = azurerm_public_ip.nat.ip_address
}

output "cluster_name" {
  description = "az aks get-credentials -g <resource_group> -n <this> (from inside the VNet: the API endpoint is private)."
  value       = azurerm_kubernetes_cluster.aks.name
}

output "resource_group" {
  value = azurerm_resource_group.rg.name
}

output "postgres_endpoint" {
  description = "values-azure.yaml config.DB_HOST"
  value       = azurerm_postgresql_flexible_server.pg.fqdn
}

output "kafka_bootstrap" {
  description = "values-azure.yaml config.KAFKA_BOOTSTRAP (SASL_SSL, PLAIN, username $ConnectionString)."
  value       = "${azurerm_eventhub_namespace.kafka.name}.servicebus.windows.net:9093"
}

output "cache_endpoint" {
  description = "values-azure.yaml config.REDIS_HOST (TLS on port 6380)."
  value       = azurerm_redis_cache.cache.hostname
}

output "registry" {
  description = "Image prefix for the release pipeline (values-azure.yaml image.registry)."
  value       = "${azurerm_container_registry.acr.login_server}/pbp"
}

output "key_vault_uri" {
  description = "For the External Secrets ClusterSecretStore (azure-key-vault)."
  value       = azurerm_key_vault.kv.vault_uri
}

output "external_secrets_client_id" {
  description = "Annotate the external-secrets service account: azure.workload.identity/client-id."
  value       = azurerm_user_assigned_identity.external_secrets.client_id
}

output "public_ip" {
  description = "Point web_hostname and ehi_hostname at this address."
  value       = azurerm_public_ip.appgw.ip_address
}

output "appinsights_connection_string" {
  value     = azurerm_application_insights.apm.connection_string
  sensitive = true
}
