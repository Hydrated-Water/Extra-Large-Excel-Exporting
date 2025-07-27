import com.alibaba.excel.EasyExcel;
import com.crim.web.lab.eleeimplement.ELEEApplication;
import com.crim.web.lab.eleeimplement.domain.Device;
import com.crim.web.lab.eleeimplement.domain.DeviceType;
import com.crim.web.lab.eleeimplement.domain.ExcelBO;
import com.crim.web.lab.eleeimplement.domain.Log;
import com.crim.web.lab.eleeimplement.domain.Scope;
import com.crim.web.lab.eleeimplement.domain.Staff;
import com.crim.web.lab.eleeimplement.domain.SubLog;
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
import org.junit.jupiter.api.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.Month;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;


@Slf4j
@SpringBootTest(classes = ELEEApplication.class)
@RunWith(SpringRunner.class)
class MainTest {
    
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
    
    @Test
    public void testMapper() {
        System.out.println(this.staffMapper.selectList(null));
    }
    
    @Test
    public void insertStaff() {
        // 向员工表中插入100条测试数据，工号使用随机的六位递增整数
        int staffNo = 100000;
        String[] firstName = new String[]{"张", "王", "李", "赵", "孙", "周", "吴", "郑", "钱", "黄"};
        String[] lastName = new String[]{"三", "四", "五", "六", "七", "八", "九", "十", "狗蛋", "富贵"};
        
        List<Staff> staffList = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            Staff staff = new Staff();
            staff.setNo(String.valueOf(staffNo));
            staffNo += (int) (Math.random() * 1000);
            staff.setName(firstName[i / 10] + lastName[i % 10]);
            staffList.add(staff);
        }
        System.out.println(this.staffService.saveBatch(staffList));
    }
    
    @Test
    public void insertDeviceType() {
        // 插入测试数据：初洗 酶洗 浸泡 终洗 消毒 干燥 自动机 晨洗 夜洗 检测
        String deviceTypeName[] = {"初洗", "酶洗", "浸泡", "终洗", "消毒", "干燥", "自动机", "晨洗", "夜洗", "检测"};
        List<DeviceType> deviceTypeList = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            DeviceType deviceType = new DeviceType();
            deviceType.setName(deviceTypeName[i]);
            deviceTypeList.add(deviceType);
        }
        System.out.println(deviceTypeService.saveBatch(deviceTypeList));
    }
    
    @Test
    public void insertDevice() {
        List<DeviceType> deviceTypeList = deviceTypeService.list();
        // 插入测试数据50行，设备类型ID随机1-10，对应list中的0-9元素，设备名称为设备类型名称+设备编号，设备编号从1-50
        List<Device> deviceList = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            Device device = new Device();
            device.setNo(((int) (Math.random() * 100000)) + "-" + (i + 1));
            device.setName(deviceTypeList.get(i % 10).getName() + (i + 1));
            device.setTypeId(deviceTypeList.get(i % 10).getId());
            deviceList.add(device);
        }
        System.out.println(deviceService.saveBatch(deviceList));
    }
    
    @Test
    public void insertScope() {
        // 插入测试数据50行，内镜名称为随机前缀加1-50
        String[] prefix = {"胃镜", "肠镜"};
        List<Scope> scopeList = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            Scope scope = new Scope();
            scope.setNo("镜" + ((int) (Math.random() * 100)) + "-" + (i + 1));
            scope.setName(prefix[(int) (Math.random() * 2)] + (i + 1));
            scopeList.add(scope);
        }
        System.out.println(scopeService.saveBatch(scopeList));
    }
    
    @Test
    public void insertLogAndSubLog() {
        // 参数
        int count = 45000;
        LocalDateTime time = LocalDateTime.of(2023, Month.JULY, 16, 0, 0);
        
        Random random = new Random();
        
        List<String> staffNoList = this.staffMapper.selectList(null).stream()
                .map(Staff::getNo).collect(Collectors.toList());
        List<String> scopeNoList = this.scopeMapper.selectList(null).stream()
                .map(Scope::getNo).collect(Collectors.toList());
        List<String> deviceNoList = this.deviceMapper.selectList(null).stream()
                .map(Device::getNo).collect(Collectors.toList());
        
        // 需要插入count行Log，每行Log随机插入2-6个SubLog，每个Log的员工工号随机，每个SubLog的内镜序号，时间总体上随机递增
        for (int i = 0; i < count; i++) {
            Log log = new Log();
            log.setStaffNo(staffNoList.get(random.nextInt(staffNoList.size())));
            log.setTime(time);
            time = time.plusMinutes((random.nextInt(10)));
            if (logMapper.insert(log) < 1) throw new RuntimeException("插入Log失败");
            
            int subLogCount = random.nextInt(5) + 2;
            List<SubLog> subLogList = new ArrayList<>();
            for (int j = 0; j < subLogCount; j++) {
                SubLog subLog = new SubLog();
                subLog.setLogId(log.getId());
                subLog.setScopeNo(scopeNoList.get(random.nextInt(scopeNoList.size())));
                subLog.setDeviceNo(deviceNoList.get(random.nextInt(deviceNoList.size())));
                subLog.setTime(time);
                time = time.plusMinutes(random.nextInt(10));
                subLogList.add(subLog);
            }
            if (!subLogService.saveBatch(subLogList)) throw new RuntimeException("插入子日志失败");
        }
    }
    
    @Test
    public void testMapper2() {
        LocalDateTime start = LocalDateTime.now();
        List datas = logMapper.excelA1();
        LocalDateTime sqlEnd = LocalDateTime.now();
        log.info("第1行：{}", datas.get(0));
        log.info("SQL执行时间：{}", Duration.between(start, sqlEnd));
        log.info("结果集行数：{}", datas.size());
    }
    
    @Test
    public void testExcel() {
        LocalDateTime start = LocalDateTime.now();
        List<ExcelBO> excelBOs = logMapper.excelOrigin();
        LocalDateTime sqlEnd = LocalDateTime.now();
        log.info("结果集行数：{}", excelBOs.size());
        log.info("SQL执行时间：{}", Duration.between(start, sqlEnd));
        
        // 筛选出前100条
        List<ExcelBO> data = excelBOs.stream().limit(100).collect(Collectors.toList());
        System.out.println(data);
        
        EasyExcel.write("excel.xlsx", ExcelBO.class).sheet("日志").doWrite(data);
        
        LocalDateTime excelEnd = LocalDateTime.now();
        log.info("excel耗时：{}", Duration.between(sqlEnd, excelEnd));
        
        LocalDateTime end = LocalDateTime.now();
        log.info("总耗时：{}", Duration.between(start, end));
    }
    
}

