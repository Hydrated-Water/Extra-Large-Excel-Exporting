package com.crim.web.lab.eleeimplement.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.crim.web.lab.eleeimplement.domain.Device;
import com.crim.web.lab.eleeimplement.mapper.DeviceMapper;
import com.crim.web.lab.eleeimplement.service.DeviceService;
import org.springframework.stereotype.Service;

@Service
public class DeviceServiceImpl extends ServiceImpl<DeviceMapper, Device> implements DeviceService {
}
