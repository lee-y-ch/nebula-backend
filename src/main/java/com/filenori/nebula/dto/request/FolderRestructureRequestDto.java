package com.filenori.nebula.dto.request;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@AllArgsConstructor
@NoArgsConstructor
public class FolderRestructureRequestDto {
    private String userId;
    private String paraBucket; // "Projects", "Areas", "Resources", "Archive"
}