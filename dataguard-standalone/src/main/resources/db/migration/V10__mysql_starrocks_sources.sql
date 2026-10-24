-- V2: MySQL remains the ONLY platform metadata database. Target sources: MySQL and StarRocks.
-- Existing data sources are MySQL; new source type is explicitly validated at the API boundary.
ALTER TABLE data_sources
  ADD COLUMN db_type VARCHAR(16) NOT NULL DEFAULT 'MYSQL'
    COMMENT '目标业务库类型：MYSQL / STARROCKS（平台元数据始终 MySQL）';
ALTER TABLE data_sources
  ADD CONSTRAINT chk_data_source_type CHECK (db_type IN ('MYSQL','STARROCKS'));
CREATE INDEX idx_data_sources_type ON data_sources(db_type,enabled);
