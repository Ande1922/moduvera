INSERT INTO inventory_stock
    (tenant_id, product_id, available, version, created_at, created_by, updated_at, updated_by)
VALUES
  ('tenant-a', 100, 20, 0, CURRENT_TIMESTAMP, 'reference', CURRENT_TIMESTAMP, 'reference'),
  ('tenant-a', 200, 1, 0, CURRENT_TIMESTAMP, 'reference', CURRENT_TIMESTAMP, 'reference'),
  ('tenant-a', 300, 5, 0, CURRENT_TIMESTAMP, 'reference', CURRENT_TIMESTAMP, 'reference'),
  ('tenant-b', 100, 10, 0, CURRENT_TIMESTAMP, 'reference', CURRENT_TIMESTAMP, 'reference');
