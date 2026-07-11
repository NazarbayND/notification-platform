import http from "k6/http";
import { check } from "k6";

const BASE_URL = (__ENV.BASE_URL || "http://localhost:8081").replace(/\/$/, "");
const RATE = Number.parseInt(__ENV.RATE || "100", 10);
const DURATION = __ENV.DURATION || "5m";
const CHANNEL = __ENV.CHANNEL || "EMAIL";
const TEMPLATE_KEY = __ENV.TEMPLATE_KEY || "welcome";

export const options = {
  scenarios: {
    normal_load: {
      executor: "constant-arrival-rate",
      rate: RATE,
      timeUnit: "1s",
      duration: DURATION,
      preAllocatedVUs: Math.max(20, Math.ceil(RATE / 2)),
      maxVUs: Math.max(100, RATE * 2),
    },
  },
  thresholds: {
    http_req_failed: ["rate<0.01"],
    http_req_duration: ["p(95)<1000"],
  },
};

function uniqueValue() {
  return `${Date.now()}-${__VU}-${__ITER}-${Math.random().toString(36).slice(2)}`;
}

export default function () {
  const unique = uniqueValue();
  const recipient = `user-${unique}@example.com`;
  const idempotencyKey = `load-${unique}`;
  const payload = {
    channel: CHANNEL,
    templateKey: TEMPLATE_KEY,
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
