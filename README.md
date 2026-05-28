# Offline UPI System — Spring Boot

A production-style implementation of three offline UPI mechanisms:

1. **USSD-based** (*99# style — works on any phone, no internet on user side)
2. **UPI Lite** (on-device wallet with deferred settlement)
3. **P2P Offline Tokens** (cryptographically signed, settles when either party reconnects — modeled on offline-CBDC research)

Built to demonstrate end-to-end systems thinking: idempotency, optimistic locking, RSA signatures, JWT auth, reconciliation, and graceful degradation under partial connectivity.

---

## TL;DR architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                       Spring Boot backend                       │
│  ┌──────────────┐   ┌──────────────┐   ┌────────────────────┐   │
│  │ AuthCtl      │   │ TxnCtl       │   │ OfflineTokenCtl    │   │
│  │ /register    │   │ /transfer    │   │ /issue /redeem     │   │
│  │ /login (JWT) │   │ /lite/load   │   │ /verify            │   │
│  └──────┬───────┘   │ /lite/spend  │   └─────────┬──────────┘   │
│         │           └──────┬───────┘             │              │
│  ┌──────▼─────────────────▼─────────────────────▼──────────┐   │
│  │  AccountService  TransactionService  OfflineTokenService │   │
│  │   (BCrypt PIN)    (idempotency,        (RSA-2048 sign,   │   │
│  │   (RSA keygen)     @Version locking,    nonce uniqueness,│   │
│  │                    retry)               reconciler)      │   │
│  └─────────────────────────┬─────────────────────────────────┘  │
│                            ▼                                    │
│        H2 (account, transaction, offline_token, ussd_session)   │
└─────────────────────────────────────────────────────────────────┘
              ▲                       ▲                ▲
              │ HTTPS                 │ HTTPS          │ HTTPS
   ┌──────────┴─────────┐   ┌─────────┴────────┐   ┌──┴──────────┐
   │ Telecom USSD       │   │ Mobile app       │   │ Mobile app  │
   │ gateway (Africa's  │   │ (online)         │   │ (offline →  │
   │ Talking, Infobip)  │   │                  │   │  online)    │
   └──────────┬─────────┘   └──────────────────┘   └─────────────┘
              │ GSM USSD
   ┌──────────▼─────────┐
   │ Feature phone      │
   │ (no data)          │
   └────────────────────┘
