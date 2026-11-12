CREATE TABLE projects (
    id UUID PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    restrictions VARCHAR(2000) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE project_scope (
    project_id UUID NOT NULL REFERENCES projects(id),
    excluded BOOLEAN NOT NULL,
    host_rule VARCHAR(255) NOT NULL,
    PRIMARY KEY (project_id, excluded, host_rule)
);
