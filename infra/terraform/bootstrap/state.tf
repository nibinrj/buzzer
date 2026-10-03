# The bucket every other stack keeps its Terraform state in. State can hold secrets (a generated database
# password ends up in it), so: private, encrypted, TLS only, versioned.

resource "aws_s3_bucket" "state" {
  # Bucket names are global across all AWS accounts: the account id and region make this one unique, without
  # writing either into the code.
  bucket = "${var.project}-tfstate-${data.aws_caller_identity.current.account_id}-${var.region}"

  # Losing the state means Terraform forgets everything it created. A destroy of this stack must be a decision made
  # by editing this line, never an accident.
  lifecycle {
    prevent_destroy = true
  }
}

# Every write keeps the previous version: a broken or wrong state can be rolled back.
resource "aws_s3_bucket_versioning" "state" {
  bucket = aws_s3_bucket.state.id

  versioning_configuration {
    status = "Enabled"
  }
}

# SSE-S3 (AES256): encrypted at rest with keys S3 manages. Free, unlike a KMS key (per-request charges).
resource "aws_s3_bucket_server_side_encryption_configuration" "state" {
  bucket = aws_s3_bucket.state.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

# No public access of any kind, whatever a later policy or ACL might say.
resource "aws_s3_bucket_public_access_block" "state" {
  bucket = aws_s3_bucket.state.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

# ACLs off: access is decided by IAM and the bucket policy only.
resource "aws_s3_bucket_ownership_controls" "state" {
  bucket = aws_s3_bucket.state.id

  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

# Old versions and abandoned uploads don't pile up forever.
resource "aws_s3_bucket_lifecycle_configuration" "state" {
  bucket = aws_s3_bucket.state.id

  rule {
    id     = "expire-old-state-versions"
    status = "Enabled"

    filter {}

    noncurrent_version_expiration {
      noncurrent_days = var.noncurrent_state_days
    }

    abort_incomplete_multipart_upload {
      days_after_initiation = 7
    }
  }

  # Lifecycle rules act on versions, so versioning must be on first.
  depends_on = [aws_s3_bucket_versioning.state]
}

# Refuse any request that isn't over HTTPS, including from this account.
data "aws_iam_policy_document" "state_tls_only" {
  statement {
    sid     = "DenyInsecureTransport"
    effect  = "Deny"
    actions = ["s3:*"]
    resources = [
      aws_s3_bucket.state.arn,
      "${aws_s3_bucket.state.arn}/*",
    ]

    principals {
      type        = "*"
      identifiers = ["*"]
    }

    condition {
      test     = "Bool"
      variable = "aws:SecureTransport"
      values   = ["false"]
    }
  }
}

resource "aws_s3_bucket_policy" "state" {
  bucket = aws_s3_bucket.state.id
  policy = data.aws_iam_policy_document.state_tls_only.json

  # S3 evaluates a new policy against the public access block: create the block first.
  depends_on = [aws_s3_bucket_public_access_block.state]
}
