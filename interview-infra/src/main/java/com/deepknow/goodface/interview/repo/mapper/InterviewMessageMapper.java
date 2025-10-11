package com.deepknow.goodface.interview.repo.mapper;

import com.deepknow.goodface.interview.domain.session.model.InterviewMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface InterviewMessageMapper {
    int insert(InterviewMessage message);
    List<InterviewMessage> listBySession(@Param("sessionId") String sessionId);
}