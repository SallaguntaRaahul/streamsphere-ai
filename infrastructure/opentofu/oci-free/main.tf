# OCI Always Free footprint for the oci-free profile: ONE Ampere A1 VM (2 OCPU / 12 GB) running k3s.
# PLAN-ONLY by policy: `tofu apply` requires the owner's explicit, written approval (docs/cost/FREE_TIER_VERIFICATION.md).
# Guardrails: shape/size limited to Always Free values via validation; no load balancer, NAT gateway or paid storage.
terraform {
  required_version = ">= 1.8"
  required_providers {
    oci = { source = "oracle/oci", version = "~> 7.20" }
  }
}

provider "oci" {
  region = var.home_region # Always Free compute must be in the tenancy's home region
}

data "oci_identity_availability_domains" "ads" { compartment_id = var.tenancy_ocid }

resource "oci_core_vcn" "main" {
  compartment_id = var.compartment_ocid
  cidr_blocks    = ["10.42.0.0/16"]
  display_name   = "streamsphere-vcn"
}

resource "oci_core_internet_gateway" "igw" {
  compartment_id = var.compartment_ocid
  vcn_id         = oci_core_vcn.main.id
  display_name   = "streamsphere-igw"
}

resource "oci_core_route_table" "public" {
  compartment_id = var.compartment_ocid
  vcn_id         = oci_core_vcn.main.id
  route_rules {
    destination       = "0.0.0.0/0"
    network_entity_id = oci_core_internet_gateway.igw.id
  }
}

resource "oci_core_security_list" "k3s" {
  compartment_id = var.compartment_ocid
  vcn_id         = oci_core_vcn.main.id
  display_name   = "streamsphere-k3s"
  egress_security_rules {
    destination = "0.0.0.0/0"
    protocol    = "all"
  }
  ingress_security_rules { # HTTPS ingress for the API gateway only
    protocol = "6"
    source   = "0.0.0.0/0"
    tcp_options {
      min = 443
      max = 443
    }
  }
  ingress_security_rules { # SSH restricted to the operator's IP
    protocol = "6"
    source   = var.admin_cidr
    tcp_options {
      min = 22
      max = 22
    }
  }
}

resource "oci_core_subnet" "public" {
  compartment_id    = var.compartment_ocid
  vcn_id            = oci_core_vcn.main.id
  cidr_block        = "10.42.1.0/24"
  route_table_id    = oci_core_route_table.public.id
  security_list_ids = [oci_core_security_list.k3s.id]
  display_name      = "streamsphere-public"
}

resource "oci_core_instance" "k3s" {
  compartment_id      = var.compartment_ocid
  availability_domain = data.oci_identity_availability_domains.ads.availability_domains[0].name
  display_name        = "streamsphere-k3s"
  shape               = "VM.Standard.A1.Flex"
  shape_config {
    ocpus         = var.ocpus
    memory_in_gbs = var.memory_gbs
  }
  source_details {
    source_type             = "image"
    source_id               = var.ubuntu_arm64_image_ocid
    boot_volume_size_in_gbs = var.boot_volume_gbs
  }
  create_vnic_details {
    subnet_id        = oci_core_subnet.public.id
    assign_public_ip = true
  }
  metadata = {
    ssh_authorized_keys = var.ssh_public_key
    user_data           = base64encode(file("${path.module}/cloud-init.yaml"))
  }
  lifecycle { prevent_destroy = true }
}

output "public_ip" { value = oci_core_instance.k3s.public_ip }
