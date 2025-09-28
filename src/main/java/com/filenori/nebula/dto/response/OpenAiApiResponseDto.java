package com.filenori.nebula.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

@Getter
@NoArgsConstructor
public class OpenAiApiResponseDto {

    @JsonProperty("choices")
    private List<ChoiceDto> choices;
}
