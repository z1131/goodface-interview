package com.deepknow.goodface.interview.repo.mapper;

import com.deepknow.goodface.interview.domain.session.model.InterviewSession;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface InterviewSessionMapper {
    int insert(InterviewSession session);
    InterviewSession findById(@Param("id") String id);
    int updateStatus(@Param("id") String id, @Param("status") String status, @Param("endTime") java.time.LocalDateTime endTime);
}