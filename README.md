# Overview





## 前言



我曾于24年末到25年初在校期间参与了某医疗清洗消毒设备日志系统的功能升级维护，其中一项功能令我记忆深刻——即本文的主题，超大Excel表的导出。当时我接手该项目时，由于前后团队交接中间出现了近半年的空档期，积累了不少问题，其中最严重的就是日志Excel导出接口处于几乎不可用的状态。而前端那边的同学一时半会还不熟悉业务，该问题也在分布在全国各地的多家医院中先后出现，负责的导师也希望以最小的部署和上线的成本解决该问题，因此当时团队里仅剩的唯一的后端——我，承当了不小的压力。

由于前端那边指望不上，传统的浏览器端的解决办法如异步和进度条无法实现，更致命地是，前端（基于Vue）被全局配置了4秒的请求超时时间。在当时这似乎是一个不可能完成的任务，因为在测试环境中，当相关的日志表行数超过50万行时，仅SQL的执行时间就已经远超这个数，更何况经估算当时已经有不少医院的该系统的日志表行数可能远超50万行，这意味着优化首字节延迟是一个巨大的挑战。

我不仅需要优化SQL，更需要放弃Apache POI以及一切基于Apache POI的流式Excel写入库（点名EasyExcel）。Apache POI的流式Excel生成需要先将部分数据写入到临时文件，这意味着当日志超过50万行时，第一行数据发送到输出流已经距离请求开始超过十几秒的时间，这显然是不可接受的。

基于内存而不是基于临时文件的流式写入XLSX数据是可行的，这是因为XLSX格式本质上是基于ZIP格式的，而ZIP是支持流式写入的，因此必然存在一种与Apache POI不同的流式写入XLSX的实现方式，而我最终选择的是`org.dhatim:FastExcel`，它虽然功能很少，但它通过硬编码写入XML字符数据而不是构建并维护复杂的XML对象树来实现流式写入的同时具有更高的性能。

为了能够尽快地让医护人员看到浏览器上的下载进度条，我在当时花费了不少的时间，逐一测试并最终选择了一套完整的方案，即通过**覆盖索引**优化SQL执行时间、**`org.dhatim:FastExcel`**纯内存流式Excel写入库优化首字节延迟和响应时间、以及**游标分页（键集分页）**和**阻塞队列**的方式优化响应时间和内存占用。



本项目是在经过半年后对当时的方案的选择、测试的模拟复现及细节补充，以留下经验及探讨更好的技术选择。模拟复现**没有**完全重现当时的场景，可能与实际情况有所出入。文章结构略显混乱，如有大佬路过，欢迎指出问题。





## 正文



### 目标



#### 接口参数

- 内镜名称
- 员工名称
- 开始时间
- 结束时间



#### 目标Excel表字段

- 内镜序号
- 内镜名
- 员工工号
- 员工姓名
- 日志时间
- 消毒设备序号
- 消毒设备名称
- 消毒设备类型名称
- 备注



#### 性能指标

- 接口响应时间
- 首字节延迟
- CPU占用
- 内存占用
- 磁盘I/O





### 数据库

- 内镜表 （50行）scope

  | 字段 | 描述     | 备注 |
  | ---- | -------- | ---- |
  | id   | ID       |      |
  | no   | 内镜序号 |      |
  | name | 内镜名称 |      |

- 消毒设备表 （50行）device

  | 字段    | 描述       | 备注     |
  | ------- | ---------- | -------- |
  | id      | ID         |          |
  | no      | 设备序号   |          |
  | name    | 设备名称   |          |
  | type_id | 设备类型ID | 连接字段 |

- 消毒设备类型表 （10行）device_type

  | 字段 | 描述     | 备注 |
  | ---- | -------- | ---- |
  | id   | ID       |      |
  | name | 类型名称 |      |

- 员工表 （100行）staff

  | 字段 | 描述 | 备注 |
  | ---- | ---- | ---- |
  | id   | ID   |      |
  | no   | 工号 |      |
  | name | 姓名 |      |

- 日志表 （12.5万行）log

  | 字段     | 描述     | 备注     |
  | -------- | -------- | -------- |
  | id       | ID       |          |
  | staff_no | 员工工号 | 连接字段 |
  | time     | 时间     |          |

- 子日志表 （50万行）sub_log

  | 字段      | 描述     | 备注     |
  | --------- | -------- | -------- |
  | id        | ID       |          |
  | log_id    | 日志ID   | 连接字段 |
  | scope_no  | 内镜序号 | 连接字段 |
  | device_no | 设备序号 | 连接字段 |
  | time      | 时间     |          |
  | comment   | 备注     |          |

```mysql
-- 创建内镜表 (scope)
CREATE TABLE scope (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'ID',
  no VARCHAR(50) NOT NULL COMMENT '内镜序号',
  name VARCHAR(100) NOT NULL COMMENT '内镜名称',
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内镜表';

-- 创建消毒设备类型表 (device_type)
CREATE TABLE device_type (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'ID',
  name VARCHAR(100) NOT NULL COMMENT '类型名称',
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='消毒设备类型表';

-- 创建员工表 (staff)
CREATE TABLE staff (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'ID',
  no VARCHAR(50) NOT NULL COMMENT '工号',
  name VARCHAR(100) NOT NULL COMMENT '姓名',
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='员工表';

-- 创建消毒设备表 (device)
CREATE TABLE device (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'ID',
  no VARCHAR(50) NOT NULL COMMENT '设备序号',
  name VARCHAR(100) NOT NULL COMMENT '设备名称',
  type_id BIGINT NOT NULL COMMENT '设备类型ID',
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='消毒设备表';

-- 创建日志表 (log)
CREATE TABLE log (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'ID',
  staff_no VARCHAR(50) NOT NULL COMMENT '员工工号',
  time DATETIME NOT NULL COMMENT '时间',
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='日志表';

-- 创建子日志表 (sub_log)
CREATE TABLE sub_log (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'ID',
  log_id BIGINT NOT NULL COMMENT '日志ID',
  scope_no VARCHAR(50) NOT NULL COMMENT '内镜序号',
  device_no VARCHAR(50) NOT NULL COMMENT '设备序号',
  time DATETIME NOT NULL COMMENT '时间',
  comment TEXT COMMENT '备注',
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='子日志表';
```





### 模拟测试环境



#### 运行环境

硬件

- CPU `AMD Ryzen 5 5500 3.6 - 4.2 GHz`
- RAM `DDR4 32G 3200MHz`
- Disk `SSD ZHITAI TiPlus5000 512G`

软件

- Windows 10 22H2
- Java 1.8
- Spring Boot 2.3.7
- MyBatis-plus 3.4.3
- MySQL 8.0



#### 测试工具

- MySQL EXPLAIN ANALYZE

  分析SQL语句各部分执行时间和使用算法

- MySQL Performance Schema

  分析SQL语句执行所需RAM和磁盘I/O

- IDEA

- Postman

  测试接口首字符延迟、传输时间、响应时间

- Chrome

  测试接口可用性





### 原始实现



#### 核心源码

```xml
<select id="excel2" parameterType="Map" resultType="excelDo">
        select
        ms.scope_num,
        ms.scope_name,
        mst.staff_job_num,
        mst.staff_name,
        ls.log_time,
        md.machine_num,
        td.type_name,
        ls.comment
        from m_scope ms,
        log_scope ls,
        m_device md ,
        log_match lm,
        m_staff mst,
        type_device td
        <where>
            1 and
            ms.scope_num = ls.scope_num
            and ls.machine_num=md.machine_num
            and ls.match_id=lm.id
            and lm.staff_job_num=mst.staff_job_num
            and td.id=md.type_id
            <if test="scopeName!=null and scopeName!=''">and ms.scope_name like concat('%',#{scopeName},'%')</if>
            <if test="staffName!=null and staffName!=''">and mst.staff_name like concat('%',#{staffName},'%')</if>
            <if test="startTime!=null and startTime!=''">and ls.log_time &amp;gt #{startTime}</if>
            <if test="endTime!=null and endTime!=''">and ls.log_time &amp;lt #{endTime}</if>
        </where>
</select>
```

```java
EasyExcel.write(response.getOutputStream())
                .needHead(true)
                .head(excelDo.class)
                .excelType(ExcelTypeEnum.XLSX)
                .registerWriteHandler(new ExcelWidthStyleStrategy())
                .sheet("洗消记录")
                .doWrite(excelDoList);
```



#### 性能瓶颈分析

- MyBatis plus 使用标准输出查询结果集日志

  **主要** 造成SQL查询在数据层的8分钟及以上的阻塞，使得接口不可用 

- MySQL 表字段索引缺失

  **主要** 近乎所有参与连接、条件查询、排序的字段索引缺失，导致SQL执行时间过长

- SQL 语句过于复杂

  **重要** 6表及以上的连接查询，影响性能

- MyBatis 查询结果集映射和转换

  **重要** 影响Mapper方法性能

- Excel表生成算法性能较差

  **重要** 影响响应时间

- Excel表样式生成

  **重要** 影响响应时间

- 磁盘性能低下

  **重要** 影响SQL语句执行性能

- CPU性能低下

  **可能** 影响SQL语句执行性能和Excel表生成性能

- 可用RAM不足

  **可能** 制约SQL语句执行性能和Excel生成性能的上限，可能造成接口不可用





### 模拟复现



#### 实现

```mysql
select
    scope.no,
    scope.name,
    staff.no,
    staff.name,
    sub_log.time,
    device.no,
    device.name,
    device_type.name,
    sub_log.comment
from scope,
     device,
     device_type,
     staff,
     log,
     sub_log
where 1
  and scope.no = sub_log.scope_no
  and sub_log.device_no = device.no
  and sub_log.log_id = log.id
  and log.staff_no = staff.no
  and device.type_id = device_type.id;
```

```java
EasyExcel.write(outputStream, ExcelBO.class)
                    .excelType(ExcelTypeEnum.XLSX)
                    .registerWriteHandler(new ExcelWidthStyleStrategy())
                    .sheet("日志")
                    .doWrite(excelBOs);
```

```java
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
    private String logTime;
    
    @ExcelProperty("设备序号")
    private String deviceNo;
    
    @ExcelProperty("设备名")
    private String deviceName;
    
    @ExcelProperty("设备类型")
    private String deviceTypeName;
    
    @ExcelProperty("备注")
    private String comment;
}
```





#### 性能分析

##### 耗时

- 数据查询（单位：秒）

  - SQL执行
  - 数据映射转换

  | 1      | 2      | 3      | 4      | 5      | 平均值 |
  | ------ | ------ | ------ | ------ | ------ | ------ |
  | 19.556 | 17.313 | 16.141 | 15.988 | 16.028 | 17.005 |

