ALTER TABLE constructions ADD COLUMN prerequisite_version VARCHAR(80) NULL;
ALTER TABLE constructions ADD COLUMN prerequisite_requirements TEXT NULL;
ALTER TABLE tech_research_queue ADD COLUMN prerequisite_version VARCHAR(80) NULL;
ALTER TABLE tech_research_queue ADD COLUMN prerequisite_requirements TEXT NULL;
