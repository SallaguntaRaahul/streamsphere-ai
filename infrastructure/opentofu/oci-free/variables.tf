variable "tenancy_ocid" { type = string }
variable "compartment_ocid" { type = string }
variable "home_region" { type = string }
variable "ubuntu_arm64_image_ocid" { type = string }
variable "ssh_public_key" { type = string }

variable "admin_cidr" {
  type        = string
  description = "Operator IP/CIDR allowed to SSH (never 0.0.0.0/0)"
  validation {
    condition     = var.admin_cidr != "0.0.0.0/0"
    error_message = "SSH must not be open to the internet."
  }
}

variable "ocpus" {
  type    = number
  default = 2
  validation {
    condition     = var.ocpus > 0 && var.ocpus <= 2
    error_message = "Always Free A1 allows at most 2 OCPUs in total."
  }
}

variable "memory_gbs" {
  type    = number
  default = 12
  validation {
    condition     = var.memory_gbs > 0 && var.memory_gbs <= 12
    error_message = "Always Free A1 allows at most 12 GB in total."
  }
}

variable "boot_volume_gbs" {
  type    = number
  default = 100
  validation {
    condition     = var.boot_volume_gbs >= 50 && var.boot_volume_gbs <= 200
    error_message = "Always Free block storage is 200 GB total (boot + block)."
  }
}
