# The way in from the internet: an Application Load Balancer in the public subnets, plain HTTP on port 80.
# HTTPS needs a certificate for a domain this project doesn't own yet (ADR-006: what changes for production).
#
# Phase 6 forwards everything to identity-service, the only service so far. Phase 7 points the listener at the
# gateway instead, and identity-service goes back to being reachable through the gateway only.

resource "aws_security_group" "alb" {
  name        = "${local.name}-alb"
  description = "Internet to the ALB on HTTP"
  vpc_id      = module.network.vpc_id

  tags = {
    Name = "${local.name}-alb"
  }
}

resource "aws_vpc_security_group_ingress_rule" "alb_http" {
  security_group_id = aws_security_group.alb.id
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "tcp"
  from_port         = 80
  to_port           = 80
  description       = "HTTP from anywhere"
}

# Out: only to the identity tasks, only on their port. Each service the ALB forwards to gets a rule like this.
resource "aws_vpc_security_group_egress_rule" "alb_to_identity" {
  security_group_id            = aws_security_group.alb.id
  referenced_security_group_id = module.identity.security_group_id
  ip_protocol                  = "tcp"
  from_port                    = 8081
  to_port                      = 8081
  description                  = "To identity-service tasks"
}

resource "aws_lb" "this" {
  name               = local.name
  load_balancer_type = "application"
  internal           = false
  security_groups    = [aws_security_group.alb.id]
  subnets            = module.network.public_subnet_ids

  # Seconds a connection may sit with no traffic. STOMP heart-beats every 10 s keep WebSockets well inside it.
  idle_timeout = 60

  # Requests with malformed headers are dropped at the edge, not passed on to the services.
  drop_invalid_header_fields = true

  # A demo environment is destroyed after every demo; deletion protection would block demo-down.
  enable_deletion_protection = false
}

resource "aws_lb_target_group" "identity" {
  name        = "${local.name}-identity"
  port        = 8081
  protocol    = "HTTP"
  target_type = "ip" # Fargate tasks are registered by their IP (awsvpc), not by instance
  vpc_id      = module.network.vpc_id

  # How long a stopping task keeps its open connections before the ALB cuts them (ECS's stand-in for preStop).
  deregistration_delay = 30

  # /readyz on the service port (K.3b): UP only when the service can take traffic.
  health_check {
    path                = "/readyz"
    matcher             = "200"
    interval            = 15
    timeout             = 5
    healthy_threshold   = 2
    unhealthy_threshold = 3
  }
}

resource "aws_lb_listener" "http" {
  load_balancer_arn = aws_lb.this.arn
  port              = 80
  protocol          = "HTTP"

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.identity.arn
  }
}
