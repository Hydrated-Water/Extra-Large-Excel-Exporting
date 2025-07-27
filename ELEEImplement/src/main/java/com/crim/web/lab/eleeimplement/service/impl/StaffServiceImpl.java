package com.crim.web.lab.eleeimplement.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.crim.web.lab.eleeimplement.domain.Staff;
import com.crim.web.lab.eleeimplement.mapper.StaffMapper;
import com.crim.web.lab.eleeimplement.service.StaffService;
import org.springframework.stereotype.Service;

@Service
public class StaffServiceImpl extends ServiceImpl<StaffMapper, Staff> implements StaffService {
}
