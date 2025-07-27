package com.crim.web.lab.eleeimplement.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.crim.web.lab.eleeimplement.domain.Log;
import com.crim.web.lab.eleeimplement.mapper.LogMapper;
import com.crim.web.lab.eleeimplement.service.LogService;
import org.springframework.stereotype.Service;

@Service
public class LogServiceImpl extends ServiceImpl<LogMapper, Log> implements LogService {
}
