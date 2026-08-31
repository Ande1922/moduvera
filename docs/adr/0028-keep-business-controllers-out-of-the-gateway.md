---
status: accepted
---

# Keep business Controllers out of the Gateway

HTTP Controllers belong exclusively to the providing Business Service's Interfaces layer. The Edge Gateway performs routing, browser-session to internal-identity conversion, Header propagation and external access control through routes and filters; it defines no Service API and must not duplicate Order, Identity or other business Controller mappings. The existing handwritten `GatewayController` is an architecture deviation to remove.

The same Business Service Controller is reused across supported application topologies. Topology changes may alter whether edge routing runs in a separate App or inside a combined App, but they do not transfer endpoint ownership to the Gateway or create a second public HTTP implementation. Service APIs remain protocol-neutral internal Local/Remote collaboration contracts rather than Gateway contracts. ADR 0029 assigns the external service-name route prefix to assembly configuration rather than introducing Gateway-owned endpoints.

Evidence: explicitly required by the user during the 2026-08-31 App Assembly review.