```

## Why these three mechanisms

| Mechanism | User connectivity | Security model | Settlement |
|---|---|---|---|
| **USSD** | Phone offline (no data); carrier connects | PIN over GSM USSD (encrypted by carrier) | Real-time at backend |
| **UPI Lite** | Phone may be offline at spend time | Device unlock = authn; pre-loaded wallet caps risk | Batched at backend on next sync |
| **P2P offline tokens** | Both parties may be offline | RSA signature on token; backend verifies on redeem | Eventual — when either party next online |

The three approaches sit on a spectrum from "carrier-mediated, looks online to backend" → "fully peer-to-peer with cryptographic trust." Real NPCI architecture uses different combinations of these for different rural-connectivity scenarios.

---

## Killer features (the parts to flag in interviews)

### 1. Idempotency keys on every transfer
Every `TransferRequest` carries an `idempotencyKey`. Backend checks `transactions` table before processing — duplicate keys return the cached response, never re-debit. This is the same pattern Stripe uses; it's table-stakes for serious payments.

### 2. Optimistic locking via `@Version`
`Account.version` is incremented by JPA on every update. Concurrent updates to the same account fail with `OptimisticLockingFailureException`, which is mapped to HTTP 409. `transferWithRetry()` demonstrates the `@Retryable` retry-with-backoff pattern.

### 3. RSA-2048 signed offline tokens
Each user's device generates an RSA keypair on registration (in this demo, the "device" is simulated server-side; in production keys live in Android Keystore / iOS Secure Enclave). When Alice issues an offline payment to Bob:

- Alice's UPI Lite wallet is **debited immediately** (funds reserved)
- A canonical payload is constructed: `nonce | sender | receiver | amount | issuedAt | expiresAt`
- Alice signs it with her **private key**
- Bob receives the token via Bluetooth/NFC/QR — he can verify locally using Alice's **public key** (cached from prior interaction)
- When either party next comes online, they POST the token to `/api/offline-tokens/redeem`
- Backend re-verifies signature, checks the nonce hasn't been used (DB unique constraint), and credits Bob's wallet

**Double-spend defense:** funds reserved at issue time + unique nonce + DB unique constraint + signature verification = an attacker would need to forge an RSA signature *and* hit a nonce collision. Both are computationally infeasible.

### 4. Reconciliation engine
`OfflineTokenService.expireStaleTokens()` runs every 60 seconds via `@Scheduled`. Tokens that weren't redeemed before expiry are marked EXPIRED, and the sender's reserved balance is refunded. This is the "eventually consistent" piece — without it, expired tokens would leave funds locked indefinitely.

### 5. Channel-aware policy
`TxnChannel` enum (ONLINE / USSD_OFFLINE / UPI_LITE / P2P_OFFLINE) drives different validation rules:
- USSD has a per-txn cap (₹500, matching real UPI Lite)
- UPI Lite spend skips PIN (device unlock is authn — matches real UPI Lite UX)
- P2P offline debits the Lite wallet, not bank balance
- All channels share the same idempotency + audit logging

### 6. Layered security
- **BCrypt** for PIN + password (work factor 12)
- **RSA-2048 / SHA-256** for offline token signatures
- **JWT (HS384)** for API auth — stateless, includes `exp`, validated by filter
- **Constant-time comparison** for signature verification (RSA verify is intrinsically constant-time)
- **Stateless sessions** — `SessionCreationPolicy.STATELESS`, CSRF disabled (REST API)

### 7. Production hygiene
- Custom exception hierarchy → typed HTTP responses
- Structured JSON error bodies (`{ timestamp, errorCode, message }`)
- OpenAPI / Swagger UI auto-generated
- Spring Actuator health endpoint
- Indexed columns for hot-path queries (`vpa`, `idempotency_key`, `nonce`)
- Append-only transaction log with status (`PENDING/SUCCESS/FAILED/REVERSED`) — every attempt audit-trailed

---

## Run it

```bash
mvn spring-boot:run
```

Endpoints exposed:
- API docs: `http://localhost:8080/swagger-ui.html`
- H2 console: `http://localhost:8080/h2-console` (JDBC URL: `jdbc:h2:mem:upidb`)
- Health: `http://localhost:8080/actuator/health`

The seeder pre-creates two accounts:
| VPA | Mobile | UPI PIN | Password | Bank | Lite |
|---|---|---|---|---|---|
| `ramesh@upi` | 9876543210 | 1234 | password123 | ₹4000 | ₹1000 |
| `sita@upi` | 9123456780 | 5678 | password456 | ₹4000 | ₹1000 |

## Demo flows

### Flow 1 — Online UPI transfer

```bash
curl -X POST http://localhost:8080/api/transactions/transfer \
  -H "Content-Type: application/json" \
  -d '{
    "Authorization": "Bearer $TOKEN"
    "idempotencyKey": "demo-online-1",
    "senderVpa": "ramesh@upi",
    "receiverVpa": "sita@upi",
    "amount": 250,
    "upiPin": "1234",
    "remarks": "Lunch"
  }'
```
Run it twice with the same key — the second call returns `"idempotentReplay": true`.

### Flow 2 — Offline transfer via USSD

The carrier sends one keypress per request, all sharing a `sessionId`:

```bash
# Dial *99#
curl -X POST http://localhost:8080/api/ussd \
  -H "Content-Type: application/json" \
  -d '{"sessionId":"s1","mobileNumber":"9876543210","text":"","serviceCode":"*99#"}'

# Press 1 (Send Money)
curl -X POST http://localhost:8080/api/ussd \
  -H "Content-Type: application/json" \
  -d '{"sessionId":"s1","mobileNumber":"9876543210","text":"1"}'

# Enter VPA
curl -X POST http://localhost:8080/api/ussd \
  -H "Content-Type: application/json" \
  -d '{"sessionId":"s1","mobileNumber":"9876543210","text":"sita@upi"}'

# Amount
curl -X POST http://localhost:8080/api/ussd \
  -H "Content-Type: application/json" \
  -d '{"sessionId":"s1","mobileNumber":"9876543210","text":"100"}'

# Remarks (0 = skip)
curl -X POST http://localhost:8080/api/ussd \
  -H "Content-Type: application/json" \
  -d '{"sessionId":"s1","mobileNumber":"9876543210","text":"groceries"}'

# UPI PIN
curl -X POST http://localhost:8080/api/ussd \
  -H "Content-Type: application/json" \
  -d '{"sessionId":"s1","mobileNumber":"9876543210","text":"1234"}'
```

