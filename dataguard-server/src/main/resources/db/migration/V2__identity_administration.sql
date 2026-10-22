-- Explicit administration rights for trusted organization bootstrap administrators.
-- Never authorizes SQL execution and never activates external database credentials.
UPDATE dg_role
SET permissions = permissions || '["identity:admin","agent:identity:write","agent:permission:write","datasource:secret:write","datasource:test"]'::jsonb,
    updated_at=now(), version=version+1
WHERE code='org-admin' AND system_role=true;
