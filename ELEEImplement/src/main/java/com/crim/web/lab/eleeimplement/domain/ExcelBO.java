package com.crim.web.lab.eleeimplement.domain;

import com.alibaba.excel.annotation.ExcelProperty;
import lombok.Data;

import java.time.LocalDateTime;


@Data
public class ExcelBO {
    
    @ExcelProperty("内镜序号")
    private String scopeNo;
    
    @ExcelProperty("内镜名")
    private String scopeName;
    
    @ExcelProperty("员工工号")
    private String staffNo;
    
    @ExcelProperty("员工姓名")
    private String staffName;
    
    @ExcelProperty("时间")
    private LocalDateTime logTime;
    
    @ExcelProperty("设备序号")
    private String deviceNo;
    
    @ExcelProperty("设备名")
    private String deviceName;
    
    @ExcelProperty("设备类型")
    private String deviceTypeName;
    
    @ExcelProperty("备注")
    private String comment;
}
