# Message envelope compatibility fixtures

These fixed version-1 samples exercise the ticket-07 consumer-first boundary.
The `without-creation` files represent messages emitted before the optional
creation extension. The `with-creation` files keep the same Message Type major
and business envelope while adding `traceparent` and `tracestate`. The current
carrier and schemas bound each propagation string to 512 characters; semantic
W3C parsing remains the Kafka adapter's responsibility.

`async-command-envelope-before-creation.schema.json` is copied byte-for-byte
from repository base `37029676c19a74567a54d26ee31ba80416151898`. Its SHA-256 is
`ce319d7436c3907e525f0cbc7f6b008e5e66c5f6367c00bce3c6f6656970d39a`.
It documents and verifies that the former strict command validator rejects the
new optional fields; rollout therefore upgrades readers and validators before
later tickets enable writers.
