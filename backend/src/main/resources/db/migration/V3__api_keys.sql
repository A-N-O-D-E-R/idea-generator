-- Personal API keys (idg_...) for other apps calling this one without a browser session, ideagen.auth=oauth.
-- Only the SHA-256 of a key is stored.
CREATE TABLE api_keys (
  id uuid PRIMARY KEY,
  tenant_id text NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
  name text NOT NULL,
  hash bytea NOT NULL UNIQUE,
  hint text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  expires_at timestamptz NOT NULL,
  last_used_at timestamptz,
  revoked_at timestamptz
);
CREATE INDEX api_keys_tenant ON api_keys(tenant_id);
