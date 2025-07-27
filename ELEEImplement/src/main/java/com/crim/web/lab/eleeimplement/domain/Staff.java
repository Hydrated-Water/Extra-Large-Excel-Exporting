package com.crim.web.lab.eleeimplement.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import lombok.Data;


@Data
@TableName("staff")
public class Staff {
    
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    
    private String no;
    
    private String name;
}
