package com.filenori.nebula.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class ChoiceDto {

    @JsonProperty("message")
    private MessageDto message;
}