- Excel生成（单位：秒）

  | 1      | 2      | 3      | 4      | 5      | 平均值 |
  | ------ | ------ | ------ | ------ | ------ | ------ |
  | 18.051 | 18.227 | 17.942 | 18.915 | 17.911 | 18.209 |

首字节延迟（单位：秒）

| 1     | 2     | 3     | 4     | 5     | 平均值 |
| ----- | ----- | ----- | ----- | ----- | ------ |
| 34.19 | 32.14 | 30.80 | 31.62 | 30.73 | 31.896 |

传输时间（单位：秒）

| 1    | 2    | 3    | 4    | 5    | 平均值 |
| ---- | ---- | ---- | ---- | ---- | ------ |
| 3.44 | 3.40 | 3.28 | 3.28 | 3.29 | 3.34   |

响应时间（单位：秒）

| 1     | 2     | 3     | 4     | 5     | 平均值 |
| ----- | ----- | ----- | ----- | ----- | ------ |
| 37.65 | 35.56 | 34.10 | 34.92 | 34.04 | 35.254 |

请求体大小：22.41 MB

*上述测量未预热，第一次和后续测量结果受多因素影响差距较大*

##### SQL执行

*注意由于操作问题数据量不符，此处结果集仅30万行*

```
-> Nested loop inner join  (cost=8.09e+9 rows=80.1e+6) (actual time=202..14274 rows=321004 loops=1)
    -> Inner hash join (sub_log.device_no = device.`no`), (sub_log.scope_no = scope.`no`)  (cost=8.01e+9 rows=801e+6) (actual time=201..3591 rows=32.1e+6 loops=1)
        -> Table scan on sub_log  (cost=2.52 rows=320259) (actual time=0.758..223 rows=321004 loops=1)
        -> Hash
            -> Inner hash join (no condition)  (cost=25306 rows=250000) (actual time=17.1..44.9 rows=250000 loops=1)
                -> Table scan on staff  (cost=0.00565 rows=100) (actual time=0.0913..0.304 rows=100 loops=1)
                -> Hash
                    -> Inner hash join (no condition)  (cost=302 rows=2500) (actual time=16.5..16.7 rows=2500 loops=1)
                        -> Table scan on scope  (cost=0.106 rows=50) (actual time=0.0704..0.0905 rows=50 loops=1)
                        -> Hash
                            -> Inner hash join (device.type_id = device_type.id)  (cost=51.5 rows=50) (actual time=16.4..16.4 rows=50 loops=1)
                                -> Table scan on device  (cost=0.0754 rows=50) (actual time=0.0916..0.0988 rows=50 loops=1)
                                -> Hash
                                    -> Table scan on device_type  (cost=1.25 rows=10) (actual time=16.3..16.3 rows=10 loops=1)
    -> Filter: (log.staff_no = staff.`no`)  (cost=1e-6 rows=0.1) (actual time=259e-6..259e-6 rows=0.01 loops=32.1e+6)
        -> Single-row index lookup on log using PRIMARY (id=sub_log.log_id)  (cost=1e-6 rows=1) (actual time=90.9e-6..114e-6 rows=1 loops=32.1e+6)
```

关键分析：

MySQL对表`sub_log`（30万行）、`staff`（100行）、`scope`（50行）、`device`（50行）进行笛卡尔积连接，造成了约3200万行的中间结果，耗时3.59秒以上，导致后续对该中间结果与`log`表进行过滤连接时迭代次数过多耗时8秒以上，最终总耗时达14秒以上

##### 系统资源

- RAM 200-300 MB
- 磁盘 I/O 10-20 MB/s





### 优化实现 A



#### 实现

对相关表的所有字段使用索引优化

```mysql
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
```



#### 性能分析

##### 耗时

数据查询（单位：秒）

- SQL执行
- 数据映射转换

| 1      | 2     | 3     | 4     | 5     | 平均值 |
| ------ | ----- | ----- | ----- | ----- | ------ |
| 12.094 | 9.897 | 9.907 | 9.773 | 9.724 | 10.279 |

*上述测量未预热，第一次和后续测量结果受多因素影响差距较大*

##### SQL执行

```
-> Nested loop inner join  (cost=635704 rows=488991) (actual time=0.185..4252 rows=500596 loops=1)
    -> Nested loop inner join  (cost=464557 rows=488991) (actual time=0.175..3012 rows=500596 loops=1)
        -> Nested loop inner join  (cost=293410 rows=488991) (actual time=0.17..2365 rows=500596 loops=1)
            -> Nested loop inner join  (cost=122263 rows=488991) (actual time=0.144..1077 rows=500596 loops=1)
                -> Nested loop inner join  (cost=13.8 rows=50) (actual time=0.115..0.33 rows=50 loops=1)
                    -> Covering index scan on device_type using idx_device_type_name  (cost=1.25 rows=10) (actual time=0.0493..0.0739 rows=10 loops=1)
                    -> Index lookup on device using idx_device_type_id (type_id=device_type.id)  (cost=0.8 rows=5) (actual time=0.0175..0.0249 rows=5 loops=10)
                -> Index lookup on sub_log using idx_sub_log_device_no (device_no=device.`no`)  (cost=1487 rows=9780) (actual time=0.0132..21 rows=10012 loops=50)
            -> Index lookup on scope using idx_scope_no (no=sub_log.scope_no)  (cost=0.25 rows=1) (actual time=0.00197..0.00244 rows=1 loops=500596)
        -> Single-row index lookup on log using PRIMARY (id=sub_log.log_id)  (cost=0.25 rows=1) (actual time=0.00113..0.00116 rows=1 loops=500596)
    -> Index lookup on staff using idx_staff_no (no=log.staff_no)  (cost=0.25 rows=1) (actual time=0.00186..0.00231 rows=1 loops=500596)
```

关键：

- 索引使用后仍需要回表查询字段
- `sub_log`表多次参与全表扫描以实现连接
- `staff`表应先与log表连接，再与`sub_log`表的中间结果连接，但MySQL可能不能同时存在两个中间结果
- `sub_log`表过大





### 优化实现 A .1



#### 实现

调整SQL子语句顺序，并去除`comment`字段的获取（`comment`字段类型为`TEXT`无法建立合适的覆盖索引，应在业务层另行处理）

```mysql
select scope.no,
       scope.name,
       staff.no,
       staff.name,
       sub_log.time,
       device.no,
       device.name,
       device_type.name
from sub_log,
     scope,
     device,
     device_type,
     staff,
     log
where sub_log.scope_no = scope.no
  and sub_log.device_no = device.no
  and sub_log.log_id = log.id
  and log.staff_no = staff.no
  and device.type_id = device_type.id;
```

实现覆盖索引

```mysql
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
```

删除影响选择性导致性能更差的索引

```mysql
drop index idx_staff_no on staff;
```



#### 性能分析

##### 耗时

MySQL执行计划分析（单位：秒）

| 1     | 2     | 3     | 4     | 5     | 平均值 |
| ----- | ----- | ----- | ----- | ----- | ------ |
| 1.826 | 1.847 | 1.831 | 1.873 | 1.929 | 1.861  |

数据查询（单位：秒）

- SQL执行
- 数据映射转换

| 1     | 2     | 3     | 4     | 5     | 平均值 |
| ----- | ----- | ----- | ----- | ----- | ------ |
| 8.264 | 7.339 | 7.702 | 7.836 | 7.243 | 7.677  |

*上述测量数据已预热*

##### SQL执行

仅调整SQL后

```
-> Nested loop inner join  (cost=635704 rows=488991) (actual time=0.235..4168 rows=500596 loops=1)
    -> Nested loop inner join  (cost=464557 rows=488991) (actual time=0.229..2943 rows=500596 loops=1)
        -> Nested loop inner join  (cost=293410 rows=488991) (actual time=0.226..2302 rows=500596 loops=1)
            -> Nested loop inner join  (cost=122263 rows=488991) (actual time=0.22..1033 rows=500596 loops=1)
                -> Nested loop inner join  (cost=13.8 rows=50) (actual time=0.042..0.221 rows=50 loops=1)
                    -> Covering index scan on device_type using idx_device_type_name  (cost=1.25 rows=10) (actual time=0.0211..0.0353 rows=10 loops=1)
                    -> Index lookup on device using idx_device_type_id (type_id=device_type.id)  (cost=0.8 rows=5) (actual time=0.0115..0.0179 rows=5 loops=10)
                -> Index lookup on sub_log using idx_sub_log_device_no (device_no=device.`no`)  (cost=1487 rows=9780) (actual time=0.17..20.2 rows=10012 loops=50)
            -> Index lookup on scope using idx_scope_no (no=sub_log.scope_no)  (cost=0.25 rows=1) (actual time=0.00195..0.00241 rows=1 loops=500596)
        -> Single-row index lookup on log using PRIMARY (id=sub_log.log_id)  (cost=0.25 rows=1) (actual time=0.00112..0.00114 rows=1 loops=500596)
    -> Index lookup on staff using idx_staff_no (no=log.staff_no)  (cost=0.25 rows=1) (actual time=0.00185..0.00229 rows=1 loops=500596)
```

调整索引后

```
-> Nested loop inner join  (cost=414072 rows=484056) (actual time=0.377..1826 rows=500596 loops=1)
    -> Nested loop inner join  (cost=244652 rows=484056) (actual time=0.366..895 rows=500596 loops=1)
        -> Nested loop inner join  (cost=75233 rows=484056) (actual time=0.358..262 rows=500596 loops=1)
            -> Inner hash join (no condition)  (cost=264 rows=2500) (actual time=0.243..1.11 rows=2500 loops=1)
                -> Covering index scan on scope using idx_scope_covering  (cost=0.106 rows=50) (actual time=0.0438..0.123 rows=50 loops=1)
                -> Hash
                    -> Nested loop inner join  (cost=13.8 rows=50) (actual time=0.0644..0.177 rows=50 loops=1)
                        -> Covering index scan on device_type using idx_device_type_name  (cost=1.25 rows=10) (actual time=0.0263..0.03 rows=10 loops=1)
                        -> Index lookup on device using idx_device_type_id (type_id=device_type.id)  (cost=0.8 rows=5) (actual time=0.0124..0.0142 rows=5 loops=10)
            -> Covering index lookup on sub_log using idx_sub_log_covering (scope_no=scope.`no`, device_no=device.`no`)  (cost=10.6 rows=194) (actual time=0.0359..0.0949 rows=200 loops=2500)
        -> Single-row index lookup on log using PRIMARY (id=sub_log.log_id)  (cost=0.25 rows=1) (actual time=0.00112..0.00114 rows=1 loops=500596)
    -> Covering index lookup on staff using idx_staff_covering (no=log.staff_no)  (cost=0.25 rows=1) (actual time=0.00126..0.00171 rows=1 loops=500596)
```





