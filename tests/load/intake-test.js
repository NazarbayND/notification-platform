import http from "k6/http";
import { check } from "k6";
import { Counter } from "k6/metrics";

const BASE_URL = (__ENV.BASE_URL || "http://localhost:8081").replace(/\/$/, "");
const RATE = Number.parseInt(__ENV.RATE || "100", 10);
const DURATION = __ENV.DURATION || "30s";
const accepted = new Counter("intake_accepted");
const rejected429 = new Counter("intake_rejected_429");
const rejected503 = new Counter("intake_rejected_503");
const unexpected = new Counter("intake_unexpected_status");

export const options = {
  scenarios: {
    intake: {
      executor: "constant-arrival-rate",
      rate: RATE,
      timeUnit: "1s",
      duration: DURATION,
      preAllocatedVUs: Math.max(20, Math.ceil(RATE / 2)),
      maxVUs: Math.max(100, RATE * 2),
    },
  },
  thresholds: {
    http_req_duration: ["p(95)<1000", "p(99)<2000"],
    intake_unexpected_status: ["count==0"],
  },
};

export default function () {
  const unique = `${Date.now()}-${__VU}-${__ITER}-${Math.random().toString(36).slice(2)}`;
  const idempotencyKey = `intake-${unique}`;
  const response = http.post(`${BASE_URL}/notifications`, JSON.stringify({
    tenantId: "load-tenant",
    productId: "default",
    userId: `user-${unique}`,
    channel: "EMAIL",
    templateKey: "welcome",
    variables: { name: "Load Test User" },
    idempotencyKey,
    destination: `user-${unique}@example.com`,
    priority: "NORMAL",
  }), {
    headers: {
      "Content-Type": "application/json",
      "Idempotency-Key": idempotencyKey,
      "X-Correlation-Id": idempotencyKey,
    },
  });

  if (response.status === 202) accepted.add(1);
  else if (response.status === 429) rejected429.add(1);
  else if (response.status === 503) rejected503.add(1);
  else unexpected.add(1);

  check(response, {
    "controlled intake response": (result) => [202, 429, 503].includes(result.status),
  });
}
