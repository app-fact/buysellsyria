# Architecture

## Overview

```
 Android app (offline-first)                          Odoo 19 + sales_rep_mobile
 ┌──────────────────────────────┐                    ┌──────────────────────────────┐
 │ Compose UI (Arabic, RTL)     │                    │ /sales_rep/api/v1/login      │
 │ Repository                   │  HTTPS + Bearer    │ /sales_rep/api/v1/pull       │
 │  ├─ Room DB (products,       │ ─────────────────► │ /sales_rep/api/v1/push       │
 │  │  customers, prices, stock,│                    │   └─ sales.rep.mobile.api    │
 │  │  docs, visits, OUTBOX)    │ ◄───────────────── │ sales.rep, sales.rep.load,   │
 │  └─ SyncWorker (WorkManager) │                    │ sales.rep.van.sale, .visit,  │
 │ VisitTrackingService (GPS)   │                    │ .collection, .sync.log,      │
 │ BluetoothPrinter (ESC/POS)   │                    │ sale.order, stock, account   │
 │ core/ (pure Kotlin, tested)  │                    └──────────────────────────────┘
 └──────────────────────────────┘
```

## Accounting & stock model

| Event | Stock | Accounting |
|---|---|---|
| Load van (validated in Odoo) | internal transfer Main WH → Van location | customer invoice to the **rep partner** (load pricelist) |
| Unload van | internal transfer Van → Main WH | credit note to the rep partner |
| Van sale to customer | picking Van → Customers | none in company books (rep already bought the goods); `sales.rep.van.sale` document |
| Customer return to van | picking Customers → Van | none; reduces customer's due to the rep |
| Collection from customer | — | `sales.rep.collection` (customer's debt to the rep) |
| Rep hands cash to company | — | normal payment on the rep partner |
| Order from main warehouse | normal `sale.order` delivery | normal customer invoice from the company |

The rep is a `res.partner` (flag `is_sales_rep`) linked to a `sales.rep` record. Reps are **not**
`res.users`, so they use no Odoo licence. The API runs as the configured integration user
(Settings) or the superuser.

## Sync protocol

All bodies are JSON. After login every call sends `Authorization: Bearer <token>`.

### `POST /sales_rep/api/v1/login`
`{"code": "R01", "pin": "1234", "device_id": "<uuid>"}` → `{token, rep, company, currency, config}`.
The token is stored hashed (SHA-256), the PIN with PBKDF2. A rep is bound to one device until a
manager presses *Reset Phone*.

### `POST /sales_rep/api/v1/pull`
`{"since": "<server_time of the previous pull>"}` (omit for a full pull). Returns:

- `products` changed since `since`, plus `product_ids` (all active ids, for pruning)
- `customers` assigned to the rep changed since `since`, plus `customer_ids`, `customer_dues`
- `prices`: `{pricelist_id: {product_id: price}}` (full, each pull)
- `van_stock`, `main_stock`: `{product_id: qty}` (full, each pull)
- `loads`: last 30 loadings with state
- `config`: geofence radius / exit buffer / GPS accuracy / manual visits

The server compares with a 2-minute overlap so records committed during the previous pull are not missed.

### `POST /sales_rep/api/v1/push`
`{"ops": [{"uuid", "type", "payload", "created_at"}]}` → `{"results": [{"uuid", "status": "ok"|"error", "res_id", "message"}]}`

- **Idempotent**: each successful op is stored in `sales.rep.sync.log` by uuid. Re-sent ops return
  the stored result (`duplicate: true`), never a second document.
- **Isolated**: each op runs in its own savepoint; one bad op does not block the others.
- **Ordered**: the phone sends ops in creation order, so a customer created offline exists before
  the sale that references it. References use `customer_id` (server id) or `customer_uuid`.

| type | payload (main keys) | creates |
|---|---|---|
| `customer_upsert` | `customer_uuid, name, phone, street, city, latitude, longitude` | `res.partner` |
| `customer_location` | `customer_id/uuid, latitude, longitude` | updates partner |
| `visit_open` | `visit_uuid, customer, check_in, mode, latitude, longitude, distance` | `sales.rep.visit` |
| `visit_close` | `visit_uuid, check_out, mode, no_sale_reason, ...` | closes visit |
| `van_sale` | `number, seq, sale_type, customer, visit_uuid, lines[], discount_amount, paid` | `sales.rep.van.sale` + picking |
| `sale_order` | `customer, visit_uuid, lines[{product_id, qty}], note` | `sale.order` (server pricelist) |
| `collection` | `number, customer, amount, note` | `sales.rep.collection` |
| `load_request` | `type: load/unload, lines[], note` | `sales.rep.load` (state requested) |

### Phone side

- Every user action writes the local document **and** an outbox row in one Room transaction.
- `SyncWorker` runs every 15 min with network, and right after each new document.
- Sync = push outbox (delete ok rows, mark errors) → pull → apply. After applying Odoo's
  van stock and customer balances, the effect of still-unsent ops is re-applied, so what the rep
  sees is always "Odoo + my pending work".
- Van-sale numbers (`R01-000123`) are generated offline; Odoo keeps the highest received and
  returns it at login, so a reinstalled app never reuses a number.

## GPS visits (`core/geo/GeofenceEngine.kt`)

Pure state machine, unit tested:

- ignore fixes with accuracy worse than `gps_max_accuracy` (40 m)
- **open** when the nearest located customer is within `radius` (50 m) for 2 consecutive fixes
- **close** when farther than `radius + exit_buffer` (60 m) for 2 consecutive fixes
- **switch** to a neighbour shop if he is ≥ 25 m closer and within radius/2 for 2 fixes
- manual visits on customers without coordinates are only closed manually

`VisitTrackingService` is a `location` foreground service using the platform `LocationManager`
(GPS every 10 s, network as fallback), so it works on phones without Google Play Services.

## Printing

`ReceiptRenderer` lays out the receipt with Android `StaticLayout` (correct Arabic shaping and
RTL), producing a bitmap 384 dots (58 mm) or 576 dots (80 mm) wide. `EscPos.raster` converts it
to `GS v 0` raster bands; `BluetoothPrinter` sends it over SPP in small chunks.