### 优化实现 B



#### 实现

注意到在优化实现 A. 1中，非SQL执行时间在数据层耗时占比达75%以上

在多次试验中注意到将字段`ExcelBO.logTime`的类型从`String`更改为`LocalDateTime`可将非SQL执行时间将低至1秒





### 优化实现 B .1



#### 实现

禁用不必要的样式，可将Excel生成时间降至11秒

```java
EasyExcel.write(outputStream, ExcelBO.class)
                    .excelType(ExcelTypeEnum.XLSX)
                    //.registerWriteHandler(new ExcelWidthStyleStrategy())
                    .sheet("日志")
                    .doWrite(excelBOs);
```





### 优化实现 C



#### 目标

在基于优化实现B .1的基础上，使用一种或多种优化方案，继续降低首字节延迟和响应时间

**目前已经采取的优化方案**（优化实现B）：

- 普通索引
- 覆盖索引
- SQL语句调整
- POJO字段类型更改
- 禁用`EasyExcel`样式

**目前的性能指标**：

MySQL执行计划分析（单位：秒）

|            | 预热     | 1     | 2     | 3     | 4     | 5     | 平均值 |
| ---------- | -------- | ----- | ----- | ----- | ----- | ----- | ------ |
| 内部       | (无数据) | 1.729 | 1.725 | 1.683 | 1.723 | 1.703 | 1.713  |
| 含网络延迟 | (无数据) | 1.873 | 1.856 | 1.814 | 1.847 | 1.821 | 1.842  |

- 数据查询执行时间（单位：秒）

  - SQL执行
  - 数据映射转换

  | 预热  | 1     | 2     | 3     | 4     | 5     | 平均值        |
  | ----- | ----- | ----- | ----- | ----- | ----- | ------------- |
  | 4.537 | 3.723 | 3.825 | 3.787 | 3.745 | 3.729 | 3.891 / 3.762 |

- Excel生成执行时间（单位：秒）

  | 预热   | 1      | 2      | 3      | 4      | 5      | 平均值          |
  | ------ | ------ | ------ | ------ | ------ | ------ | --------------- |
  | 12.262 | 11.230 | 11.189 | 10.291 | 10.629 | 11.159 | 11.127 / 10.899 |

首字节延迟（单位：秒）

| 预热     | 1     | 2     | 3     | 4     | 5     | 平均值 |
| -------- | ----- | ----- | ----- | ----- | ----- | ------ |
| (未统计) | 11.83 | 11.84 | 10.94 | 11.25 | 11.78 | 11.53  |

传输时间（单位：秒）

| 预热     | 1    | 2    | 3    | 4    | 5    | 平均值 |
| -------- | ---- | ---- | ---- | ---- | ---- | ------ |
| (未统计) | 3.12 | 3.17 | 3.13 | 3.12 | 3.11 | 3.13   |

响应时间（单位：秒）

| 预热  | 1     | 2     | 3     | 4     | 5     | 平均值          |
| ----- | ----- | ----- | ----- | ----- | ----- | --------------- |
| 16.80 | 14.96 | 15.03 | 14.09 | 14.39 | 14.90 | 15.028 / 14.674 |

请求体大小：22.41 MB

**可以使用的优化方案**：

- 使用其他数据结构代替POJO降低反射性能损耗
- 降低ORM层查询结果集处理性能损耗
- 流式输出
- MyBatis游标查询
- 使用`WHERE`和`ORDER BY`子句实现的分页查询
- 并行生成
- 使用CSV代替XLSX文件格式





### 优化实现 C .1



#### 阶段目标1

为降低首字节延迟，使用**MyBatis游标**和**流式输出**以尽可能快地方式将第一行数据发送到前端

为了测量游标地性能，使用统一直观的流式输出CSV数据的方式来排除无关变量影响

以下实现基于优化实现B .1



#### 空白对照组

##### 实现

```java
@GetMapping("/csv/origin")
public void exportCsvOrigin(HttpServletResponse response) throws IOException {
    LocalDateTime start = LocalDateTime.now();
    //List<ExcelBO> excelBOs = logMapper.excelOrigin();
    List<ExcelBO> excelBOs = logMapper.excelA1();
    LocalDateTime sqlEnd = LocalDateTime.now();
    log.info("结果集行数：{}", excelBOs.size());
    log.info("SQL执行时间：{}", Duration.between(start, sqlEnd));
    
    response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment;filename=excel.csv");
    response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
    
    try {
        OutputStream outputStream = response.getOutputStream();
        
        // 使用EasyExcel实现性能极差，需要4-5秒
        // EasyExcel.write(outputStream, ExcelBO.class)
        //         .excelType(ExcelTypeEnum.CSV)
        //         .sheet("日志")
        //         .doWrite(excelBOs);
        
        // 手动实现
        // 写入头，包括UTF-8 BOM
        outputStream.write(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
        outputStream.write("内镜序号,内镜名,员工工号,员工姓名,时间,设备序号,设备名,设备类型,备注\n".getBytes(StandardCharsets.UTF_8));
        // 写入数据，每1000行刷新一次
        int count = 0;
        for (ExcelBO excelBO : excelBOs) {
            StringBuilder stringBuilder = new StringBuilder();
            stringBuilder.append(excelBO.getScopeNo()).append(",")
                    .append(excelBO.getScopeName()).append(",")
                    .append(excelBO.getStaffNo()).append(",")
                    .append(excelBO.getStaffName()).append(",")
                    .append(excelBO.getLogTime()).append(",")
                    .append(excelBO.getDeviceNo()).append(",")
                    .append(excelBO.getDeviceName()).append(",")
                    .append(excelBO.getDeviceTypeName()).append(",")
                    .append(excelBO.getComment()).append("\n");
            outputStream.write(stringBuilder.toString().getBytes(StandardCharsets.UTF_8));
            count++;
            if (count % 1000 == 0) {
                outputStream.flush();
            }
        }
        
        outputStream.flush();
        
        LocalDateTime excelEnd = LocalDateTime.now();
        log.info("excel耗时：{}", Duration.between(sqlEnd, excelEnd));
        
    }
    catch (Exception e) {
        log.error(e.getMessage(), e);
        response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
    }
    
    LocalDateTime end = LocalDateTime.now();
    log.info("总耗时：{}", Duration.between(start, end));
}
```

##### 性能分析

文件大小：37.86 MB

Excel生成执行时间（单位：秒）

| 预热  | 1     | 2     | 3     | 4     | 5     | 平均值        |
| ----- | ----- | ----- | ----- | ----- | ----- | ------------- |
| 0.753 | 0.594 | 0.601 | 0.586 | 0.596 | 0.685 | 0.636 / 0.612 |

首字节延迟（单位：秒）

| 预热 | 1    | 2    | 3    | 4    | 5    | 平均值      |
| ---- | ---- | ---- | ---- | ---- | ---- | ----------- |
| 4.86 | 4.40 | 3.82 | 3.92 | 4.44 | 3.76 | 4.20 / 4.07 |

响应时间（单位：秒）

| 预热 | 1    | 2    | 3    | 4    | 5    | 平均值      |
| ---- | ---- | ---- | ---- | ---- | ---- | ----------- |
| 5.63 | 5.02 | 4.44 | 4.52 | 5.05 | 4.46 | 4.85 / 4.70 |



#### 游标实现组

##### 实现

- 更改控制层数据遍历方式
- 更改Mapper方法声明
- `Mapper.xml`保持不变
- 增加数据源URL `useCursorFetch`参数

```java
@GetMapping("/csv/cursor")
@Transactional
public void exportCsvCursor(HttpServletResponse response) throws IOException {
    
    LocalDateTime start = LocalDateTime.now();
    
    try (Cursor<ExcelBO> excelBOCursor = logMapper.excelC1()) {
        
        LocalDateTime sqlEnd = LocalDateTime.now();
        log.info("SQL执行时间：{}", Duration.between(start, sqlEnd));
        
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment;filename=excel.csv");
        response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
        
        
        OutputStream outputStream = response.getOutputStream();
        
        // 手动实现
        // 写入头，包括UTF-8 BOM
        outputStream.write(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
        outputStream.write("内镜序号,内镜名,员工工号,员工姓名,时间,设备序号,设备名,设备类型,备注\n".getBytes(StandardCharsets.UTF_8));
        // 写入数据，每1000行刷新一次
        int count = 0;
        for (ExcelBO excelBO : excelBOCursor) {
            StringBuilder stringBuilder = new StringBuilder();
            stringBuilder.append(excelBO.getScopeNo()).append(",")
                    .append(excelBO.getScopeName()).append(",")
                    .append(excelBO.getStaffNo()).append(",")
                    .append(excelBO.getStaffName()).append(",")
                    .append(excelBO.getLogTime()).append(",")
                    .append(excelBO.getDeviceNo()).append(",")
                    .append(excelBO.getDeviceName()).append(",")
                    .append(excelBO.getDeviceTypeName()).append(",")
                    .append(excelBO.getComment()).append("\n");
            outputStream.write(stringBuilder.toString().getBytes(StandardCharsets.UTF_8));
            count++;
            if (count % 1000 == 0) {
                outputStream.flush();
            }
        }
        
        outputStream.flush();
        
        LocalDateTime excelEnd = LocalDateTime.now();
        log.info("excel耗时：{}", Duration.between(sqlEnd, excelEnd));
        
        LocalDateTime end = LocalDateTime.now();
        log.info("总耗时：{}", Duration.between(start, end));
        
    }
    catch (Exception e) {
        log.error(e.getMessage(), e);
        response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
    }
    
}
```

```java
@Options(fetchSize = Integer.MIN_VALUE)
Cursor<ExcelBO> excelC1();
```

```
jdbc:mysql://127.0.0.1:3306/${crim.default-database}?useUnicode=true&characterEncoding=UTF-8&useSSL=false&serverTimezone=Asia/Shanghai&autoReconnect=true&useCursorFetch=true
```

##### 性能分析

文件大小：37.86 MB

数据查询（单位：秒）

- SQL执行
- 数据映射转换

| 预热     | 1     | 2     | 3     | 4     | 5     | 平均值 |
| -------- | ----- | ----- | ----- | ----- | ----- | ------ |
| (无数据) | 1.790 | 1.744 | 1.781 | 1.777 | 1.723 | 1.763  |

Excel生成执行时间（单位：秒）

