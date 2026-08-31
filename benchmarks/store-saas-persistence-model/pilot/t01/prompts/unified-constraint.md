## Assigned persistence constraint

The qualified Domain Store implementation must itself be the object mapped by
MyBatis XML. Do not create a second Store Row/DO/record/entity, a generic map or
JSON row, or a Domain-to-row object mapper. Keep MyBatis annotations/imports out
of Domain; result maps, SQL and conversion metadata remain in Infrastructure.
