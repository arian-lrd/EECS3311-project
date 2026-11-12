create table note_document (
    id uuid primary key,
    project_id uuid not null references projects(id),
    source varchar(180) not null,
    version integer not null check (version > 0),
    sha256 char(64) not null,
    imported_at timestamp with time zone not null,
    section_count integer not null check (section_count > 0),
    unique (project_id, source, version)
);

create table note_section (
    id uuid primary key,
    document_id uuid not null references note_document(id) on delete cascade,
    ordinal_no integer not null,
    heading varchar(180) not null,
    body varchar(4000) not null,
    unique (document_id, ordinal_no)
);

create table note_token (
    section_id uuid not null references note_section(id) on delete cascade,
    token varchar(48) not null,
    weight integer not null,
    primary key (section_id, token)
);

create index note_token_lookup on note_token(token);

create table review_run (
    id uuid primary key,
    project_id uuid not null references projects(id),
    endpoint_id uuid not null references evidence_endpoints(id),
    module_id varchar(50) not null,
    operation varchar(20) not null,
    status varchar(40) not null,
    observed_responses integer not null,
    action_count integer not null,
    generated_at timestamp with time zone not null
);

create index review_run_project on review_run(project_id, generated_at);

create table review_run_action (
    run_id uuid not null references review_run(id) on delete cascade,
    ordinal_no integer not null,
    action_code varchar(50) not null,
    primary key (run_id, ordinal_no)
);

create table review_run_evidence (
    run_id uuid not null,
    action_position integer not null,
    evidence_id uuid not null,
    primary key (run_id, action_position, evidence_id),
    foreign key (run_id, action_position)
        references review_run_action(run_id, ordinal_no) on delete cascade
);

create table review_run_citation (
    run_id uuid not null references review_run(id) on delete cascade,
    section_id uuid not null references note_section(id),
    primary key (run_id, section_id)
);

create table review_coverage (
    project_id uuid not null references projects(id),
    endpoint_id uuid not null references evidence_endpoints(id),
    module_id varchar(50) not null,
    state varchar(20) not null,
    note varchar(400) not null,
    updated_at timestamp with time zone not null,
    primary key (project_id, endpoint_id, module_id)
);

create table review_coverage_history (
    id uuid primary key,
    project_id uuid not null references projects(id),
    endpoint_id uuid not null references evidence_endpoints(id),
    module_id varchar(50) not null,
    state varchar(20) not null,
    note varchar(400) not null,
    updated_at timestamp with time zone not null
);