| 预热     | 1     | 2     | 3     | 4     | 5     | 平均值 |
| -------- | ----- | ----- | ----- | ----- | ----- | ------ |
| (无数据) | 3.346 | 2.961 | 2.710 | 3.015 | 2.893 | 2.985  |

首字节延迟（单位：秒）

| 预热     | 1    | 2    | 3    | 4    | 5    | 平均值 |
| -------- | ---- | ---- | ---- | ---- | ---- | ------ |
| (无数据) | 1.80 | 1.75 | 1.78 | 1.78 | 1.73 | 1.768  |

响应时间（单位：秒）

| 预热     | 1    | 2    | 3    | 4    | 5    | 平均值 |
| -------- | ---- | ---- | ---- | ---- | ---- | ------ |
| (无数据) | 5.17 | 4.73 | 4.51 | 4.81 | 4.63 | 4.77   |



#### 阶段结论1

注意到使用游标后Mapper方法执行时间与SQL语句在MySQL执行计划分析中的总耗时接近，而游标实现组与空白对照组响应时间接近，说明游标优化仅是将数据层的**网络传输与ORM数据转换**部分耗时分担到Excel生成过程中，因此考虑使用MyBatis的`ResultHandler`或`JDBCTemplate`优化首字节延迟的方案测试结果应与游标优化方案接近

因此考虑到首字节延迟未到达理想数据，**MyBatis的`ResultHandler`**或**`JDBCTemplate`**的进一步优化方案均至此搁置



#### 阶段目标2

为进一步降低首字节延迟，引入**游标分页（键集分页）**技术



##### 原始SQL

（基于优化实现A .1）

```mysql
EXPLAIN ANALYZE
select scope.no,
       scope.name,
       staff.no,
       staff.name,
       sub_log.time,
       device.no,
       device.name,
       device_type.name
from sub_log,
     scope,
     device,
     device_type,
     staff,
     log
where sub_log.scope_no = scope.no
  and sub_log.device_no = device.no
  and sub_log.log_id = log.id
  and log.staff_no = staff.no
  and device.type_id = device_type.id;
```

返回结果集：500596行

MySQL执行计划分析（单位：秒）（数据同优化实现 A .1）

| 预热     | 1     | 2     | 3     | 4     | 5     | 平均值 |
| -------- | ----- | ----- | ----- | ----- | ----- | ------ |
| (无数据) | 1.826 | 1.847 | 1.831 | 1.873 | 1.929 | 1.861  |

##### 游标分页SQL

考虑到业务中表`sub_log`的`id`字段值从1开始自增且连续，基于该字段实现游标分页

```mysql
EXPLAIN ANALYZE
select scope.no,
       scope.name,
       staff.no,
       staff.name,
       sub_log.time,
       device.no,
       device.name,
       device_type.name
from sub_log,
     scope,
     device,
     device_type,
     staff,
     log
where sub_log.scope_no = scope.no
  and sub_log.device_no = device.no
  and sub_log.log_id = log.id
  and log.staff_no = staff.no
  and device.type_id = device_type.id
  and sub_log.id >= {start}
  and sub_log.id <= {end}
ORDER BY sub_log.id;
```

##### SQL分析

注意到当`end-start`的值在不同大小时，执行计划有所不同

###### 执行计划分析

- `end-start=1000`

  执行计划结构形如C 2-1

  |             | 1    | 2      | 3      | 4      | 最小值 | 最大值 | 平均值 |
  | ----------- | ---- | ------ | ------ | ------ | ------ | ------ | ------ |
  | start       | 1    | 200001 | 400001 | 499001 | -      | -      | -      |
  | end         | 1000 | 201000 | 401000 | 500000 | -      | -      | -      |
  | 内部 (ms)   | 8.06 | 9.66   | 8.27   | 8.06   | 8.06   | 9.66   | 8.51   |
  | 含网络 (ms) | 70   | 60     | 59     | 48     | 48     | 70     | 59     |

  预估50万行遍历时间（需考虑查询结果集网络传输及ORM数据转换）> 29.50 秒

- `end-start=5000`

  执行计划结构形如C 2-1

  |             | 1    | 2      | 3      | 4      | 最小值 | 最大值 | 平均值 |
  | ----------- | ---- | ------ | ------ | ------ | ------ | ------ | ------ |
  | start       | 1    | 200001 | 400001 | 495001 | -      | -      | -      |
  | end         | 5000 | 205000 | 405000 | 500000 | -      | -      | -      |
  | 内部 (ms)   | 45.2 | 40.2   | 39.2   | 40.5   | 39.2   | 45.2   | 41.3   |
  | 含网络 (ms) | 104  | 87     | 82     | 83     | 82     | 104    | 89     |

  预估50万行遍历时间（需考虑查询结果集网络传输及ORM数据转换）> 8.90 秒

- `end-start=10000`

  执行计划结构形如C 2-1

  |             | 1     | 2      | 3      | 4      | 最小值 | 最大值 | 平均值 |
  | ----------- | ----- | ------ | ------ | ------ | ------ | ------ | ------ |
  | start       | 1     | 200001 | 400001 | 490001 | -      | -      | -      |
  | end         | 10000 | 210000 | 410000 | 500000 | -      | -      | -      |
  | 内部 (ms)   | 85.1  | 78.8   | 83.4   | 80.6   | 78.8   | 85.1   | 82.0   |
  | 含网络 (ms) | 135   | 114    | 119    | 114    | 114    | 135    | 121    |

  预估50万行遍历时间（需考虑查询结果集网络传输及ORM数据转换）> 6.05 秒

- `end-start=50000`

  执行计划结构形如 C 2-2

  |             | 1     | 2      | 3      | 4      | 最小值 | 最大值 | 平均值 |
  | ----------- | ----- | ------ | ------ | ------ | ------ | ------ | ------ |
  | start       | 1     | 200001 | 400001 | 450001 | -      | -      | -      |
  | end         | 50000 | 250000 | 450000 | 500000 | -      | -      | -      |
  | 内部 (ms)   | 466   | 457    | 458    | 453    | 453    | 466    | 459    |
  | 含网络 (ms) | 508   | 495    | 497    | 488    | 488    | 508    | 497    |

  预估50万行遍历时间（需考虑查询结果集网络传输及ORM数据转换）> 4.97 秒

- `end-start=100000`

  执行计划结构形如 C 2-2

  |             | 1      | 2      | 3      | 平均值 |
  | ----------- | ------ | ------ | ------ | ------ |
  | start       | 1      | 200001 | 400001 | -      |
  | end         | 100000 | 300000 | 500000 | -      |
  | 内部 (ms)   | 690    | 718    | 682    | 697    |
  | 含网络 (ms) | 737    | 760    | 721    | 739    |

  预估50万行遍历时间（需考虑查询结果集网络传输及ORM数据转换）> 3.696 秒