### Flow 3 — UPI Lite (no PIN spend)

First login to get a JWT:

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"vpa":"ramesh@upi","password":"password123"}' \
  | grep -o '"token":"[^"]*' | cut -d'"' -f4)

# Spend from UPI Lite — no PIN needed (device unlock is authn)
curl -X POST http://localhost:8080/api/transactions/lite/spend \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "idempotencyKey": "demo-lite-1",
    "receiverVpa": "sita@upi",
    "amount": 50,
    "remarks": "Tea"
  }'
```

### Flow 4 — P2P Offline Tokens (the cool one)

```bash
# Step 1: Ramesh issues a signed token for Sita (he's online)
TOKEN_PAYLOAD=$(curl -s -X POST http://localhost:8080/api/offline-tokens/issue \
  -H "Content-Type: application/json" \
  -d '{
    "Authorization": "Bearer $TOKEN"
    "senderVpa": "ramesh@upi",
    "receiverVpa": "sita@upi",
    "amount": 200,
    "upiPin": "1234"
  }')
echo "Token issued: $TOKEN_PAYLOAD"

# Step 2: Imagine the token traveled via Bluetooth/NFC to Sita
# Step 3: Sita comes online and redeems
curl -X POST http://localhost:8080/api/offline-tokens/redeem \
  -H "Content-Type: application/json" \
  -d "{\"token\": $TOKEN_PAYLOAD}"

# Step 4: Try redeeming again — gets ALREADY_SETTLED, double-spend prevented
curl -X POST http://localhost:8080/api/offline-tokens/redeem \
  -H "Content-Type: application/json" \
  -d "{\"token\": $TOKEN_PAYLOAD}"
