import http from "k6/http";
import { check, sleep } from "k6";

const BASE_URL = __ENV.BASE_URL || "http://localhost:8080";
const PRODUCT_ID = __ENV.PRODUCT_ID;
const TEMPLATE_KEY = __ENV.TEMPLATE_KEY || "load-test-email";

export const options = {
  vus: 1,
  iterations: 10,
  thresholds: {
    http_req_failed: ["rate<0.01"],
    http_req_duration: ["p(95)<500"],
    checks: ["rate>0.99"],
  },
};

export function setup() {
  if (PRODUCT_ID) {
    return { productId: PRODUCT_ID, templateKey: TEMPLATE_KEY };
  }

  const suffix = unique("smoke");
  const product = postJson("/api/v1/admin/products", {
    name: `Load Smoke ${suffix}`,
  });

  check(product, {
    "product created": (res) => res.status === 201,
  });

  const productId = product.json("id");
  const templateKey = `${TEMPLATE_KEY}-${suffix}`;

  const template = postJson("/api/v1/admin/templates", {
    productId,
    templateKey,
    channel: "EMAIL",
    version: 1,
    subject: "Load test smoke",
    content: "Hello {{name}}, this is a smoke notification.",
    status: "ACTIVE",
  });

  check(template, {
    "template created": (res) => res.status === 201,
  });

  return { productId, templateKey };
}

export default function (data) {
  const response = postJson("/api/v1/notifications", notificationPayload(data, "smoke"));

  check(response, {
    "notification created": (res) => res.status === 201,
    "notification id returned": (res) => Boolean(res.json("id")),
    "notification latency acceptable": (res) => res.timings.duration < 500,
  });

  sleep(0.2);
}

function notificationPayload(data, prefix) {
  const id = unique(prefix);

  return {
    productId: data.productId,
    templateKey: data.templateKey,
    requestedChannels: ["EMAIL"],
    externalUserId: `load-user-${id}`,
    idempotencyKey: `load-${id}`,
    category: "LOAD_TEST",
    priority: "NORMAL",
    payload: {
      name: "Load Tester",
      runId: id,
    },
    recipient: {
      email: `load-${id}@example.test`,
    },
  };
}

function postJson(path, body) {
  return http.post(`${BASE_URL}${path}`, JSON.stringify(body), {
    headers: { "Content-Type": "application/json" },
    timeout: "30s",
  });
}

function unique(prefix) {
  const vu = typeof __VU === "undefined" ? 0 : __VU;
  const iter = typeof __ITER === "undefined" ? 0 : __ITER;
  return `${prefix}-${Date.now()}-${vu}-${iter}-${Math.random().toString(36).slice(2, 10)}`;
}
