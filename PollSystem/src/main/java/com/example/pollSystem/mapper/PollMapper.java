package com.example.pollSystem.mapper;

import com.example.pollSystem.dto.request.CreatePollRequestDto;
import com.example.pollSystem.dto.response.PollDetailsResponseDto;
import com.example.pollSystem.dto.response.PollResponseDto;
import com.example.pollSystem.dto.response.WinnerOptionResponseDto;
import com.example.pollSystem.entity.Option;
import com.example.pollSystem.entity.Poll;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

@Mapper(componentModel = "spring", uses = {OptionMapper.class}) // per convertire Option in PollDetailsResponseDto
public interface PollMapper {

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "owner", ignore = true)
    @Mapping(target = "status", ignore = true)
    Poll toEntity(CreatePollRequestDto dto);

    @Mapping(source = "owner.username", target = "owner")
    PollResponseDto toResponseDto(Poll poll);

    @Mapping(source = "poll.owner.username", target = "owner")
    @Mapping(source = "poll.id", target = "id")
    @Mapping(source = "poll.expiresAt", target = "expiresAt")
    @Mapping(source = "poll.status", target = "status")
    @Mapping(source = "options", target = "options")
    @Mapping(target = "winner", expression = "java(toWinnerDto(poll))") // mapping custom per il campo winner
    PollDetailsResponseDto toDetailsResponseDto(Poll poll, List<Option> options);

    // default utile per inserire metodi di utilità in un'interfaccia
    // questo metodo non deve essere generato da mapstruct ma rimane disponibile "dentro" l'implementazione che MapStruct genera
    default WinnerOptionResponseDto toWinnerDto(Poll poll) {
        if (poll.getWinnerOptionId() == null || poll.getWinnerPercent() == null) {
            return null;
        }
        return WinnerOptionResponseDto.builder()
                .pollId(poll.getId())
                .optionId(poll.getWinnerOptionId())
                .percentOfWiner(poll.getWinnerPercent())
                .build();
    }
}