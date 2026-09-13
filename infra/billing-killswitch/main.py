"""Budget kill-switch — the only thing that makes "< $5/month" a hard guarantee.

GCP budget *alerts* merely notify; they do not stop spend. This Cloud Function is subscribed to the
budget's Pub/Sub topic and DISABLES billing on the project once actual cost reaches the budget. That
hard-stops every billable resource (the service goes offline) until billing is manually re-enabled —
for a portfolio project, "temporarily offline" beats a surprise bill.

Deploy: see infra/README.md. Trigger: the Pub/Sub topic attached to the budget (via Eventarc, so the
function is a gen2 CloudEvent function — the message payload is at cloud_event.data["message"]["data"]).
"""
import base64
import json
import os

import functions_framework
import googleapiclient.discovery

PROJECT_ID = os.environ["GCP_PROJECT_ID"]
PROJECT_NAME = f"projects/{PROJECT_ID}"


def parse_notification(cloud_event_data: dict) -> tuple[float, float]:
    """(costAmount, budgetAmount) from a budget notification; (0, 0) when either is absent."""
    payload = json.loads(base64.b64decode(cloud_event_data["message"]["data"]).decode("utf-8"))
    return float(payload.get("costAmount") or 0), float(payload.get("budgetAmount") or 0)


def should_disable(cost: float, budget: float) -> bool:
    # A missing or zero budget is a malformed notification, never a reason to cut billing.
    return budget > 0 and cost >= budget


@functions_framework.cloud_event
def stop_billing(cloud_event):
    """Pub/Sub-triggered (Eventarc). Disables billing when cost >= budget."""
    cost, budget = parse_notification(cloud_event.data)
    print(f"budget notification: cost={cost} budget={budget}")

    if not should_disable(cost, budget):
        print("under budget (or no budget in the message) — no action")
        return

    billing = googleapiclient.discovery.build("cloudbilling", "v1", cache_discovery=False)
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
