# merchant-gateway-resilience4j-demo

A runnable demo for merchant traffic pool isolation based on `Resilience4j + Bulkhead + CircuitBreaker + Retry`.

## What this demo includes

- Merchant tier isolation: `vip` / `normal` / `default`
- Per-tier resilience set: `Bulkhead + CircuitBreaker + Retry`
- Atomic config refresh with in-flight request safety
- Dynamic config source options:
  - local (default): update by REST API
  - Nacos: subscribe YAML and hot apply
  - Apollo: subscribe JSON and hot apply
- Mock downstream endpoint for direct stress testing

## Tech stack

- Java 17
- Spring Boot 3.4.4 (WebFlux)
- Resilience4j
- Optional config center clients: Nacos / Apollo

## Quick start (local mode)

1. Start app:

```bash
mvn spring-boot:run
```

2. Check current config:

```bash
curl http://localhost:8088/admin/pool-config/pretty
```

3. Send gateway request:

```bash
curl "http://localhost:8088/gateway/inventory/sku-1?failPercent=0&delayMs=20" -H "X-Merchant-Id: m1001"
```

4. Simulate failures (trigger retry/circuit behavior):

```bash
curl "http://localhost:8088/gateway/inventory/sku-1?failPercent=80&delayMs=50" -H "X-Merchant-Id: m2001"
```

5. Hot update pool config by REST (local mode):

```bash
curl -X PUT "http://localhost:8088/admin/pool-config" \
  -H "Content-Type: application/json" \
  -d '{
    "version":"local-v2",
    "defaultTier":"default",
    "tiers":{
      "default":{"maxConcurrentCalls":5,"maxWaitDurationMs":0,"slidingWindowSize":10,"minimumNumberOfCalls":5,"failureRateThreshold":50,"openStateWaitMs":5000,"halfOpenCalls":3,"retryMaxAttempts":1,"retryWaitMs":100},
      "normal":{"maxConcurrentCalls":10,"maxWaitDurationMs":10,"slidingWindowSize":20,"minimumNumberOfCalls":10,"failureRateThreshold":50,"openStateWaitMs":5000,"halfOpenCalls":3,"retryMaxAttempts":2,"retryWaitMs":100},
      "vip":{"maxConcurrentCalls":20,"maxWaitDurationMs":10,"slidingWindowSize":20,"minimumNumberOfCalls":10,"failureRateThreshold":50,"openStateWaitMs":5000,"halfOpenCalls":3,"retryMaxAttempts":3,"retryWaitMs":80}
    },
    "merchantTier":{"m1001":"vip","m2001":"normal"}
  }'
```

## Endpoints

- Gateway proxy: `GET /gateway/inventory/{skuId}`
  - Header: `X-Merchant-Id`
  - Query params:
    - `failPercent` (0-100): downstream failure ratio
    - `delayMs`: downstream delay
- Mock downstream: `GET /downstream/inventory/{skuId}`
- Admin:
  - `GET /admin/pool-config`
  - `GET /admin/pool-config/pretty`
  - `PUT /admin/pool-config` (JSON)
  - `PUT /admin/pool-config/raw` (JSON or YAML plain text)
  - `GET /admin/tier/{merchantId}`

## Nacos mode

1. Publish `src/main/resources/samples/merchant-pool.nacos.yaml` to:
  - DataId: `merchant-pool.yaml`
  - Group: `DEFAULT_GROUP`

2. Start app with:

```bash
mvn spring-boot:run -Dspring-boot.run.arguments="--demo.config-provider=nacos --demo.nacos.server-addr=127.0.0.1:8848"
```

## Apollo mode

1. In Apollo namespace `merchant-pool`, create key `merchant.pool.json`
2. Set value from `src/main/resources/samples/merchant-pool.apollo.json`
3. Start app (example):

```bash
mvn spring-boot:run -Dspring-boot.run.arguments="--demo.config-provider=apollo --apollo.meta=http://127.0.0.1:8080 --app.id=merchant-gateway-demo"
```

## Notes

- Retry should only be used for idempotent requests.
- Prefer tier pools over one pool per merchant in production.
- If remote config parse/apply fails, old snapshot is retained.
