-- Pin the exact authorized model tool projection; reserved provider declarations are not capabilities.
ALTER TABLE ga_executions ADD COLUMN allowed_tools TEXT NOT NULL DEFAULT '[]'
    CHECK (octet_length(allowed_tools) <= 65536);
