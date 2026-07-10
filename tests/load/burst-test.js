import http from "k6/http";
import { check } from "k6";

const BASE_URL = (__ENV.BASE_URL || "http://localhost:8081").replace(/\/$/, "");

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
  thresholds: { http_req_duration: ["p(99)<3000"] },
};

export default function () {
  const unique = `${Date.now()}-${__VU}-${__ITER}`;
  const response = http.post(`${BASE_URL}/notifications`, JSON.stringify({
    tenantId: "burst-tenant", productId: "default", userId: `user-${unique}`,
    channel: "EMAIL", templateKey: "welcome", variables: { name: "Burst" },
    idempotencyKey: `burst-${unique}`, destination: `user-${unique}@example.com`,
  }), { headers: { "Content-Type": "application/json" } });
  check(response, { "explicit outcome": (result) => [202, 429, 503].includes(result.status) });
}
