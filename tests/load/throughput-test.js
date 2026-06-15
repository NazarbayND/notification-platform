import http from "k6/http";
import { check } from "k6";

const BASE_URL = (__ENV.BASE_URL || "http://localhost:8080").replace(/\/$/, "");
const CHANNEL = __ENV.CHANNEL || "EMAIL";
const TEMPLATE_KEY = __ENV.TEMPLATE_KEY || "welcome";
const MAX_VUS = Number.parseInt(__ENV.MAX_VUS || "2000", 10);

export const options = {
  scenarios: {
    throughput: {
      executor: "ramping-arrival-rate",
      startRate: 50,
      timeUnit: "1s",
      preAllocatedVUs: Math.min(200, MAX_VUS),
      maxVUs: MAX_VUS,
      stages: [
        { target: 100, duration: "1m" },
        { target: 250, duration: "1m" },
        { target: 500, duration: "1m" },
        { target: 1000, duration: "1m" },
        { target: 2000, duration: "1m" },
        { target: 0, duration: "30s" },
      ],
    },
  },
  thresholds: {
    http_req_failed: ["rate<0.01"],
    http_req_duration: ["p(95)<1000", "p(99)<2000"],
  },
};

function uniqueValue() {
  return `${Date.now()}-${__VU}-${__ITER}-${Math.random().toString(36).slice(2)}`;
}

export default function () {
  const unique = uniqueValue();
  const recipient = `user-${unique}@example.com`;
  const idempotencyKey = `throughput-${unique}`;
  const payload = {
    recipient,
    channel: CHANNEL,
    templateKey: TEMPLATE_KEY,
    payload: {
      name: "Load Test User",
    },
    userId: `user-${unique}`,
    productId: "default",
    variables: {
      name: "Load Test User",
    },
    idempotencyKey,
    destination: recipient,
  };

  const response = http.post(`${BASE_URL}/notifications`, JSON.stringify(payload), {
    headers: {
      "Content-Type": "application/json",
      "Idempotency-Key": idempotencyKey,
      "X-Correlation-Id": idempotencyKey,
    },
  });

  check(response, {
    "created or accepted": (res) => res.status >= 200 && res.status < 300,
  });
}
