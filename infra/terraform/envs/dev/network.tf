module "network" {
  source = "../../modules/network"

  name     = local.name
  cidr     = var.vpc_cidr
  az_count = var.az_count
}
