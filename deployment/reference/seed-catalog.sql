INSERT INTO catalog_product
    (tenant_id, product_id, name, unit_price, currency, version,
     created_at, created_by, updated_at, updated_by)
VALUES
  ('tenant-a', 100, 'Mechanical Keyboard', 399.00, 'CNY', 0, CURRENT_TIMESTAMP, 'reference', CURRENT_TIMESTAMP, 'reference'),
  ('tenant-a', 200, 'Studio Display', 8999.00, 'CNY', 0, CURRENT_TIMESTAMP, 'reference', CURRENT_TIMESTAMP, 'reference'),
  ('tenant-a', 300, 'USB-C Dock', 699.00, 'CNY', 0, CURRENT_TIMESTAMP, 'reference', CURRENT_TIMESTAMP, 'reference'),
  ('tenant-b', 100, 'Tenant B Keyboard', 299.00, 'CNY', 0, CURRENT_TIMESTAMP, 'reference', CURRENT_TIMESTAMP, 'reference');
