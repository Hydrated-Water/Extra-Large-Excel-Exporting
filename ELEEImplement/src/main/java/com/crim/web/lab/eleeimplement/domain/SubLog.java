package com.crim.web.lab.eleeimplement.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableField;
import lombok.Data;

import java.time.LocalDateTime;


@Data
@TableName("sub_log")
public class SubLog {
    
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    
    @TableField("log_id")
    private Long logId;
    
    @TableField("scope_no")
    private String scopeNo;
    
    @TableField("device_no")
    private String deviceNo;
    
    private LocalDateTime time;
    
    private String comment;
}