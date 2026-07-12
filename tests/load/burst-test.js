import http from "k6/http";
import { check } from "k6";
import { Counter } from "k6/metrics";

const BASE_URL = (__ENV.BASE_URL || "http://localhost:8081").replace(/\/$/, "");
const unexpected = new Counter("burst_unexpected_status");
const accepted = new Counter("burst_accepted");
const rejected = new Counter("burst_rejected");

export const options = {
  scenarios: {
    burst: {
      executor: "ramping-arrival-rate",
      startRate: 10,
      timeUnit: "1s",
      preAllocatedVUs: 100,
      maxVUs: Number.parseInt(__ENV.MAX_VUS || "1000", 10),
      stages: [
        { target: 10, duration: "5s" },
        { target: Number.parseInt(__ENV.BURST_RATE || "1000", 10), duration: "5s" },
        { target: 10, duration: "10s" },
      ],
    },
  },
  thresholds: { http_req_duration: ["p(99)<3000"], burst_unexpected_status: ["count==0"] },
};

export default function () {
  const unique = `${Date.now()}-${__VU}-${__ITER}`;
  const channel = (__ENV.CHANNEL || "PUSH").toUpperCase();
  const destination = channel === "EMAIL" ? `user-${unique}@example.com`
    : channel === "SMS" ? `+973${String(__VU).padStart(8, "0")}`
      : channel === "IN_APP" ? `user-${unique}` : `push-${unique}`;
  const response = http.post(`${BASE_URL}/notifications`, JSON.stringify({
    tenantId: "burst-tenant", productId: "default", userId: `user-${unique}`,
    channel, templateKey: __ENV.TEMPLATE_KEY || "welcome", variables: { name: "Burst" },
    idempotencyKey: `burst-${unique}`, destination,
  }), { headers: { "Content-Type": "application/json" } });
  if (response.status === 202) accepted.add(1);
  if ([429, 503].includes(response.status)) rejected.add(1);
  if (![202, 429, 503].includes(response.status)) unexpected.add(1);
  check(response, { "explicit outcome": (result) => [202, 429, 503].includes(result.status) });
}
