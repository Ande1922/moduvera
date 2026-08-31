---
status: accepted
---

# Bound durable publication at broker acceptance

Durable Publication atomically persists the publication intent with local business state and keeps an automatically or manually redriveable record until the Broker confirms receipt. Its guarantee ends at Broker acceptance: consumer contract handling, business-state convergence, compensation, and reversal remain responsibilities of the consuming business capability rather than promises of the publication mechanism.

The database remains the only source of pending messages. A local after-commit signal may wake one mutually exclusive Relay worker for low latency, while periodic polling recovers missed signals, process crashes, and work left by other instances; the signal carries no message data and never creates a second delivery path. Multiple application instances run Relays concurrently and divide work through database claim tokens and expiring leases rather than a process-wide leader or distributed mutex.