- ``end-start=500000`

  执行计划结构形如 C 2-2

  |             | 1    | 2    | 3    | 4    | 5    | 平均值 |
  | ----------- | ---- | ---- | ---- | ---- | ---- | ------ |
  | 内部 (ms)   | 2507 | 2461 | 2464 | 2496 | 2501 | 2486   |
  | 含网络 (ms) | 2608 | 2544 | 2547 | 2575 | 2582 | 2571   |

###### 执行计划结构

- C 2-1

  ```
  -> Nested loop inner join  (cost=1951 rows=1000) (actual time=0.0469..8.31 rows=1000 loops=1)
      -> Nested loop inner join  (cost=1601 rows=1000) (actual time=0.0426..6.4 rows=1000 loops=1)
          -> Nested loop inner join  (cost=1251 rows=1000) (actual time=0.0403..5.9 rows=1000 loops=1)
              -> Nested loop inner join  (cost=901 rows=1000) (actual time=0.0369..5.09 rows=1000 loops=1)
                  -> Nested loop inner join  (cost=551 rows=1000) (actual time=0.0279..2.56 rows=1000 loops=1)
                      -> Filter: ((sub_log.id >= 1) and (sub_log.id <= 1000))  (cost=201 rows=1000) (actual time=0.0143..0.52 rows=1000 loops=1)
                          -> Index range scan on sub_log using PRIMARY over (1 <= id <= 1000)  (cost=201 rows=1000) (actual time=0.0127..0.407 rows=1000 loops=1)
                      -> Covering index lookup on scope using idx_scope_covering (no=sub_log.scope_no)  (cost=0.25 rows=1) (actual time=0.00145..0.00191 rows=1 loops=1000)
                  -> Index lookup on device using idx_device_no (no=sub_log.device_no)  (cost=0.25 rows=1) (actual time=0.00199..0.0024 rows=1 loops=1000)
              -> Single-row index lookup on device_type using PRIMARY (id=device.type_id)  (cost=0.25 rows=1) (actual time=670e-6..691e-6 rows=1 loops=1000)
          -> Single-row index lookup on log using PRIMARY (id=sub_log.log_id)  (cost=0.25 rows=1) (actual time=344e-6..367e-6 rows=1 loops=1000)
      -> Covering index lookup on staff using idx_staff_covering (no=log.staff_no)  (cost=0.25 rows=1) (actual time=0.00128..0.00175 rows=1 loops=1000)
  ```

- C 2-2

  ```
  -> Sort: sub_log.id  (actual time=461..466 rows=50000 loops=1)
      -> Stream results  (cost=121402 rows=94410) (actual time=0.3..435 rows=50000 loops=1)
          -> Nested loop inner join  (cost=121402 rows=94410) (actual time=0.295..405 rows=50000 loops=1)
              -> Nested loop inner join  (cost=88358 rows=94410) (actual time=0.29..312 rows=50000 loops=1)
                  -> Nested loop inner join  (cost=55315 rows=94410) (actual time=0.285..251 rows=50000 loops=1)
                      -> Inner hash join (no condition)  (cost=265 rows=2500) (actual time=0.226..0.7 rows=2500 loops=1)
                          -> Covering index scan on scope using idx_scope_covering  (cost=0.124 rows=50) (actual time=0.0184..0.0881 rows=50 loops=1)
                          -> Hash
                              -> Nested loop inner join  (cost=13.8 rows=50) (actual time=0.0496..0.191 rows=50 loops=1)
                                  -> Covering index scan on device_type using idx_device_type_name  (cost=1.25 rows=10) (actual time=0.0249..0.0271 rows=10 loops=1)
                                  -> Index lookup on device using idx_device_type_id (type_id=device_type.id)  (cost=0.8 rows=5) (actual time=0.0149..0.0159 rows=5 loops=10)
                      -> Filter: ((sub_log.id >= 1) and (sub_log.id <= 50000))  (cost=2.66 rows=37.8) (actual time=0.0351..0.099 rows=20 loops=2500)
                          -> Covering index lookup on sub_log using idx_sub_log_covering (scope_no=scope.`no`, device_no=device.`no`)  (cost=2.66 rows=194) (actual time=0.0349..0.0891 rows=200 loops=2500)
                  -> Single-row index lookup on log using PRIMARY (id=sub_log.log_id)  (cost=0.25 rows=1) (actual time=0.00109..0.00111 rows=1 loops=50000)
              -> Covering index lookup on staff using idx_staff_covering (no=log.staff_no)  (cost=0.25 rows=1) (actual time=0.0012..0.00171 rows=1 loops=50000)
  ```



#### 阶段结论2

通过在不同批次大小下进行游标分页的性能测试，得到以下数据

| 批次大小                        | 1,000 | 5,000 | 10,000 | 50,000 | 100,000 | 500,000 |
| ------------------------------- | ----- | ----- | ------ | ------ | ------- | ------- |
| 内部耗时平均值 (ms)             | 8.51  | 41.3  | 82.0   | 459    | 697     | 2486    |
| 含网络耗时平均值 (ms)           | 59    | 89    | 121    | 497    | 739     | 2571    |
| 内部耗时占比                    | 14.4% | 46.4% | 67.8%  | 92.4%  | 94.3%   | 96.7%   |
| 50万行预计请求数                | 500   | 100   | 50     | 10     | 5       | 1       |
| 50万行内部遍历预计总耗时 (ms)   | 4255  | 4130  | 4100   | 4590   | 3485    | 2486    |
| 50万行含网络遍历预计总耗时 (ms) | 29500 | 8900  | 6050   | 4970   | 3695    | 2571    |

注意到随着批次逐渐增大，网络延迟和数据传输时间在每次查询总耗时中的占比将逐渐减小，因此可以采取**先拉取小批次数据后拉取大批次数据**和**并发查询**的策略，以降低首字节延迟并降低多次数据库请求带来的性能损耗



#### 阶段目标3

为了确定实际运行环境下在不同批次大小中多次数据库请求所带来的性能损耗，对游标分页查询进行简单遍历测试



##### 测试实现

核心代码

```java
@GetMapping("/page/test/simple")
@Transactional
public String pageTest() throws IOException {
    
    String returnValue = "Hello World!";
    
    long startId = 1;
    long batchSize = 1000;
    long endId = startId + batchSize - 1;
    
    long count = 0;
    long batchIndex = 1;
    
    Duration minDuration = Duration.ofMillis(Long.MAX_VALUE);
    Duration maxDuration = Duration.ZERO;
    
    LocalDateTime start = LocalDateTime.now();
    LocalDateTime current = LocalDateTime.now();
    
    while (true) {
        
        List<ExcelBO> excelBOList = logMapper.excelC1_1(startId, endId);
        
        Duration duration = Duration.between(current, LocalDateTime.now());
        if (duration.compareTo(minDuration) < 0) minDuration = duration;
        if (duration.compareTo(maxDuration) > 0) maxDuration = duration;
        log.info("第{}批次耗时：{}", batchIndex, duration);
        current = LocalDateTime.now();
        batchIndex++;
        
        if (excelBOList == null || excelBOList.isEmpty()) break;
        startId += batchSize;
        endId += batchSize;
        
        for (ExcelBO excelBO : excelBOList) {
            if (excelBO != null)
                count++;
        }
    }
    
    log.info("总耗时：{}", Duration.between(start, LocalDateTime.now()));
    log.info("最小耗时：{}", minDuration);
    log.info("最大耗时：{}", maxDuration);
    
    returnValue += "\n行数：" + count;
    return returnValue;
}
```

```java
List<ExcelBO> excelC1_1(@Param("startId") Long startId, @Param("endId") Long endId);
```

```xml
<select id="excelC1_1" resultType="ExcelBO">
    select scope.no         as scopeNo,
           scope.name       as scopeName,
           staff.no         as staffNo,
           staff.name       as staffName,
           sub_log.time     as logTime,
           device.no        as deviceNo,
           device.name      as deviceName,
           device_type.name as deviceTypeName
    from sub_log,
         scope,
         device,
         device_type,
         staff,
         log
    where sub_log.scope_no = scope.no
      and sub_log.device_no = device.no
      and sub_log.log_id = log.id
      and log.staff_no = staff.no
      and device.type_id = device_type.id
      and sub_log.id >= #{startId}
      and sub_log.id &lt;= #{endId}
    ORDER BY sub_log.id;
</select>
```



#### 阶段结论3

通过在不同批次大小下进行游标分页的简单遍历性能测试，得到以下数据（数据源于多次统计的去除最大值和最小值的平均值）

查询结果集：500596行

| 批次大小     | 1,000 | 5,000 | 10,000 | 50,000 | 100,000 | 500,000 |
| ------------ | ----- | ----- | ------ | ------ | ------- | ------- |
| 批次数       | 502   | 102   | 52     | 12     | 7       | 3       |
| 总耗时       | 6715  | 5923  | 5762   | 6291   | 5312    | 4488    |
| 预估平均耗时 | 13.43 | 59.23 | 115.24 | 629.10 | 1062.4  | 4488.0  |
| 最小耗时     | 11.4  | 54.6  | 108.4  | 599.0  | 1003.2  | 4468.0  |
| 最大耗时     | 69.6  | 105.0 | 174.4  | 684.6  | 1129    | 4468.0  |

注意到随着批次逐渐增大，总耗时总体逐渐减少，符合因多次请求造成的网络上的性能损耗，但在连接池等多种技术的优化下该**性能损耗远低于预估**

注意到第一个批次近乎总是较之后的批次有着明显的执行时间增加，并近乎总是成为最大耗时的批次，这意味着**首字节延迟由最大耗时决定**

注意到事务对第一个批次的执行时间数据存在影响，表现为开启事务后第一个批次执行时间存在几十到百毫秒级别的减少，考虑到可能的原因是连接的建立和部分资源的初始化，上述所有数据均基于事务已开启的情况下测量

考虑到首字节延迟，10000及以下的批次大小更为合理



#### 阶段目标4

为了优化在10000及以下批次大小的响应时间，对并行查询进行测试



##### 并行查询实现

```java
/**
 * 键集分页并发查询测试
 */
@GetMapping("/page/test/thread")
public String pageTestThread() throws IOException {
    
    String returnValue = "Hello World!";
    
    final long threadsCount = 4;
    final long batchSize = 1000;
    
    final LocalDateTime start = LocalDateTime.now();
    
    
    // 定义线程任务，0线程任务处理第1批次，1线程任务处理第2批次，以此类推
    class PageTask implements Runnable {
        
        // 从0开始
        private final int threadIndex;
        // 1是最小值
        private long startId;
        private long count = 0;
        private long batchCount = 0;
        
        private final List<LocalDateTime> times = new ArrayList<>();
        
        public PageTask(int threadIndex) {
            this.threadIndex = threadIndex;
            this.startId = threadIndex * batchSize + 1;
        }
        
        @Override
        public void run() {
            try {
                
                this.times.add(LocalDateTime.now());
                while (true) {
                    List<ExcelBO> excelBOList = logMapper.excelC1_1(startId, startId + batchSize - 1);
                    
                    this.batchCount++;
                    this.times.add(LocalDateTime.now());
                    log.info("线程任务{}已处理{}批次数据", this.threadIndex, this.batchCount);
                    
                    if (excelBOList == null || excelBOList.isEmpty()) break;
                    startId += batchSize * threadsCount;
                    for (ExcelBO excelBO : excelBOList) {
                        if (excelBO != null)
                            count++;
                    }
                }
                
                LocalDateTime threadEnd = LocalDateTime.now();
                
                // 耗时统计
                List<Duration> durations = new ArrayList<>();
                for (int i = 0; i < this.times.size() - 1; i++) {
                    durations.add(Duration.between(this.times.get(i), this.times.get(i + 1)));
                }
                log.info("线程任务{}耗时：{}", this.threadIndex, durations);
                log.info("线程任务{}行数：{}", this.threadIndex, this.count);
                log.info("线程任务{}遍历耗时：{}", this.threadIndex,
                        Duration.between(this.times.get(0), this.times.get(this.times.size() - 1)));
                log.info("线程任务{}总耗时：{}", this.threadIndex, Duration.between(start, threadEnd));
                // 统计五个最大耗时
                List<Duration> maxDurations = durations.stream().sorted(Comparator.reverseOrder())
                        .limit(5).collect(Collectors.toList());
                log.info("线程任务{}最大耗时：{}", this.threadIndex, maxDurations);
                // 统计五个最小耗时
                List<Duration> minDurations = durations.stream().sorted().limit(5).collect(Collectors.toList());
                log.info("线程任务{}最小耗时：{}", this.threadIndex, minDurations);
                
                
            }
            catch (Exception e) {
                log.error("线程任务{}异常：{}", this.threadIndex, e.getMessage());
            }
        }
    }
    
    List<Thread> threads = new ArrayList<>();
    for (int i = 0; i < threadsCount; i++) {
        Thread thread = new Thread(new PageTask(i));
        threads.add(thread);
    }
    threads.forEach(Thread::start);
    threads.forEach(thread -> {
        try {
            thread.join();
        }
        catch (InterruptedException e) {
            log.error("线程任务{}异常：{}", thread.getName(), e.getMessage());
        }
    });
    
    LocalDateTime end = LocalDateTime.now();
    log.info("所有线程总耗时：{}", Duration.between(start, end));
    
    return returnValue;
}
```



#### 阶段结论4

在多次测试中注意到，随着线程数增多，查询速度可明显下降，可有效解决多次网络请求造成的性能损耗，但测试中若需将查询速度下降至单线程方案的50%及以下需要4个线程，考虑到多线程优化受实际硬件性能影响较大，此方案的具体实践有待考量



#### 阶段目标5

为了进一步降低首字节延迟，积极寻求能在最短时间内将生成中的XLSX数据尽快发送到网络输出流中的解决方案

以下基于``实现



##### FastExcel实现

```xml
<dependency>
    <groupId>org.dhatim</groupId>
    <artifactId>fastexcel</artifactId>
    <version>0.19.0</version>
