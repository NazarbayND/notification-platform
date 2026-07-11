import http from "k6/http";
import { check, sleep } from "k6";
import { Counter, Trend } from "k6/metrics";

const API_URL = (__ENV.BASE_URL || "http://localhost:8081").replace(/\/$/, "");
const PROJECTION_URL = (__ENV.PROJECTION_URL || "http://localhost:8092").replace(/\/$/, "");
const RATE = Number.parseInt(__ENV.RATE || "50", 10);
const DURATION = __ENV.DURATION || "30s";
const POLL_INTERVAL_SECONDS = Number.parseFloat(__ENV.POLL_INTERVAL_SECONDS || "0.25");
const STATUS_TIMEOUT_SECONDS = Number.parseInt(__ENV.STATUS_TIMEOUT_SECONDS || "30", 10);

const delivered = new Counter("e2e_delivered");
const failed = new Counter("e2e_failed");
const timedOut = new Counter("e2e_status_timeout");
const deliveryLatency = new Trend("e2e_delivery_duration", true);

export const options = {
  scenarios: {
    end_to_end: {
      executor: "constant-arrival-rate",
      rate: RATE,
      timeUnit: "1s",
      duration: DURATION,
      preAllocatedVUs: Math.max(20, RATE),
      maxVUs: Number.parseInt(__ENV.MAX_VUS || String(Math.max(200, RATE * 4)), 10),
    },
  },
  thresholds: {
    intake_unexpected_status: ["count==0"],
    e2e_status_timeout: [`count<=${__ENV.MAX_STATUS_TIMEOUTS || "0"}`],
    e2e_delivery_duration: [`p(95)<${__ENV.E2E_P95_MS || "10000"}`],
  },
};

const unexpected = new Counter("intake_unexpected_status");

export default function () {
  const unique = `${Date.now()}-${__VU}-${__ITER}-${Math.random().toString(36).slice(2)}`;
  const acceptedAt = Date.now();
  const channel = (__ENV.CHANNEL || "EMAIL").toUpperCase();
  const destination = channel === "SMS" ? `+973${String(__VU).padStart(8, "0")}`
    : channel === "PUSH" ? `push-${unique}`
      : channel === "WEBHOOK" ? `${__ENV.WEBHOOK_URL || "http://webhook-worker-service:8090/webhooks/test"}`
        : channel === "IN_APP" ? `user-${unique}` : `user-${unique}@example.com`;
  const response = http.post(`${API_URL}/notifications`, JSON.stringify({
    tenantId: "e2e-tenant",
    productId: "default",
    userId: `user-${unique}`,
    channel,
    templateKey: __ENV.TEMPLATE_KEY || "welcome",
    variables: { name: "End-to-End User" },
    idempotencyKey: `e2e-${unique}`,
    destination,
    priority: "NORMAL",
  }), { headers: { "Content-Type": "application/json", "X-Correlation-Id": `e2e-${unique}` } });

  if (response.status !== 202) {
    if (![429, 503].includes(response.status)) unexpected.add(1);
    check(response, { "controlled intake response": (result) => [202, 429, 503].includes(result.status) });
    return;
  }

  const notificationId = response.json("notificationId");
  const deadline = Date.now() + STATUS_TIMEOUT_SECONDS * 1000;
  while (Date.now() < deadline) {
    const statusResponse = http.get(`${PROJECTION_URL}/projections/notifications/${notificationId}/status`);
    if (statusResponse.status === 200) {
      const status = statusResponse.json("status");
      if (["DELIVERED", "PARTIALLY_DELIVERED", "FAILED", "REJECTED"].includes(status)) {
        deliveryLatency.add(Date.now() - acceptedAt);
        if (["DELIVERED", "PARTIALLY_DELIVERED"].includes(status)) delivered.add(1);
        else failed.add(1);
        return;
      }
    }
    sleep(POLL_INTERVAL_SECONDS);
  }
  timedOut.add(1);
}
