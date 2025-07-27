package com.crim.web.lab.eleeimplement.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.crim.web.lab.eleeimplement.domain.DeviceType;
import com.crim.web.lab.eleeimplement.mapper.DeviceTypeMapper;
import com.crim.web.lab.eleeimplement.service.DeviceTypeService;
import org.springframework.stereotype.Service;

@Service
public class DeviceTypeServiceImpl extends ServiceImpl<DeviceTypeMapper, DeviceType> implements DeviceTypeService {
}
