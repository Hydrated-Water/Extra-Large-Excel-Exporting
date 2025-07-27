package com.crim.web.lab.eleeimplement.controller;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.ExcelWriter;
import com.alibaba.excel.support.ExcelTypeEnum;
import com.alibaba.excel.write.metadata.WriteSheet;
import com.crim.web.lab.eleeimplement.domain.ExcelBO;
import com.crim.web.lab.eleeimplement.mapper.DeviceMapper;
import com.crim.web.lab.eleeimplement.mapper.DeviceTypeMapper;
import com.crim.web.lab.eleeimplement.mapper.LogMapper;
import com.crim.web.lab.eleeimplement.mapper.ScopeMapper;
import com.crim.web.lab.eleeimplement.mapper.StaffMapper;
import com.crim.web.lab.eleeimplement.mapper.SubLogMapper;
import com.crim.web.lab.eleeimplement.service.DeviceService;
import com.crim.web.lab.eleeimplement.service.DeviceTypeService;
import com.crim.web.lab.eleeimplement.service.LogService;
import com.crim.web.lab.eleeimplement.service.ScopeService;
import com.crim.web.lab.eleeimplement.service.StaffService;
import com.crim.web.lab.eleeimplement.service.SubLogService;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.cursor.Cursor;
import org.dhatim.fastexcel.Workbook;
import org.dhatim.fastexcel.Worksheet;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;


@Slf4j
@RestController
public class HelloController {
    
    @Autowired
    private StaffService staffService;
    
    @Autowired
    private StaffMapper staffMapper;
    
    @Autowired
    private ScopeService scopeService;
    
    @Autowired
    private ScopeMapper scopeMapper;
    
    @Autowired
    private DeviceService deviceService;
    
    @Autowired
    private DeviceMapper deviceMapper;
    
    @Autowired
    private DeviceTypeService deviceTypeService;
    
    @Autowired
    private DeviceTypeMapper deviceTypeMapper;
    
    @Autowired
    private LogService logService;
    
    @Autowired
    private LogMapper logMapper;
    
    @Autowired
    private SubLogService subLogService;
    
    @Autowired
    private SubLogMapper subLogMapper;
    
    @RequestMapping("/")
    public String hello() {
        return "Hello World!";
    }
    
    
    /**
     * 模拟文件下载限流
     * <p>
     * 由于未写入文件大小，Chrome浏览器始终显示“正在恢复”
     * <p>
     * Chrome浏览器可以容许120秒的间隔
     */
    @GetMapping("/limit")
    public void limit(HttpServletResponse response,
                      @RequestParam(required = false, defaultValue = "3") Integer size,
                      @RequestParam(required = false, defaultValue = "0") Integer interval,
                      @RequestParam(required = false, defaultValue = "0") Integer delay) throws IOException {
        if (size <= 0 || interval < 0) response.sendError(HttpServletResponse.SC_BAD_REQUEST);
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment;filename=limit.txt");
        response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
        try (OutputStream outputStream = response.getOutputStream()) {
            if (delay > 0)
                Thread.sleep(delay);
            for (int i = 0; i < size; i++) {
                outputStream.write("哈基米哦南北绿豆".getBytes(StandardCharsets.UTF_8));
                outputStream.flush();
                if (interval > 0) {
                    Thread.sleep(interval);
                }
            }
        }
        catch (Exception e) {
            log.error(e.getMessage(), e);
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }
    }
    
    
    /**
     * 导出Excel数据，原始实现
     */
    @GetMapping("/excel/origin")
    public void exportExcelOrigin(HttpServletResponse response) throws IOException {
        
        LocalDateTime start = LocalDateTime.now();
        //List<ExcelBO> excelBOs = logMapper.excelOrigin();
        List<ExcelBO> excelBOs = logMapper.excelA1();
        LocalDateTime sqlEnd = LocalDateTime.now();
        log.info("结果集行数：{}", excelBOs.size());
        log.info("SQL执行时间：{}", Duration.between(start, sqlEnd));
        
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment;filename=excel.xlsx");
        response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
        
        
        try {
            OutputStream outputStream = response.getOutputStream();
            
            EasyExcel.write(outputStream, ExcelBO.class)
                    .excelType(ExcelTypeEnum.XLSX)
                    //.registerWriteHandler(new ExcelWidthStyleStrategy())
                    .sheet("日志")
                    .doWrite(excelBOs);
            
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
    
    
    /**
     * 导出Excel数据，尽快发送响应头
     */
    @GetMapping("/excel/quick")
    public void exportExcelQuick(HttpServletResponse response) throws IOException {
        try {
            
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment;filename=excel.xlsx");
            response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
            
            response.flushBuffer();
            OutputStream outputStream = response.getOutputStream();
            outputStream.flush();
            
            ExcelWriter excelWriter = EasyExcel.write(outputStream, ExcelBO.class)
                    .excelType(ExcelTypeEnum.XLSX).build();
            WriteSheet writeSheet = EasyExcel.writerSheet("日志").build();
            ArrayList<ExcelBO> head = new ArrayList<>();
            head.add(new ExcelBO());
            excelWriter.write(head, writeSheet);
            outputStream.flush();
            
            
            LocalDateTime start = LocalDateTime.now();
            List<ExcelBO> excelBOs = logMapper.excelA1();
            LocalDateTime sqlEnd = LocalDateTime.now();
            log.info("结果集行数：{}", excelBOs.size());
            log.info("SQL执行时间：{}", Duration.between(start, sqlEnd));
            
            
            excelWriter.write(excelBOs, writeSheet);
            
            outputStream.flush();
            excelWriter.finish();
            excelWriter.close();
            
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
    
    
    /**
     * 导出Excel数据，FastExcel实现
     */
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
    
    
    /**
     * 导出CSV数据，原始实现，Excel生成时间低至0.7秒
     */
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
    
    
    /**
     * 游标测试
     */
    @GetMapping("/cursor/test")
    @Transactional
    public String cursorTest() throws IOException {
        
        String returnValue = "Hello World!";
        
        LocalDateTime start = LocalDateTime.now();
        try (Cursor<ExcelBO> excelBOCursor = logMapper.excelC1()) {
            
            LocalDateTime sqlEnd = LocalDateTime.now();
            log.info("SQL执行时间：{}", Duration.between(start, sqlEnd));
            
            int count = 0;
            for (ExcelBO excelBO : excelBOCursor) {
                if (excelBO != null)
                    count++;
            }
            returnValue += "\n行数：" + count;
            
            LocalDateTime excelEnd = LocalDateTime.now();
            log.info("excel耗时：{}", Duration.between(sqlEnd, excelEnd));
            LocalDateTime end = LocalDateTime.now();
            log.info("总耗时：{}", Duration.between(start, end));
        }
        catch (Exception e) {
            log.error(e.getMessage(), e);
        }
        return returnValue;
    }
    
    
    /**
     * 导出CSV数据，使用游标实现
     */
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
    
    
    /**
     * 键集分页测试，简单遍历
     */
    @GetMapping("/page/test/simple")
    @Transactional
    public String pageTestSimple() throws IOException {
        
        String returnValue = "Hello World!";
        
        long startId = 1;
        long batchSize = 500000;
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
            if (duration.compareTo(minDuration) < 0 && excelBOList.size() == batchSize) minDuration = duration;
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
        log.info("行数：{}", count);
        
        returnValue += "\n行数：" + count;
        return returnValue;
    }
    
    
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
    
    
    /**
     * 键集分页与FastExcel集成，简单遍历
     */
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
    
    
    /**
     * 键集分页与FastExcel集成，使用生产者消费者模式
     */
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
}
