-- 为 sub_log 表创建覆盖索引
CREATE INDEX idx_sub_log_covering ON sub_log (scope_no, device_no, log_id, time);

-- 为 log 表创建覆盖索引
CREATE INDEX idx_log_covering ON log (id, staff_no);

-- 为 staff 表创建覆盖索引
CREATE INDEX idx_staff_covering ON staff (no, name);

-- 为 scope 表创建覆盖索引
CREATE INDEX idx_scope_covering ON scope (no, name);

-- 为 device 表创建覆盖索引
CREATE INDEX idx_device_covering ON device (no, type_id, name);

-- 为 device_type 表创建覆盖索引
CREATE INDEX idx_device_type_covering ON device_type (id, name);


