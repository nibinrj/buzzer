# The safety net for a forgotten demo-down: an email when the account's spend this month passes the limit, or is
# forecast to. A demo left running costs a few dollars a day (ADR-007), so a $5 monthly limit catches it within a
# day or two. Account-wide, not filtered by tag: whatever is costing money should trigger it. The first two budgets
# of an account are free.

resource "aws_budgets_budget" "monthly" {
  name         = "${var.project}-monthly"
  budget_type  = "COST"
  limit_amount = var.monthly_budget_usd
  limit_unit   = "USD"
  time_unit    = "MONTHLY"

  # Actual spend has passed 80% of the limit.
  notification {
    comparison_operator        = "GREATER_THAN"
    threshold                  = 80
    threshold_type             = "PERCENTAGE"
    notification_type          = "ACTUAL"
    subscriber_email_addresses = [var.budget_alert_email]
  }

  # AWS forecasts the month will end above the limit: usually the first sign of a demo left running.
  notification {
    comparison_operator        = "GREATER_THAN"
    threshold                  = 100
    threshold_type             = "PERCENTAGE"
    notification_type          = "FORECASTED"
    subscriber_email_addresses = [var.budget_alert_email]
  }
}
