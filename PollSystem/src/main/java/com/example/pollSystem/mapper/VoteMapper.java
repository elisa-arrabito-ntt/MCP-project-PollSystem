package com.example.pollSystem.mapper;

import com.example.pollSystem.dto.response.VoteResponseDto;
import com.example.pollSystem.entity.Vote;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface VoteMapper {

    @Mapping(source = "option.id", target = "optionId") // ricavo il campo optionId da option.id
    VoteResponseDto toResponseDto(Vote vote);
}
