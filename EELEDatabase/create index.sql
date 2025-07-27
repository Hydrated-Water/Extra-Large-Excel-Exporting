-- 为scope表的字段建立索引
CREATE INDEX idx_scope_no ON scope(no);
CREATE INDEX idx_scope_name ON scope(name);

-- 为device_type表的字段建立索引
CREATE INDEX idx_device_type_name ON device_type(name);

-- 为staff表的字段建立索引
CREATE INDEX idx_staff_no ON staff(no);
CREATE INDEX idx_staff_name ON staff(name);

-- 为device表的字段建立索引
CREATE INDEX idx_device_no ON device(no);
CREATE INDEX idx_device_name ON device(name);
CREATE INDEX idx_device_type_id ON device(type_id);

-- 为log表的字段建立索引
CREATE INDEX idx_log_staff_no ON log(staff_no);
CREATE INDEX idx_log_time ON log(time);

-- 为sub_log表的字段建立索引
CREATE INDEX idx_sub_log_log_id ON sub_log(log_id);
CREATE INDEX idx_sub_log_scope_no ON sub_log(scope_no);
CREATE INDEX idx_sub_log_device_no ON sub_log(device_no);
CREATE INDEX idx_sub_log_time ON sub_log(time);

-- 解决索引选择性问题
drop index idx_staff_no on staff;