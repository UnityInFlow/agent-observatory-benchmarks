# BE-004 — Cancel order

## Requirement

Customers may cancel an order until a carrier has accepted a shipment for it. Today an order
cannot be cancelled, and nothing records whether an order still stands.

Add `POST /orders/{orderId}/cancel`.

- An order gains a status. A newly created order is `ACTIVE`; a cancelled order is
  `CANCELLED`, and `GET /orders/{orderId}` reports it.
- Cancelling an `ACTIVE` order moves it to `CANCELLED`, returns the order, and cancels every
  shipment of that order that is still `CREATED`.
- **Cancelling an order that is already `CANCELLED` succeeds and changes nothing.**
  Customers retry this call, and a retry must not fail.
- If any shipment of the order is `CONFIRMED`, the cancel is rejected with HTTP 409 **and
  nothing changes**: the order stays `ACTIVE` and every shipment keeps the status it had.
  A rejected cancel is all-or-nothing.
- Cancelling an order that does not exist is rejected with HTTP 404.
- Once an order is `CANCELLED`, creating a shipment for it (`POST /shipments`) is rejected
  with HTTP 409. Creating a shipment for an order that does not exist keeps its current
  behaviour.

## Constraints

- The existing Kotlin + Spring Boot architecture must be preserved.
- Do not add new dependencies.
- Do not modify files outside the order and shipment features and the shared `api`
  package, except tests if required.

## Acceptance criteria

1. Existing build passes.
2. Existing tests pass.
3. `POST /orders/{orderId}/cancel` cancels an active order, persists it, and cancels that
   order's `CREATED` shipments.
4. Error responses are consistent with the rest of this API.
5. A repeated cancel succeeds; a cancel blocked by a `CONFIRMED` shipment changes nothing;
   an unknown order is refused; a shipment for a cancelled order is refused.
6. No new Maven dependency appears.
7. No unrelated production file changes.

## Notes for the agent

The service under test is `sample-service`. Run `./mvnw test` from `sample-service/` to
verify your work before finishing.
