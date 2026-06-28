"""Budget kill-switch — the only thing that makes "< $5/month" a hard guarantee.

GCP budget *alerts* merely notify; they do not stop spend. This Cloud Function is subscribed to the
budget's Pub/Sub topic and DISABLES billing on the project once actual cost reaches the budget. That
hard-stops every billable resource (the service goes offline) until billing is manually re-enabled —
for a portfolio project, "temporarily offline" beats a surprise bill.

Deploy: see infra/README.md. Trigger: the Pub/Sub topic attached to the budget.
"""
import base64
import json
import os

import googleapiclient.discovery

PROJECT_ID = os.environ["GCP_PROJECT_ID"]
PROJECT_NAME = f"projects/{PROJECT_ID}"

billing = googleapiclient.discovery.build("cloudbilling", "v1", cache_discovery=False)


def stop_billing(event, context):
    """Pub/Sub-triggered. Disables billing when cost >= budget."""
    data = json.loads(base64.b64decode(event["data"]).decode("utf-8"))
    cost = data.get("costAmount", 0)
    budget = data.get("budgetAmount", 0)
    print(f"budget notification: cost={cost} budget={budget}")

    if cost < budget:
        print("under budget — no action")
        return

    projects = billing.projects()
    info = projects.getBillingInfo(name=PROJECT_NAME).execute()
    if not info.get("billingEnabled"):
        print("billing already disabled — no action")
        return

    projects.updateBillingInfo(
        name=PROJECT_NAME,
        body={"billingAccountName": ""},  # detaching the billing account disables billing
    ).execute()
    print(f"BILLING DISABLED for {PROJECT_NAME} (cost {cost} >= budget {budget})")
