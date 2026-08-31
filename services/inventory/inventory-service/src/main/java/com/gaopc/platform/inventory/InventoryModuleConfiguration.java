package com.gaopc.platform.inventory;

import com.gaopc.platform.inventory.configuration.InventoryApplicationConfiguration;
import com.gaopc.platform.inventory.configuration.InventoryPersistenceConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Compatibility composition for consumers that previously selected the complete Inventory module.
 * New application assemblies should import the individual configuration slices explicitly.
 */
@Deprecated(forRemoval = true)
@Configuration(proxyBeanMethods = false)
@Import({InventoryApplicationConfiguration.class, InventoryPersistenceConfiguration.class})
public class InventoryModuleConfiguration {}