</dependency>
```

```java
@GetMapping("/excel/fast")
public void exportExcelFast(HttpServletResponse response) throws IOException {
    
    LocalDateTime start = LocalDateTime.now();
    List<ExcelBO> excelBOs = logMapper.excelA1();
    LocalDateTime sqlEnd = LocalDateTime.now();
    log.info("结果集行数：{}", excelBOs.size());
    log.info("SQL执行时间：{}", Duration.between(start, sqlEnd));
    
    response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment;filename=excel.xlsx");
    response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
    
    OutputStream outputStream = response.getOutputStream();
    
    // org.dhatim.fastexcel.Workbook
    try (Workbook workbook = new Workbook(outputStream, "ELEE", "1.0")) {
        
        Worksheet worksheet = workbook.newWorksheet("日志");
        // 写入头
        worksheet.value(0, 0, "内镜序号");
        worksheet.value(0, 1, "内镜名");
        worksheet.value(0, 2, "员工工号");
        worksheet.value(0, 3, "员工姓名");
        worksheet.value(0, 4, "时间");
        worksheet.value(0, 5, "设备序号");
        worksheet.value(0, 6, "设备名");
        worksheet.value(0, 7, "设备类型");
        worksheet.value(0, 8, "备注");
        // 写入数据
        for (int i = 0; i < excelBOs.size(); i++) {
            worksheet.value(i + 1, 0, excelBOs.get(i).getScopeNo());
            worksheet.value(i + 1, 1, excelBOs.get(i).getScopeName());
            worksheet.value(i + 1, 2, excelBOs.get(i).getStaffNo());
            worksheet.value(i + 1, 3, excelBOs.get(i).getStaffName());
            worksheet.value(i + 1, 4, excelBOs.get(i).getLogTime());
            worksheet.style(i + 1, 4).format("yyyy-MM-dd HH:mm:ss").set();
            worksheet.value(i + 1, 5, excelBOs.get(i).getDeviceNo());
            worksheet.value(i + 1, 6, excelBOs.get(i).getDeviceName());
            worksheet.value(i + 1, 7, excelBOs.get(i).getDeviceTypeName());
            worksheet.value(i + 1, 8, excelBOs.get(i).getComment());
            // 每隔1000行写入一次
            if (i % 1000 == 0) {
                worksheet.flush();
                outputStream.flush();
            }
        }
        worksheet.finish();
        workbook.close();
        outputStream.flush();
        
        LocalDateTime excelEnd = LocalDateTime.now();
        log.info("excel耗时：{}", Duration.between(sqlEnd, excelEnd));
        
    }
    catch (Exception e) {
        log.error(e.getMessage(), e);
        response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
    }
    
    LocalDateTime end = LocalDateTime.now();
    log.info("总耗时：{}", Duration.between(start, end));
    
}
```



#### 阶段结论5

对上述实现进行测试，得到以下数据

文件大小：17.09 MB

|                | 预热  | 1     | 2     | 3     | 4     | 5     | 平均值        |
| -------------- | ----- | ----- | ----- | ----- | ----- | ----- | ------------- |
| 数据层耗时 (s) | 4.588 | 3.866 | 3.685 | 4.450 | 3.698 | 3.681 | 3.995 / 3.876 |
| Excel耗时 (s)  | 3.368 | 3.539 | 3.257 | 3.047 | 3.054 | 3.492 | 3.293 / 3.278 |
| 首字节延迟 (s) | 4.67  | 3.87  | 3.69  | 4.45  | 3.70  | 3.68  | 4.01 / 3.88   |
| 响应时间 (s)   | 8.03  | 7.42  | 6.95  | 7.51  | 6.76  | 7.18  | 7.31 / 7.16   |

可以发现，首字节延迟明显接近数据层耗时，证明使用**FastExcel**代替EasyExcel以用于优化首字节延迟是**可行的**，接下来应基于之前的经验，运用**键集分页**和**游标**继续优化首字节延迟



#### 阶段目标6

基于阶段结论3和阶段结论5的经验，现集成键集分页和FastExcel流式输出



##### 集成实现

```java
@GetMapping("/page/fast/simple")
@Transactional
public void pageFastSimple(HttpServletResponse response) throws IOException {
    
    long startId = 1;
    long batchSize = 10000;
    int row = 0;
    
    LocalDateTime start = LocalDateTime.now();
    
    
    response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment;filename=excel.xlsx");
    response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
    
    OutputStream outputStream = response.getOutputStream();
    try (Workbook workbook = new Workbook(outputStream, "ELEE", "1.0")) {
        
        Worksheet worksheet = workbook.newWorksheet("日志");
        // 写入头
        worksheet.value(0, 0, "内镜序号");
        worksheet.value(0, 1, "内镜名");
        worksheet.value(0, 2, "员工工号");
        worksheet.value(0, 3, "员工姓名");
        worksheet.value(0, 4, "时间");
        worksheet.value(0, 5, "设备序号");
        worksheet.value(0, 6, "设备名");
        worksheet.value(0, 7, "设备类型");
        worksheet.value(0, 8, "备注");
        
        row++;
        
        // 写入数据
        while (true) {
            List<ExcelBO> excelBOList = logMapper.excelC1_1(startId, startId + batchSize - 1);
            
            if (excelBOList == null || excelBOList.isEmpty()) break;
            startId += batchSize;
            
            for (ExcelBO excelBO : excelBOList) {
                worksheet.value(row, 0, excelBO.getScopeNo());
                worksheet.value(row, 1, excelBO.getScopeName());
                worksheet.value(row, 2, excelBO.getStaffNo());
                worksheet.value(row, 3, excelBO.getStaffName());
                worksheet.value(row, 4, excelBO.getLogTime());
                worksheet.style(row, 4).format("yyyy-MM-dd HH:mm:ss").set();
                worksheet.value(row, 5, excelBO.getDeviceNo());
                worksheet.value(row, 6, excelBO.getDeviceName());
                worksheet.value(row, 7, excelBO.getDeviceTypeName());
                worksheet.value(row, 8, excelBO.getComment());
                
                row++;
            }
            
            worksheet.flush();
            outputStream.flush();
            
            // 显式清除数据
            excelBOList.clear();
        }
        
        // 后处理
        worksheet.finish();
        workbook.close();
        outputStream.flush();
        
        log.info("总耗时：{}", Duration.between(start, LocalDateTime.now()));
        log.info("行数：{}", row);
    }
    catch (Exception e) {
        log.error(e.getMessage(), e);
        response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
    }
}
```



#### 阶段结论6

通过FastExcel和键集分页，在测试中首字节延迟普遍降至200至500 ms，但响应时间普遍在9至10秒

同时，注意到Excel文件大小较阶段5从17.09 MB增长至18.71 MB，且Excel生成时间有所增长，通过分析Excel数据和FastExcel源码后推测原因为FastExcel通过缓存重复字符串并利用XLSX共享字符串功能来减少文件体积，同时阶段5中SQL查询结果集在临近行存在大量的重复字符串，使得阶段5的Excel生成速度更快

为了进一步降低响应时间，接下来应考虑使用**生产者消费者模式**



#### 阶段目标7

为了进一步优化响应时间，将基于阶段结论6的经验，使用生产者消费者模式进行优化

实现该模式需要阻塞队列，候选的有：

- `ArrayBlockingQueue` 有界、内存开销小、单锁竞争高并发场景吞吐量低、支持公平锁
- `LinkedBlockingQueue` 有界或无界、内存开销大、双锁分离支持高并发场景、长队列场景下吞吐量可能下降
- `PriorityBlockingQueue` 无界、支持优先级、单锁竞争高并发场景吞吐量低、长队列场景下吞吐量很低

此处考虑到阻塞队列的吞吐性能尚不足以对接口响应时间产生明显影响，此处采用更为通用的`LinkedBlockingQueue`



##### 生产者消费者模式实现

```java
@GetMapping("/page/fast/complex")
public void pageFastComplex(HttpServletResponse response) throws IOException {
    
    final LocalDateTime start = LocalDateTime.now();
    // 统计生产者每批次读取数据库耗时
    final List<Duration> producerReadTimes = new ArrayList<>();
    // 统计生产者写入队列耗时
    final List<Duration> producerWriteTimes = new ArrayList<>();
    // 统计消费者从队列读出耗时
    final List<Duration> consumerReadTimes = new ArrayList<>();
    // 统计消费者每批次写入数据耗时
    final List<Duration> consumerWriteTimes = new ArrayList<>();
    
    
    final long batchSize = 10000;
    final int queueLength = 100;
    // 生产者队列最长阻塞时间，单位秒
    final long producerQueueTimeout = 60;
    // 消费者队列最长阻塞时间，单位秒
    final long consumerQueueTimeout = 60;
    
    // 定义队列
    final LinkedBlockingQueue<List<ExcelBO>> queue = new LinkedBlockingQueue<>(queueLength);
    
    
    // 定义生产者线程，读取数据并放入队列
    class Producer implements Runnable {
        @Override
        public void run() {
            try {
                
                LocalDateTime producerStart = LocalDateTime.now();
                long startId = 1;
                long count = 0;
                while (true) {
                    
                    LocalDateTime sqlStart = LocalDateTime.now();
                    List<ExcelBO> excelBOList = logMapper.excelC1_1(startId, startId + batchSize - 1);
                    producerReadTimes.add(Duration.between(sqlStart, LocalDateTime.now()));
                    
                    count += excelBOList.size();
                    
                    // 将列表放入队列，不可超出最长阻塞时间，否则报错退出
                    // 如果抛出 InterruptedException，应退出
                    // 如果列表为空也放入，作为队列结束标记
                    try {
                        LocalDateTime queueStart = LocalDateTime.now();
                        if (!queue.offer(excelBOList, producerQueueTimeout, TimeUnit.SECONDS))
                            throw new RuntimeException("生产者队列阻塞超时：" + producerQueueTimeout + "秒");
                        producerWriteTimes.add(Duration.between(queueStart, LocalDateTime.now()));
                    }
                    catch (InterruptedException e) {
                        throw new RuntimeException("生产者线程中断", e);
                    }
                    
                    if (excelBOList.isEmpty()) break;
                    startId += batchSize;
                }
                
                log.info("生产者行数：{}", count);
                log.info("生产者总耗时：{}", Duration.between(start, LocalDateTime.now()));
                log.info("生产者内部总耗时：{}", Duration.between(producerStart, LocalDateTime.now()));
                
                // SQL请求数
                log.info("生产者SQL请求数：{}", producerReadTimes.size());
                // SQL耗时
                log.info("生产者SQL耗时：{}", producerReadTimes);
                // SQL总耗时
                Duration sqlTotalTime = Duration.ZERO;
                for (Duration duration : producerReadTimes) sqlTotalTime = sqlTotalTime.plus(duration);
                log.info("生产者SQL总耗时：{}", sqlTotalTime);
                // SQL平均耗时
                log.info("生产者SQL平均耗时：{}", sqlTotalTime.dividedBy(producerReadTimes.size()));
                // 最长SQL耗时，五个
                log.info("生产者SQL最长耗时：{}", producerReadTimes.stream().sorted(Comparator.reverseOrder()).limit(5).collect(Collectors.toList()));
                // 最短SQL耗时，五个
                log.info("生产者SQL最短耗时：{}", producerReadTimes.stream().sorted().limit(5).collect(Collectors.toList()));
                
                // 队列写入数
                log.info("生产者队列写入数：{}", producerWriteTimes.size());
                // 队列阻塞耗时
                log.info("生产者队列阻塞耗时：{}", producerWriteTimes);
                // 队列阻塞总耗时
                Duration queueTotalTime = Duration.ZERO;
                for (Duration duration : producerWriteTimes) queueTotalTime = queueTotalTime.plus(duration);
                log.info("生产者队列阻塞总耗时：{}", queueTotalTime);
                // 队列阻塞平均耗时
                log.info("生产者队列阻塞平均耗时：{}", queueTotalTime.dividedBy(producerWriteTimes.size()));
                // 最长队列阻塞耗时，五个
                log.info("生产者队列最长阻塞耗时：{}", producerWriteTimes.stream().sorted(Comparator.reverseOrder()).limit(5).collect(Collectors.toList()));
                // 最短队列阻塞耗时，五个
                log.info("生产者队列最短阻塞耗时：{}", producerWriteTimes.stream().sorted().limit(5).collect(Collectors.toList()));
                
            }
            catch (Exception e) {
                log.error(e.getMessage(), e);
            }
        }
    }
    
    // 启动生产者线程
    new Thread(new Producer(), "生产者线程-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")))
            .start();
    
    
    // 消费者线程即处理请求的线程本身，从队列中取出数据并写入文件
    
    LocalDateTime consumerStart = LocalDateTime.now();
    
    int row = 0;
    
    response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment;filename=excel.xlsx");
    response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
    OutputStream outputStream = response.getOutputStream();
    
    try (Workbook workbook = new Workbook(outputStream, "ELEE", "1.0")) {
        Worksheet worksheet = workbook.newWorksheet("日志");
        // 写入头
        worksheet.value(0, 0, "内镜序号");
        worksheet.value(0, 1, "内镜名");
        worksheet.value(0, 2, "员工工号");
        worksheet.value(0, 3, "员工姓名");
        worksheet.value(0, 4, "时间");
        worksheet.value(0, 5, "设备序号");
        worksheet.value(0, 6, "设备名");
        worksheet.value(0, 7, "设备类型");
        worksheet.value(0, 8, "备注");
        
        worksheet.flush();
        outputStream.flush();
        
        row++;
        
        // 写入数据
        while (true) {
            
            List<ExcelBO> excelBOList;
            try {
                LocalDateTime queueStart = LocalDateTime.now();
                excelBOList = queue.poll(consumerQueueTimeout, TimeUnit.SECONDS);
                if (excelBOList == null)
                    throw new RuntimeException("消费者队列阻塞超时：" + consumerQueueTimeout + "秒");
                consumerReadTimes.add(Duration.between(queueStart, LocalDateTime.now()));
            }
            catch (InterruptedException e) {
                throw new RuntimeException("消费者线程中断", e);
            }
            
            // 如果列表为空，说明队列已结束，退出
            if (excelBOList.isEmpty()) break;
            
            LocalDateTime writeStart = LocalDateTime.now();
            for (ExcelBO excelBO : excelBOList) {
                worksheet.value(row, 0, excelBO.getScopeNo());
                worksheet.value(row, 1, excelBO.getScopeName());
                worksheet.value(row, 2, excelBO.getStaffNo());
                worksheet.value(row, 3, excelBO.getStaffName());
                worksheet.value(row, 4, excelBO.getLogTime());
                worksheet.style(row, 4).format("yyyy-MM-dd HH:mm:ss").set();
                worksheet.value(row, 5, excelBO.getDeviceNo());
                worksheet.value(row, 6, excelBO.getDeviceName());
                worksheet.value(row, 7, excelBO.getDeviceTypeName());
                worksheet.value(row, 8, excelBO.getComment());
                
                row++;
            }
            
            // 频繁刷新可能造成性能损耗，但可能会减少内存占用
            // 核心作用是将后处理所需的时间平摊到数据写入时间中，从而降低响应时间（在消费者处理快于生产者时）
            worksheet.flush();
            outputStream.flush();
            
            // 显式清除数据，可能存在问题？
            excelBOList.clear();
            
            consumerWriteTimes.add(Duration.between(writeStart, LocalDateTime.now()));
        }
        
        LocalDateTime postStart = LocalDateTime.now();
        // 后处理
        worksheet.finish();
        workbook.close();
        outputStream.flush();
        LocalDateTime postEnd = LocalDateTime.now();
        
        log.info("消费者行数：{}", row);
        log.info("消费者总耗时：{}", Duration.between(start, LocalDateTime.now()));
        log.info("消费者内部总耗时：{}", Duration.between(consumerStart, LocalDateTime.now()));
        // 后处理耗时
        log.info("消费者后处理耗时：{}", Duration.between(postStart, postEnd));
        
        log.info("消费者队列读取数：{}", consumerReadTimes.size());
        log.info("消费者队列阻塞耗时：{}", consumerReadTimes);
        // 队列阻塞总耗时
        Duration queueTotalTime = Duration.ZERO;
        for (Duration duration : consumerReadTimes) queueTotalTime = queueTotalTime.plus(duration);
        log.info("消费者队列阻塞总耗时：{}", queueTotalTime);
        // 队列阻塞平均耗时
        log.info("消费者队列阻塞平均耗时：{}", queueTotalTime.dividedBy(consumerReadTimes.size()));
        // 最长队列阻塞耗时，五个
        log.info("消费者队列最长阻塞耗时：{}", consumerReadTimes.stream().sorted(Comparator.reverseOrder()).limit(5).collect(Collectors.toList()));
        // 最短队列阻塞耗时，五个
        log.info("消费者队列最短阻塞耗时：{}", consumerReadTimes.stream().sorted().limit(5).collect(Collectors.toList()));
        
        log.info("消费者Excel写入任务数：{}", consumerWriteTimes.size());
        log.info("消费者Excel写入耗时：{}", consumerWriteTimes);
        // Excel写入总耗时
        Duration excelTotalTime = Duration.ZERO;
        for (Duration duration : consumerWriteTimes) excelTotalTime = excelTotalTime.plus(duration);
        log.info("消费者Excel写入总耗时：{}", excelTotalTime);
        // Excel写入平均耗时
        log.info("消费者Excel写入平均耗时：{}", excelTotalTime.dividedBy(consumerWriteTimes.size()));
        // 最长Excel写入耗时，五个
        log.info("消费者Excel写入最长耗时：{}", consumerWriteTimes.stream().sorted(Comparator.reverseOrder()).limit(5).collect(Collectors.toList()));
        // 最短Excel写入耗时，五个
        log.info("消费者Excel写入最短耗时：{}", consumerWriteTimes.stream().sorted().limit(5).collect(Collectors.toList()));
        
    }
    catch (Exception e) {
        log.error(e.getMessage(), e);
        response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
    }
}
```



##### 测试结果

对上述实现进行测试，得到以下数据：

文件大小：18.71 MB

生产者线程数：1

消费者线程数：1

批次大小：10000

队列限长：100

| 生产者                | 预热  | 1     | 2     | 3     | 4     | 5     |
| --------------------- | ----- | ----- | ----- | ----- | ----- | ----- |
| 总耗时 (ms)           | 6718  | 6127  | 6126  | 5940  | 5789  | 6005  |
| 内部耗时 (ms)         | 6709  | 6127  | 6126  | 5940  | 5789  | 6004  |
| Mapper请求数          | 52    | 52    | 52    | 52    | 52    | 52    |
| Mapper总耗时 (ms)     | 6704  | 6127  | 6125  | 5940  | 5788  | 6004  |
| Mapper平均耗时 (ms)   | 128.9 | 117.8 | 117.8 | 114.2 | 111.3 | 115.5 |
| Mapper首次耗时 (ms)   | 288   | 150   | 160   | 145   | 132   | 151   |
| Mapper最长耗时 (ms)   | 174   | 144   | 145   | 141   | 131   | 147   |
| Mapper最短耗时 (ms)   | 111   | 110   | 109   | 110   | 110   | 109   |
| 队列写入数            | 52    | 52    | 52    | 52    | 52    | 52    |
| 队列阻塞总耗时 (ms)   | 1     | 0     | 0     | 0     | 1     | 0     |
| 队列阻塞平均耗时 (ms) | 0.02  | 0     | 0     | 0     | 0.02  | 0     |
| 队列阻塞最长耗时 (ms) | 1     | 0     | 0     | 0     | 1     | 0     |
| 队列阻塞最短耗时 (ms) | 0     | 0     | 0     | 0     | 0     | 0     |

| 消费者                 | 预热 | 1    | 2    | 3    | 4    | 5    |
| ---------------------- | ---- | ---- | ---- | ---- | ---- | ---- |
| 总耗时 (ms)            | 6789 | 6218 | 6189 | 6003 | 5859 | 6072 |
| 内部总耗时 (ms)        | 6780 | 6218 | 6189 | 6003 | 5859 | 6072 |
| 后处理耗时 (ms)        | 8    | 3    | 2    | 2    | 2    | 1    |
| 队列读取数             | 52   | 52   | 52   | 52   | 52   | 52   |
| 队列阻塞总耗时 (ms)    | 2836 | 2487 | 2513 | 2396 | 2336 | 2498 |
| 队列阻塞平均耗时 (ms)  | 54.5 | 47.8 | 48.3 | 46.1 | 44.9 | 48.0 |
| 队列阻塞首次耗时 (ms)  | 270  | 121  | 114  | 118  | 114  | 115  |
| 队列阻塞最长耗时 (ms)  | 89   | 59   | 65   | 60   | 55   | 81   |
| 队列阻塞最短耗时 (ms)  | 26   | 31   | 38   | 31   | 36   | 40   |
| Excel任务数            | 51   | 51   | 51   | 51   | 51   | 51   |
| Excel写入总耗时 (ms)   | 3916 | 3725 | 3672 | 3605 | 3520 | 3572 |
| Excel写入平均耗时 (ms) | 76.8 | 73.0 | 72.0 | 70.7 | 69.0 | 70.0 |
| Excel写入首次耗时 (ms) | 128  | 100  | 99   | 90   | 83   | 93   |
| Excel写入最长耗时 (ms) | 95   | 95   | 96   | 89   | 83   | 91   |
| Excel写入最短耗时 (ms) | 65   | 63   | 65   | 65   | 64   | 64   |

| 接口            | 预热  | 1    | 2    | 3    | 4    | 5    |
| --------------- | ----- | ---- | ---- | ---- | ---- | ---- |
| 首字节延迟 (ms) | 102.3 | 3.1  | 1.7  | 1.5  | 1.5  | 1.6  |
| 响应时间 (s)    | 6.88  | 6.23 | 6.2  | 6.01 | 5.87 | 6.08 |

*关于生产者耗时的统计范围：*

- *生产者总耗时*
  - 生产者线程及资源初始化
  - *内部耗时*
    - *Mapper方法总耗时*
    - *队列写入阻塞总耗时*

*其中Mapper方法耗时和队列写入阻塞耗时的第一次和末尾两次耗时数据受其他因素影响较大，不纳入Mapper方法最长/最短耗时和队列写入阻塞最长/最短耗时的统计范围内*

*关于消费者耗时的统计范围：*

- *消费者总耗时*
  - 消费者线程及资源的初始化
  - *内部耗时*
    - 网络输出流的头写入
      - HTTP响应头写入
      - XLSX流部分数据写入
    - *队列读取阻塞总耗时*
    - *Excel写入总耗时*
    - *后处理耗时*
      - XLSX流部分数据写入
      - 资源释放

*其中Excel写入耗时的第一次和末尾两次耗时数据和队列读取阻塞耗时的第一次和末尾一次耗时数据的受其他因素影响较大，不纳入Excel写入最长/最短耗时和队列读取阻塞最长/最短耗时的统计范围内*



#### 阶段结论7

对测试结果进行分析，得到：

| 生产者                | 预热  | 后续平均 |
| --------------------- | ----- | -------- |
| 总耗时 (ms)           | 6718  | 5997.4   |
| 内部耗时 (ms)         | 6709  | 5997.2   |
| Mapper请求数          | 52    | 52       |
| Mapper总耗时 (ms)     | 6704  | 5996.8   |
| Mapper平均耗时 (ms)   | 128.9 | 115.3    |
| Mapper首次耗时 (ms)   | 288   | 147.6    |
| Mapper最长耗时 (ms)   | 174   | 141.6    |
| Mapper最短耗时 (ms)   | 111   | 109.6    |
| 队列写入数            | 52    | 52       |
| 队列阻塞总耗时 (ms)   | 1     | 0.2      |
| 队列阻塞平均耗时 (ms) | 0.02  | 0.004    |
| 队列阻塞最长耗时 (ms) | 1     | 0.2      |
| 队列阻塞最短耗时 (ms) | 0     | 0        |

| 消费者                 | 预热 | 后续平均 |
| ---------------------- | ---- | -------- |
| 总耗时 (ms)            | 6789 | 6068.2   |
| 内部总耗时 (ms)        | 6780 | 6068.2   |
| 后处理耗时 (ms)        | 8    | 2        |
| 队列读取数             | 52   | 52       |
| 队列阻塞总耗时 (ms)    | 2836 | 2446.0   |
| 队列阻塞平均耗时 (ms)  | 54.5 | 47.0     |
| 队列阻塞首次耗时 (ms)  | 270  | 116.4    |
| 队列阻塞最长耗时 (ms)  | 89   | 64       |
| 队列阻塞最短耗时 (ms)  | 26   | 35.2     |
| Excel任务数            | 51   | 51       |
| Excel写入总耗时 (ms)   | 3916 | 3618.8   |
| Excel写入平均耗时 (ms) | 76.8 | 70.94    |
| Excel写入首次耗时 (ms) | 128  | 93       |
| Excel写入最长耗时 (ms) | 95   | 90.8     |
| Excel写入最短耗时 (ms) | 65   | 64.2     |

| 接口            | 预热  | 后续平均 |
| --------------- | ----- | -------- |
| 首字节延迟 (ms) | 102.3 | 1.88     |
| 响应时间 (s)    | 6.88  | 6.08     |

因此可以得出以下结论：

1. 在批次大小为10000的情况下，由阶段3知查询所有批次需要约5.7秒，由阶段5知写入所有数据需要3.2秒，由阶段6知由于键集分页查询较一次查询结果集各行顺序有所不同，使得Excel写入性能有所下降，与本次测试数据中Excel写入耗时在3.6至3.9秒的数据相符，因此理论上10000批次大小的分批次查询与FastExcel写入总耗时大于8.9秒，与阶段6实测数据相符，而通过生产者消费者模式将响应时间降低至6.08至6.88秒，**性能提升符合预期**
2. 在实际测试中，当批次为1000或5000时，由于数据库请求数量增加，**导致响应时间有所增加**
3. 在不考虑游标的情况下，键集分页查询的总耗时为5至7秒，Excel写入总耗时为3.6至3.9秒；而一次查询的时间是3.7至4.5秒，Excel写入总耗时为3.2秒。因此在大多数情况下消费者处理速度大于生产者处理速度，这与本次测试数据中队列阻塞几乎完全发生在消费者且阻塞总耗时高达2.4到2.8秒的数据相符，因此**优化生产者消费者处理速度使之互相匹配是进一步优化响应速度的关键**，即提高生产者处理速度以降低性能瓶颈
4. 在通过写入Excel表头后立即刷新工作表和输出流以保障较低的首字节延迟前提下，**使用游标有进一步优化响应时间的可能性**。考虑两种情况：在使用一次查询方案时，由阶段1知首行返回耗时约1.7秒，考虑到一次查询的时间是3.7至4.5秒，也就是所有行简单遍历的时间在约2至2.8秒，稍快于Excel写入总耗时的3.2秒，此时接口的响应时间预估在5秒以下；而在使用10000批次大小以下的分批次查询中，由阶段3知即使未包含简单遍历时间，总耗时也为4至4.2秒，考虑简单遍历时间约为2至2.8秒，那么此时游标则不能有效降低响应时间。因此**使用游标有进一步优化响应时间的可能性的前提是使用一次查询方案**，且使用游标在查询结果集大于50万行时也有响应时间性能优于当前方案的可能性（100万行结果集预估：当前测试方案：12至13秒；游标优化方案：10秒；200万行结果集预估：当前方案：24至26秒；游标优化方案：20秒），但需考虑由于使用游标，阻塞队列中的每一个元素实际上是单个`ExcelBO`或`Map`对象，这不仅可能明显提高内存占用，还可能导致`LinkedBlockingQueue`在长队列下的性能下降，需考虑使用`ArrayBlockingQueue`或限制队列长度来优化性能
5. 在不考虑游标的情况下，**使用并发查询有进一步优化响应时间的可能性，但需要使用`PriorityBlockingQueue`保障任务先后顺序**。由阶段4可知并发查询能有效降低响应时间，但是此方案可能对数据库和系统资源造成很大的压力，若需平衡生产者和消费者的处理速度，则预估需要2至3个生产者线程，且并发查询的实际优化效果受硬件环境影响很大
6. 在实际测试中，**消费者是否在每个写入任务中对工作表和输出流的刷新对响应时间有很大影响**，这是因为当前测试方案下消费者处理速度大于生产者，如果消费者选择在后处理阶段才刷新工作表和输出流，将导致工作表在内存中滞留使得内存占用升高，且由于工作表写入速度加快消费者队列阻塞耗时也将增加，后处理阶段将工作表写入到输出流也至少需要2至3秒，这将极大地增加响应时间。
7. 在实际测试中，注意到消费者手动调用列表的`clear`方法能够有效降低堆内存峰值占用，推测存在能提高CG性能的可能性



#### 补充

以下是其他性能指标测试



##### Spring Boot Actuator

基于Spring Boot Actuator和Spring Boot Monitor并限制JVM最大堆内存来对内存和CG性能进行简单测试

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>

<dependency>
    <groupId>cn.pomit</groupId>
    <artifactId>spring-boot-monitor</artifactId>
    <version>0.0.4</version>
</dependency>
```

