package com.example.mapper;

import org.apache.ibatis.annotations.Select;

public interface UserMapper {
    String selectUser(String userId);

    int updateUserName(String userId);

    @Select("SELECT COUNT(*) FROM TB_USER")
    int countUsers();
}
