# BE-005 — Partial fulfilment, customer check, paged lists

Three changes to `sample-service`, shipped together on one branch. They touch the order,
shipment and customer features and the shared `api` package.

## 1. Partial fulfilment

An order is for a quantity of goods, and that quantity is shipped in parts. Today an order
has no quantity, a shipment carries no quantity, and nothing records how much of an order
its shipments account for.

- An order gains a `quantity`: a positive integer, `1` when the request does not give one.
  A quantity below `1` is rejected with HTTP 400 naming the field. `GET /orders/{orderId}`
  reports it.
- A shipment gains a `quantity` with the same rule and the same default.
- An order's shipments **allocate** its quantity. `POST /shipments` is rejected with HTTP 409,
  and nothing changes, when the shipment would push the order's allocated quantity above the
  order's quantity. The allocated quantity is the sum over the order's shipments that are not
  `CANCELLED`. Creating a shipment for an order that does not exist keeps its current
  behaviour.
- Shipments gain transitions: `POST /shipments/{shipmentId}/confirm` moves `CREATED` to
  `CONFIRMED`; `POST /shipments/{shipmentId}/deliver` moves `CONFIRMED` to `DELIVERED`, a new
  status; `POST /shipments/{shipmentId}/cancel` moves `CREATED` to `CANCELLED`. Each returns
  the shipment. Any other transition is rejected with HTTP 409 and changes nothing; an
  unknown shipment is rejected with HTTP 404.
- An order gains a fulfilment status, reported by `GET /orders/{orderId}` and by the list as
  `fulfilment: { allocated, delivered, status }`. A newly created order is `UNALLOCATED`.
  `delivered` is the sum over the order's `DELIVERED` shipments. The status is `DELIVERED`
  when `delivered` reaches the order's quantity, `FULLY_ALLOCATED` when `allocated` reaches
  it, `PARTIALLY_ALLOCATED` when `allocated` is above zero and below it, and `UNALLOCATED`
  otherwise.
- `GET /orders?fulfilment=STATUS` lists only the orders in that status. Without the parameter
  the list is unchanged. A value that is not a fulfilment status is rejected with HTTP 400
  naming the parameter.
- **Cancelling a shipment releases its quantity.** After the cancel, the order's fulfilment no
  longer counts that shipment, its status moves back accordingly, and a new shipment for the
  released quantity is accepted.

## 2. An order needs its customer

Today `POST /orders` accepts any `customerId`. From now on an order for a customer that does
not exist is rejected with HTTP 422 through the error envelope. `GET /customers/{customerId}`
for an unknown customer reports HTTP 404 **through the error envelope**, like orders and
shipments already do.

The existing `OrderControllerTest` creates orders for a customer that was never created.
Update it so that the suite reflects the new rule.

## 3. Paged lists

`GET /customers`, `GET /orders` and `GET /shipments` accept `limit` (1 to 100, default 100)
and `offset` (0 or more, default 0), and slice the list in id order. The response body stays
the array it is today, so callers that do not page keep working, and every list response
carries the total number of items before slicing in an `X-Total-Count` header. A `limit`
outside its range or a negative `offset` is rejected with HTTP 400 naming the parameter. On
`GET /orders` the total is the total after the `fulfilment` filter.

## Constraints

- The existing Kotlin + Spring Boot architecture must be preserved.
- Do not add new dependencies.
- Do not modify files outside the order, shipment and customer features and the shared `api`
  package, except tests.

## Acceptance criteria

1. Existing build passes.
2. Existing tests pass, other than `OrderControllerTest`, which this ticket changes.
3. Quantities, allocation, the three transitions, the fulfilment read model and the filter
   behave as specified, including the release of a cancelled shipment's quantity.
4. Error responses are consistent with the rest of this API.
5. An order for an unknown customer is refused; every list pages as specified and unpaged
   calls are unchanged.
6. No new Maven dependency appears.
7. No unrelated production file changes.

## Notes for the agent

The service under test is `sample-service`. Run `./mvnw test` from `sample-service/` to
verify your work before finishing.