```yaml
management:
  endpoints:
    web:
      exposure:
        include: "*"
```

```bash
-Xmx1024m
-Xmx512m
-Xmx256m
-Xmx128m
```

得到以下数据：

| 堆内存限制 | 一次查询+EasyExcel导出方案 | 一次查询+FastExcel导出方案 | 键集分页+FastExcel导出方案 | 键集分页+FastExcel导出+生产者消费者模式 |
| ---------- | -------------------------- | -------------------------- | -------------------------- | --------------------------------------- |
| 1024 MB    | CG 3至7秒                  | CG 3至7秒                  | CG < 0.2秒                 | CG < 0.2秒                              |
| 512 MB     | CG 5至7秒                  | CG 6至7秒                  | CG < 0.2秒                 | CG < 0.2秒                              |
| 256 MB     | **OOM**                    | **OOM**                    | CG < 0.5秒                 | CG < 0.5秒                              |
| 128 MB     | **OOM**                    | **OOM**                    | CG < 0.5秒                 | CG约0.5秒                               |





## 总结



从7月15日到7月26日，先后测试普通索引优化、覆盖索引优化、POJO字段优化、Excel表样式删除、流式输出、MyBatis游标优化、游标分页优化、并行查询、FastExcel、生产者消费者模式等多种方案，并最终选择了**覆盖索引优化**+**游标分页**+**基于`LinkedBlockingQueue`的生产者消费者模式**+**`org.dhatim:fastexcel`流式生成**的组合方案，使得该接口从优化前的首字节延迟约32秒、响应时间约35秒到优化后的首字节延迟100毫秒及以下、响应时间6至7秒，同时在内存方面从优化前的频繁CG及200至300 MB的堆内存占用到优化后的128 MB堆内存限制下稳定运行及不超过1秒的CG





## 展望



- 对于本场景下，通过汇总表的形式避免连表查询是优化SQL性能的最好的方案，可以大幅降低SQL执行时间至百毫秒级别
- 可以使用冗余字段和外键约束自动更新字段值，避开连表查询，可作为汇总表的替代
- 如果用户能够接受，CSV是比XLSX更高效的数据格式，在测试中也可以注意到50万行数据通过`StringBuilder`生成CSV数据仅需0.7秒，但需注意文件头和字符转义，并且不能通过EasyExcel生成CSV，否则会有严重的性能损耗
- 在最终的组合优化方案中，可以通过游标小幅度优化响应时间，但可能牺牲部分内存方面的性能
- 在最终的组合优化方案中，如果需要增加生产者线程通过并发查询的方式解除性能瓶颈的话，可考虑在生产者端通过监测队列元素长度来控制队列写入速率
- 在最终的组合优化方案中，接口需要限流并设置`fallback`以避免同时生成多个Excel耗尽系统资源，可采取的方案有：基于`Guava RateLimiter`令牌桶、基于锁等等