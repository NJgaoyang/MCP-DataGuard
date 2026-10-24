ALTER TABLE data_sources
  MODIFY COLUMN password_env VARCHAR(100) NULL COMMENT '可选的目标密码环境变量名',
  ADD COLUMN encrypted_password TEXT NULL COMMENT 'AES-GCM 加密密码，密钥位于数据库外';
