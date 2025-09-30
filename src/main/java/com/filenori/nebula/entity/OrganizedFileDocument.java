package com.filenori.nebula.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "organized_files")
@CompoundIndexes({
        @CompoundIndex(name = "idx_user_bucket_folder", def = "{ 'userId': 1, 'paraBucket': 1, 'paraFolder': 1 }"),
        @CompoundIndex(name = "idx_user_original_path", def = "{ 'userId': 1, 'originalRelativePath': 1 }", unique = true)
})
public class OrganizedFileDocument {

    @Id
    private ObjectId id;

    private ObjectId userId;
    private String baseDirectory;
    private String originalRelativePath;
    private boolean directory;
    private boolean development;
    private long sizeBytes;
    private String modifiedAt;
    private List<String> keywords;

    private String koreanFileName;
    private String englishFileName;
    private String paraBucket;
    private String paraFolder;
    private String paraFullPath;
    private String reason;

    private Instant createdAt;
}
