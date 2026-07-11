import http from "k6/http";
import { check } from "k6";
import { Counter } from "k6/metrics";

const BASE_URL = (__ENV.BASE_URL || "http://localhost:8081").replace(/\/$/, "");
const CHANNEL = __ENV.CHANNEL || "EMAIL";
const TEMPLATE_KEY = __ENV.TEMPLATE_KEY || "welcome";
const MAX_VUS = Number.parseInt(__ENV.MAX_VUS || "2000", 10);
const accepted = new Counter("stress_accepted");
const rejected429 = new Counter("stress_rejected_429");
const rejected503 = new Counter("stress_rejected_503");
const unexpected = new Counter("stress_unexpected_status");

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
    http_req_duration: ["p(95)<1000", "p(99)<2000"],
    stress_unexpected_status: ["count==0"],
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

  if (response.status === 202) accepted.add(1);
  else if (response.status === 429) rejected429.add(1);
  else if (response.status === 503) rejected503.add(1);
  else unexpected.add(1);
  check(response, { "controlled stress response": (res) => [202, 429, 503].includes(res.status) });
}
