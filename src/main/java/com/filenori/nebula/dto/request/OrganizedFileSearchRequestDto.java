package com.filenori.nebula.dto.request;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class OrganizedFileSearchRequestDto {
    private String userId;
    private String query;
    private Integer limit;
    private Integer numCandidates;
    private Double minScore;
}

