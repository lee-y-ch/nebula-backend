package com.filenori.nebula.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class MessageDto {
    @JsonProperty("role")
    private String role;

    @JsonProperty("content")
    private String content;

}
