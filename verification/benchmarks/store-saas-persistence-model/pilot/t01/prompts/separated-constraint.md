## Assigned persistence constraint

The mapped persistence object for Store must be a second flat typed
`StoreRow` owned by Infrastructure. Implement an explicit aggregate-specific
`StoreObjectMapper` between `StoreRow` and the framework-free Domain Store.
MyBatis XML maps database columns only to `StoreRow`. Do not map SQL results
directly into the Domain implementation.
