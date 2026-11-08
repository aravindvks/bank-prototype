# Network (HLD section 8): one VCN, four subnets.
#   public    — API Gateway, the ingress and EHI load balancers
#   oke-api   — Kubernetes API endpoint (private)
#   workers   — OKE worker nodes and pods (private, egress through NAT with a reserved IP that
#               vendors allow-list)
#   data      — PostgreSQL, Streaming, Cache private endpoints (private)

locals {
  name = "pbp-${var.env}"
  cidr = {
    public  = cidrsubnet(var.vcn_cidr, 8, 0)  # 10.20.0.0/24
    oke_api = cidrsubnet(var.vcn_cidr, 8, 1)  # 10.20.1.0/24
    workers = cidrsubnet(var.vcn_cidr, 8, 10) # 10.20.10.0/24
    data    = cidrsubnet(var.vcn_cidr, 8, 20) # 10.20.20.0/24
  }
}

data "oci_core_services" "all" {
  filter {
    name   = "name"
    values = ["All .* Services In Oracle Services Network"]
    regex  = true
  }
}

resource "oci_core_vcn" "main" {
  compartment_id = var.compartment_id
  display_name   = local.name
  cidr_blocks    = [var.vcn_cidr]
  dns_label      = "pbp${var.env}"
}

resource "oci_core_internet_gateway" "igw" {
  compartment_id = var.compartment_id
  vcn_id         = oci_core_vcn.main.id
  display_name   = "${local.name}-igw"
}

# Reserved egress IP: Finastra, Thredd and Atfinity allow-list this address.
resource "oci_core_public_ip" "nat" {
  compartment_id = var.compartment_id
  lifetime       = "RESERVED"
  display_name   = "${local.name}-nat-egress"
}

resource "oci_core_nat_gateway" "nat" {
  compartment_id = var.compartment_id
  vcn_id         = oci_core_vcn.main.id
  display_name   = "${local.name}-nat"
  public_ip_id   = oci_core_public_ip.nat.id
}

resource "oci_core_service_gateway" "sgw" {
  compartment_id = var.compartment_id
  vcn_id         = oci_core_vcn.main.id
  display_name   = "${local.name}-sgw"
  services {
    service_id = data.oci_core_services.all.services[0].id
  }
}

resource "oci_core_route_table" "public" {
  compartment_id = var.compartment_id
  vcn_id         = oci_core_vcn.main.id
  display_name   = "${local.name}-public"
  route_rules {
    destination       = "0.0.0.0/0"
    destination_type  = "CIDR_BLOCK"
    network_entity_id = oci_core_internet_gateway.igw.id
  }
}

resource "oci_core_route_table" "private" {
  compartment_id = var.compartment_id
  vcn_id         = oci_core_vcn.main.id
  display_name   = "${local.name}-private"
  route_rules {
    destination       = "0.0.0.0/0"
    destination_type  = "CIDR_BLOCK"
    network_entity_id = oci_core_nat_gateway.nat.id
  }
  route_rules {
    destination       = data.oci_core_services.all.services[0].cidr_block
    destination_type  = "SERVICE_CIDR_BLOCK"
    network_entity_id = oci_core_service_gateway.sgw.id
  }
}

resource "oci_core_security_list" "public" {
  compartment_id = var.compartment_id
  vcn_id         = oci_core_vcn.main.id
  display_name   = "${local.name}-public"
  egress_security_rules {
    destination = var.vcn_cidr
    protocol    = "6"
  }
  ingress_security_rules {
    source   = "0.0.0.0/0"
    protocol = "6"
    tcp_options {
      min = 443
      max = 443
    }
  }
}

resource "oci_core_security_list" "private" {
  compartment_id = var.compartment_id
  vcn_id         = oci_core_vcn.main.id
  display_name   = "${local.name}-private"
  egress_security_rules {
    destination = "0.0.0.0/0"
    protocol    = "all"
  }
  ingress_security_rules {
    source   = var.vcn_cidr
    protocol = "all"
  }
}

resource "oci_core_subnet" "public" {
  compartment_id    = var.compartment_id
  vcn_id            = oci_core_vcn.main.id
  display_name      = "${local.name}-public"
  cidr_block        = local.cidr.public
  dns_label         = "pub"
  route_table_id    = oci_core_route_table.public.id
  security_list_ids = [oci_core_security_list.public.id]
}

resource "oci_core_subnet" "oke_api" {
  compartment_id             = var.compartment_id
  vcn_id                     = oci_core_vcn.main.id
  display_name               = "${local.name}-oke-api"
  cidr_block                 = local.cidr.oke_api
  dns_label                  = "okeapi"
  prohibit_public_ip_on_vnic = true
  route_table_id             = oci_core_route_table.private.id
  security_list_ids          = [oci_core_security_list.private.id]
}

resource "oci_core_subnet" "workers" {
  compartment_id             = var.compartment_id
  vcn_id                     = oci_core_vcn.main.id
  display_name               = "${local.name}-workers"
  cidr_block                 = local.cidr.workers
  dns_label                  = "workers"
  prohibit_public_ip_on_vnic = true
  route_table_id             = oci_core_route_table.private.id
  security_list_ids          = [oci_core_security_list.private.id]
}

resource "oci_core_subnet" "data" {
  compartment_id             = var.compartment_id
  vcn_id                     = oci_core_vcn.main.id
  display_name               = "${local.name}-data"
  cidr_block                 = local.cidr.data
  dns_label                  = "data"
  prohibit_public_ip_on_vnic = true
  route_table_id             = oci_core_route_table.private.id
  security_list_ids          = [oci_core_security_list.private.id]
}
