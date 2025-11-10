package com.filenori.nebula.controller;

import com.filenori.nebula.dto.response.ParaFolderTreeResponseDto;
import com.filenori.nebula.service.OrganizedFileService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/para")
@RequiredArgsConstructor
public class OrganizedFileController {

    private final OrganizedFileService organizedFileService;

    @GetMapping("/folders")
    public ResponseEntity<ParaFolderTreeResponseDto> getFolders(@RequestParam String userId,
                                                                @RequestParam String bucket,
                                                                @RequestParam(required = false) String path) {
        ParaFolderTreeResponseDto response = organizedFileService.getFolders(userId, bucket, path);
        return ResponseEntity.ok(response);
    }
}
