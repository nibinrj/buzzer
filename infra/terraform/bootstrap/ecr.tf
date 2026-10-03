# One image repository per service. They outlive every demo: images are pushed once per commit and every demo-up
# pulls them from here, instead of uploading ~1 GB again each time.

resource "aws_ecr_repository" "service" {
  for_each = var.services

  name = "${var.project}/${each.key}"

  # A tag means one image forever: the git commit it was built from. Pushing a different image under an existing tag
  # fails, so what a task definition names is what runs (tasks.ps1 push refuses uncommitted changes for this reason).
  image_tag_mutability = "IMMUTABLE"

  # Basic scanning: free, runs on every push, results in the console and the API.
  image_scanning_configuration {
    scan_on_push = true
  }

  encryption_configuration {
    encryption_type = "AES256"
  }
}

# Storage is billed per GB-month: keep the last few images, expire the rest.
resource "aws_ecr_lifecycle_policy" "service" {
  for_each = aws_ecr_repository.service

  repository = each.value.name

  policy = jsonencode({
    rules = [
      {
        rulePriority = 1
        description  = "Keep the last ${var.images_to_keep} images"
        selection = {
          tagStatus   = "any"
          countType   = "imageCountMoreThan"
          countNumber = var.images_to_keep
        }
        action = {
          type = "expire"
        }
      }
    ]
  })
}
