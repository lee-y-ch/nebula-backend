package com.filenori.nebula.entity;

import lombok.Getter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@Document(collation = "file_name_history") //MongoDB 컬렉션 이름 지정
public class FileNameHistory {

    @Id
    private String id;

    private List<String> originalKeywords;

    private String generatedFileName;

    private LocalDateTime createdAt;

    public FileNameHistory(List<String> originalKeywords, String generatedFileName){
        this.originalKeywords = originalKeywords;
        this.generatedFileName = generatedFileName;
        this.createdAt = LocalDateTime.now();
    }

}
