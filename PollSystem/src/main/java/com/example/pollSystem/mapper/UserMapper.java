package com.example.pollSystem.mapper;

import com.example.pollSystem.dto.request.RegistrationRequestDto;
import com.example.pollSystem.entity.User;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface UserMapper {

    @Mapping(target = "id", ignore = true)
    User toEntity(RegistrationRequestDto request); // poi il service fara encoding della password
}