```

Observe the logs — you'll see signature verification, nonce dedup, and settlement.

## Verified demo output

All flows below were run against the live application (`mvn spring-boot:run`) and the
responses are captured verbatim. All `mvn test` integration tests pass
(`Tests run: 6, Failures: 0, Errors: 0`).

> **Auth note:** every endpoint except `/api/auth/**`, `/api/ussd/**`,
> `/api/accounts/register`, and the docs/health paths requires a JWT. Login first
> and pass the bearer token on subsequent calls.

### 0. Login → JWT

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"vpa":"ramesh@upi","password":"password123"}' \
  | grep -o '"token":"[^"]*' | cut -d'"' -f4)
```
```json
{"token":"eyJhbGciOiJIUzM4NCJ9...","vpa":"ramesh@upi","expiresInSec":3600}
```

### 1. Online transfer — and idempotent replay

First call (₹250 moves, balance 4000 → 3750):
```bash
curl -s -X POST http://localhost:8080/api/transactions/transfer \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"idempotencyKey":"demo-1","senderVpa":"ramesh@upi","receiverVpa":"sita@upi","amount":250,"upiPin":"1234","remarks":"Lunch"}'
```
```json
{"utr":"F069BF85757847EE","status":"SUCCESS","message":"₹250 sent to sita@upi","newBalance":3750.00,"idempotentReplay":false}
```

Same request again (same key) — **same UTR returned, no second debit:**
```json
{"utr":"F069BF85757847EE","status":"SUCCESS","message":"Transfer completed","newBalance":null,"idempotentReplay":true}
```

### 2. Wrong PIN — rejected with structured error + audit log

```json
HTTP/1.1 401
{"timestamp":"2026-05-27T21:09:58Z","errorCode":"INVALID_PIN","message":"Invalid UPI PIN"}
```
The failed attempt is still written to the audit log (via `@Transactional(REQUIRES_NEW)`),
so it survives the parent-transaction rollback.

### 3. P2P offline token — issue → redeem → double-spend blocked

Issue (RSA-2048 signed; ₹200 reserved from sender's Lite wallet):
```json
{
  "nonce":"b8a2c4a6-8d45-47b1-949f-144f6af008d6",
  "senderVpa":"ramesh@upi","receiverVpa":"sita@upi","amount":200,
  "issuedAt":"2026-05-27T21:11:16Z","expiresAt":"2026-05-28T21:11:16Z",
  "signedPayload":"b8a2c4a6-...|ramesh@upi|sita@upi|200|2026-05-27T21:11:16Z|2026-05-28T21:11:16Z",
  "signature":"MP+pBHmkq68jyEnXQv/CNf11pyy/Emofk...=="
}
```

Redeem — signature verified, settled:
```json
{"status":"SETTLED","utr":"OFL5E56E4B4918F4","message":"Offline token settled. ₹200 credited to sita@upi"}
```

Redeem the **same** token again — nonce already used, **no second credit:**
```json
{"status":"ALREADY_SETTLED","utr":"OFL5E56E4B4918F4","message":"Token was already redeemed"}
```

### 4. Tampered token — signature rejects the forgery

Take a valid token, change `"amount":50` → `"amount":5000`, leave the signature intact
(an attacker can't re-sign without the sender's private key), and redeem:
```json
HTTP/1.1 422
{"timestamp":"2026-05-28T04:08:56Z","errorCode":"TOKEN_INVALID","message":"Token signature verification failed"}
```
The server reconstructs the canonical string from the (tampered) fields, the RSA signature
no longer matches, and the redeem is rejected. The amount is readable and editable — but any
edit is **detectable**. No funds move.

---

## Tests

```bash
mvn test
```

Covers:
- Idempotency replay returns cached UTR
- Offline txn over ₹500 limit rejected
- Invalid PIN throws `InvalidPinException`
- Token issue → verify → redeem end-to-end
- Double-redeem returns `ALREADY_SETTLED`
- Tampered token payload rejected on redeem (signature mismatch)

---

## Things you'd add for production

I deliberately stopped here to keep the demo focused. In production:

- **Replace H2 with PostgreSQL** + Flyway/Liquibase migrations
- **Move USSD sessions to Redis** (ephemeral, TTL-driven)
- **HSM-backed RSA signing** for the bank's keys (NPCI mandates this)
- **Real device key storage**: Android Keystore + iOS Secure Enclave; never on the server
- **NPCI PSP onboarding** — real UPI requires a PSP license + production NPCI integration
- **Real telecom USSD gateway** — Infobip, Africa's Talking, or NPCI's *99# infrastructure
- **Distributed scheduler** with leader election (ShedLock or Quartz cluster) for the reconciler
- **Outbox pattern** for SMS/notification delivery — never call SMS provider inside the txn
- **Rate limiting** at API gateway (per-VPA, per-IP)
- **Fraud detection pipeline** — async ingest of `transactions` table into an ML service
- **AML / KYC checks** — required by RBI
- **Disaster recovery** — multi-AZ, encrypted backups, RPO/RTO targets
- **Performance SLOs** — UPI mandates p99 < 1s end-to-end at NPCI
- **Switch to Ed25519** signatures instead of RSA-2048 for smaller payload (matters for NFC/Bluetooth size budgets)

---

## File map

```
src/main/java/com/example/upi/
├── OfflineUpiApplication.java
├── controller/
│   ├── AuthController.java          ← register, login (JWT)
│   ├── AccountController.java       ← /me, /{vpa}
│   ├── TransactionController.java   ← online + UPI Lite endpoints
│   ├── UssdController.java          ← *99# gateway endpoint
│   └── OfflineTokenController.java  ← issue/verify/redeem P2P
├── service/
│   ├── AccountService.java          ← registration, login, RSA keygen
│   ├── TransactionService.java      ← idempotency + locking + retry
│   ├── UssdService.java             ← state machine for USSD menu
│   └── OfflineTokenService.java     ← RSA sign/verify + reconciler
├── model/                           ← JPA entities (Account, Transaction, OfflineToken, UssdSession)
├── repository/
├── dto/Dtos.java                    ← all request/response shapes
├── security/                        ← JWT service, filter, config
├── exception/                       ← typed exception hierarchy
├── config/                          ← seeder, exception handler, retry
└── util/
    ├── Hashing.java                 ← BCrypt wrapper
    └── RsaCrypto.java               ← RSA-2048 sign/verify
```
