-- BCrypt hashes are local acceptance fixtures for the documented credentials in README.
INSERT INTO identity_user(user_id, username, password_hash, enabled) VALUES
  ('alice', 'alice', '$2y$10$Rr49fnOlh4KlyZeADQag1uHW.RN7EvNB8shl3C6T1HGmgY6YpAzzW', true),
  ('bob', 'bob', '$2y$10$uYiJCyom8J8cLwPQDNVMPec6PKfNfLc0HVE4Q0k0l2XEstnReZbNm', true),
  ('viewer', 'viewer', '$2y$10$otwb9rUrgOMRW88Tm.lM7eHAWx8M9Q8zTWAXYm0LZXdjC80Tjat7m', true);

INSERT INTO identity_tenant_membership(user_id, tenant_id) VALUES
  ('alice', 'tenant-a'), ('bob', 'tenant-b'), ('viewer', 'tenant-a');

INSERT INTO identity_permission_assignment(user_id, tenant_id, permission) VALUES
  ('alice', 'tenant-a', 'catalog:read'),
  ('alice', 'tenant-a', 'order:create'),
  ('alice', 'tenant-a', 'order:read'),
  ('bob', 'tenant-b', 'catalog:read'),
  ('bob', 'tenant-b', 'order:create'),
  ('bob', 'tenant-b', 'order:read'),
  ('viewer', 'tenant-a', 'catalog:read'),
  ('viewer', 'tenant-a', 'order:read');

INSERT INTO identity_service(service_id, secret_hash, enabled) VALUES
  ('gateway', '$2y$10$dNDsNExW2HTFVTssXXs57e9AemeL/EOh4unHkYUchurbSs9Qm6cs2', true),
  ('order-service', '$2y$10$VJISK05vKL0LWSfl8j8lLeEA87UsiZeU2sO.IaV9DG8t40rIS7c/.', true);

INSERT INTO identity_service_permission(service_id, audience, permission) VALUES
  ('order-service', 'catalog-service', 'catalog:read');
