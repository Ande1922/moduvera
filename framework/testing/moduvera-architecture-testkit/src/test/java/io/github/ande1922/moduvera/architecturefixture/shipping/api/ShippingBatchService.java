package io.github.ande1922.moduvera.architecturefixture.shipping.api;

import java.util.List;

public interface ShippingBatchService {

    void arrangeAll(List<ShipOrderCommand> commands);
}
