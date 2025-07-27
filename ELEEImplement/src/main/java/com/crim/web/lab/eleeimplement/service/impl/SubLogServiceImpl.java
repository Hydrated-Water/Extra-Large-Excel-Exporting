package com.crim.web.lab.eleeimplement.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.crim.web.lab.eleeimplement.domain.SubLog;
import com.crim.web.lab.eleeimplement.mapper.SubLogMapper;
import com.crim.web.lab.eleeimplement.service.SubLogService;
import org.springframework.stereotype.Service;

@Service
public class SubLogServiceImpl extends ServiceImpl<SubLogMapper, SubLog> implements SubLogService {
}
