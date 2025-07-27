package com.crim.web.lab.eleeimplement.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.crim.web.lab.eleeimplement.domain.ExcelBO;
import com.crim.web.lab.eleeimplement.domain.Log;
import com.crim.web.lab.eleeimplement.domain.SubLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.cursor.Cursor;

import java.util.List;


@Mapper
public interface LogMapper extends BaseMapper<Log> {
    
    int count();
    
    List<ExcelBO> excelOrigin();
    
    List<ExcelBO> excelA1();
    
    @Options(fetchSize = Integer.MIN_VALUE)
    Cursor<ExcelBO> excelC1();
    
    List<ExcelBO> excelC1_1(@Param("startId") Long startId, @Param("endId") Long endId);
    
    @Options(fetchSize = Integer.MIN_VALUE)
    Cursor<ExcelBO> excelC1_2(@Param("startId") Long startId, @Param("endId") Long endId);
}
