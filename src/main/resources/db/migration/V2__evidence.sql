CREATE TABLE evidence_imports (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES projects(id),
    profile VARCHAR(40) NOT NULL,
    fingerprint CHAR(64) NOT NULL,
    imported_at TIMESTAMP WITH TIME ZONE NOT NULL,
    accepted_count INT NOT NULL CHECK (accepted_count >= 0),
    out_of_scope_count INT NOT NULL CHECK (out_of_scope_count >= 0),
    invalid_count INT NOT NULL CHECK (invalid_count >= 0),
    UNIQUE (project_id, profile, fingerprint)
);

CREATE TABLE evidence_endpoints (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES projects(id),
    host VARCHAR(253) NOT NULL,
    method VARCHAR(8) NOT NULL,
    normalized_path VARCHAR(256) NOT NULL,
    UNIQUE (project_id, host, method, normalized_path)
);

CREATE TABLE evidence_observations (
    id UUID PRIMARY KEY,
    import_id UUID NOT NULL REFERENCES evidence_imports(id),
    endpoint_id UUID NOT NULL REFERENCES evidence_endpoints(id),
    source_index INT NOT NULL CHECK (source_index > 0),
    status_code INT NOT NULL CHECK (status_code BETWEEN 100 AND 599),
    content_type VARCHAR(64) NOT NULL,
    has_csp BOOLEAN NOT NULL,
    has_content_type_options BOOLEAN NOT NULL,
    has_frame_options BOOLEAN NOT NULL,
    UNIQUE (import_id, source_index)
);

CREATE INDEX evidence_observations_endpoint ON evidence_observations(endpoint_id);
CREATE INDEX evidence_imports_history ON evidence_imports(project_id, imported_at);
