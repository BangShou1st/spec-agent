ALTER TABLE search_settings ADD COLUMN org_id UUID NOT NULL
    DEFAULT '00000000-0000-4000-8000-000000000001' REFERENCES organizations(id);
ALTER TABLE search_settings DROP CONSTRAINT search_settings_pkey;
ALTER TABLE search_settings ADD PRIMARY KEY (org_id, singleton_id);
