# The demo's network (ADR-006):
#
#   public subnets   ALB + ECS tasks. Route to the internet gateway. Tasks get a public IP to reach ECR,
#                    CloudWatch Logs and Secrets Manager directly, so there's no NAT gateway. What may reach a task is
#                    decided by its security group (the ALB only), not by the subnet.
#   private subnets  RDS + ElastiCache. No route out at all: they never need the internet.
#
# No NAT gateway and no interface endpoints: both bill per hour, more than the public IPs they'd replace.
# One free S3 gateway endpoint.

data "aws_availability_zones" "available" {
  state = "available"

  # Regular AZs only: Local Zones and Wavelength Zones have to be opted into and don't run every service.
  filter {
    name   = "opt-in-status"
    values = ["opt-in-not-required"]
  }
}

data "aws_region" "current" {}

locals {
  azs = slice(data.aws_availability_zones.available.names, 0, var.az_count)

  # One /24 per subnet. Public from .0, private from .100: adding an AZ never renumbers an existing subnet.
  public_cidrs  = { for index, az in local.azs : az => cidrsubnet(var.cidr, 8, index) }
  private_cidrs = { for index, az in local.azs : az => cidrsubnet(var.cidr, 8, 100 + index) }
}

resource "aws_vpc" "this" {
  cidr_block = var.cidr

  # DNS names inside the VPC: RDS and ElastiCache endpoints are hostnames, and the S3 endpoint relies on them too.
  enable_dns_support   = true
  enable_dns_hostnames = true

  tags = {
    Name = var.name
  }
}

# A new VPC's default security group lets everything inside it talk to everything. With no rules here, Terraform
# empties it: anything that forgets to name a security group gets no access at all.
resource "aws_default_security_group" "default" {
  vpc_id = aws_vpc.this.id
}

resource "aws_internet_gateway" "this" {
  vpc_id = aws_vpc.this.id

  tags = {
    Name = var.name
  }
}

# --- Public: ALB and ECS tasks --------------------------------------------------------------------------------------

resource "aws_subnet" "public" {
  for_each = local.public_cidrs

  vpc_id            = aws_vpc.this.id
  availability_zone = each.key
  cidr_block        = each.value

  # Nothing gets a public IP just by starting here. ECS asks for one per task (assign_public_ip), explicitly.
  map_public_ip_on_launch = false

  tags = {
    Name = "${var.name}-public-${each.key}"
    Tier = "public"
  }
}

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.this.id

  tags = {
    Name = "${var.name}-public"
  }
}

resource "aws_route" "public_internet" {
  route_table_id         = aws_route_table.public.id
  destination_cidr_block = "0.0.0.0/0"
  gateway_id             = aws_internet_gateway.this.id
}

resource "aws_route_table_association" "public" {
  for_each = aws_subnet.public

  subnet_id      = each.value.id
  route_table_id = aws_route_table.public.id
}

# --- Private: RDS and ElastiCache ------------------------------------------------------------------------------------

resource "aws_subnet" "private" {
  for_each = local.private_cidrs

  vpc_id            = aws_vpc.this.id
  availability_zone = each.key
  cidr_block        = each.value

  tags = {
    Name = "${var.name}-private-${each.key}"
    Tier = "private"
  }
}

# Only the implicit "local" route (the VPC itself), plus the S3 endpoint below: no way to the internet.
resource "aws_route_table" "private" {
  vpc_id = aws_vpc.this.id

  tags = {
    Name = "${var.name}-private"
  }
}

resource "aws_route_table_association" "private" {
  for_each = aws_subnet.private

  subnet_id      = each.value.id
  route_table_id = aws_route_table.private.id
}

# --- S3 gateway endpoint (free) --------------------------------------------------------------------------------------

# A route-table entry, not a network interface: no hourly charge, no per-GB charge. S3 traffic (ECR stores image
# layers in S3) stays on AWS's network. Interface endpoints for other services would bill per hour, so there are none.
resource "aws_vpc_endpoint" "s3" {
  vpc_id            = aws_vpc.this.id
  service_name      = "com.amazonaws.${data.aws_region.current.region}.s3"
  vpc_endpoint_type = "Gateway"
  route_table_ids   = [aws_route_table.public.id, aws_route_table.private.id]

  tags = {
    Name = "${var.name}-s3"
  }
}
