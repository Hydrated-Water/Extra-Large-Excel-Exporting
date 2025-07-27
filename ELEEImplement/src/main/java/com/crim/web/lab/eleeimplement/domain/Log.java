package com.crim.web.lab.eleeimplement.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableField;
import lombok.Data;

import java.time.LocalDateTime;


@Data
@TableName("log")
public class Log {
    
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    
    @TableField("staff_no")
    private String staffNo;
    
    private LocalDateTime time;
}
