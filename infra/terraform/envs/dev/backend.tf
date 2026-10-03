# State in the bootstrap's bucket, locked with an S3 lock file (<key>.tflock, written with S3 conditional writes),
# so no DynamoDB table. The DynamoDB arguments are deprecated since Terraform 1.11.
#
# bucket and region are not written here: a backend block can't use variables, and the bucket name contains the
# account id, which stays out of the repo. They're given at init (partial configuration):
#   terraform -chdir=infra/terraform/envs/dev init "-backend-config=bucket=<bootstrap output state_bucket>" "-backend-config=region=ap-south-1"
terraform {
  backend "s3" {
    key          = "envs/dev/terraform.tfstate"
    encrypt      = true
    use_lockfile = true
  }
}
