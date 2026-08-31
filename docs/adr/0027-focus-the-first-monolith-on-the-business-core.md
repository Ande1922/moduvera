---
status: accepted
---

# Focus the first modular monolith on the business core

The first supported modular-monolith topology composes Catalog, Order and Inventory into `app-monolith`, while Gateway and Identity remain separate App Assemblies at the external trust boundary. Focused acceptance continues through the same public Gateway and Identity flow: Gateway calls the monolith over HTTP, Order calls Catalog locally through `CatalogApi`, and Order and Inventory continue to communicate through Kafka.

This bounds the first topology proof without claiming that Gateway and Identity can never join a later full-backend composition. Such a composition must preserve one owner for each public HTTP contract and explicitly replace remote Service API adapters with Local implementations; it must not activate duplicate Gateway and provider routes or bypass trusted-context establishment.

Evidence: explicitly accepted by the user during the 2026-08-31 App Assembly review, with full-backend composition retained as a possible later topology.
