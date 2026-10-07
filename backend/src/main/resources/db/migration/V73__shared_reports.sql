CREATE TABLE shared_reports (
    token VARCHAR(36) PRIMARY KEY,
    report_id BIGINT NOT NULL,
    owner_id BIGINT NOT NULL,
    CONSTRAINT fk_shared_report FOREIGN KEY (report_id) REFERENCES scout_reports(id) ON DELETE CASCADE,
    CONSTRAINT fk_shared_report_owner FOREIGN KEY (owner_id) REFERENCES players(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
