# Notification Platform Load Tests

Simple k6 load tests for `POST /notifications`.

## Normal Load

```sh
RATE=100 DURATION=5m k6 run tests/load/load-test.js
```

## Approximate 100k Total Requests

```sh
RATE=500 DURATION=200s k6 run tests/load/load-test.js
```

## Throughput Test

```sh
k6 run tests/load/throughput-test.js
```

## Kubernetes Port-Forward

```sh
BASE_URL=http://localhost:8080 k6 run tests/load/load-test.js
```

## Environment Variables

`load-test.js` supports:

- `BASE_URL`, default `http://localhost:8080`
- `RATE`, default `100`
- `DURATION`, default `5m`
- `CHANNEL`, default `EMAIL`
- `TEMPLATE_KEY`, default `welcome`

`throughput-test.js` supports:

- `BASE_URL`, default `http://localhost:8080`
- `CHANNEL`, default `EMAIL`
- `TEMPLATE_KEY`, default `welcome`
- `MAX_VUS`, default `2000`

## How To Interpret Results

- If API latency grows, `notification-api-service` or the database may be the bottleneck.
- If API latency is fine but outbox pending grows, `outbox-publisher-service` is the bottleneck.
- If broker queues grow, worker services are the bottleneck.
- If provider errors grow, the provider or test provider is the bottleneck.
