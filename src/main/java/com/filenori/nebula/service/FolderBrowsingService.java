package com.filenori.nebula.service;

import com.filenori.nebula.dto.response.FolderContentsDto;
import com.filenori.nebula.entity.OrganizedFileDocument;
import com.filenori.nebula.repository.OrganizedFileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.types.ObjectId;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class FolderBrowsingService {

    private final OrganizedFileRepository organizedFileRepository;

    public FolderContentsDto getFolderContents(String userId, String paraBucket, String paraFolder) {
        log.info("=== Getting folder contents using Aggregation ===");
        log.info("User: {}, Bucket: {}, Folder: '{}'", userId, paraBucket, paraFolder);

        ObjectId userObjectId = new ObjectId(userId);
        
        String normalizedParent = normalizeFolderPath(paraFolder, paraBucket);
        String bucketLower = paraBucket == null ? "" : paraBucket.trim().toLowerCase(Locale.ROOT);

        // Aggregation을 통한 통합 조회
        String folderPattern;
        
        if (normalizedParent.isEmpty()) {
            // 루트 레벨: 버킷 하위 모든 항목 조회 후 직접 하위만 필터링
            folderPattern = "(?i).*";
        } else {
            // 특정 폴더: 버킷 접두 여부와 관계없이 동일 폴더 및 하위 항목 조회
            folderPattern = "(?i)" + buildFolderPattern(normalizedParent, bucketLower);
        }
        
        log.info("Using aggregation with pattern: '{}' (normalized folder '{}')", folderPattern, normalizedParent);

        List<OrganizedFileDocument> allItems = organizedFileRepository
                .findFolderContentsByAggregation(userObjectId, paraBucket, folderPattern);
        
        log.info("Aggregation returned {} items", allItems.size());
        
        // 결과 분리
        List<FolderContentsDto.FileItemDto> files = new ArrayList<>();
        Set<String> subfolderNames = new HashSet<>();
        Map<String, FolderStats> folderStatsMap = new HashMap<>();
        
        for (OrganizedFileDocument item : allItems) {
            String itemParaFolder = item.getParaFolder();
            String normalizedItemFolder = normalizeFolderPath(itemParaFolder, paraBucket);
            log.info("Processing item: paraFolder='{}' (normalized='{}'), directory={}, file='{}'", 
                    itemParaFolder, normalizedItemFolder, item.isDirectory(), item.getOriginalRelativePath());
            
            if (isDirectFile(normalizedItemFolder, normalizedParent)) {
                // 직접 하위 파일
                files.add(convertToFileItemDto(item));
            } else if (isDirectSubfolder(normalizedItemFolder, normalizedParent)) {
                // 직접 하위 폴더
                String subfolderName = extractSubfolderName(normalizedItemFolder, normalizedParent);
                subfolderNames.add(subfolderName);
                
                // 폴더 통계 업데이트
                FolderStats stats = folderStatsMap.computeIfAbsent(subfolderName, k -> new FolderStats());
                if (item.isDirectory()) {
                    stats.subfolderCount++;
                } else {
                    stats.fileCount++;
                    if (item.getModifiedAt() != null && (stats.lastModified == null || 
                            item.getModifiedAt().compareTo(stats.lastModified) > 0)) {
                        stats.lastModified = item.getModifiedAt();
                    }
                    if (item.getKeywords() != null) {
                        stats.keywords.addAll(item.getKeywords());
                    }
                }
            }
        }
        
        // 하위 폴더 DTO 생성
        List<FolderContentsDto.FolderItemDto> subfolders = subfolderNames.stream()
                .map(name -> {
                    FolderStats stats = folderStatsMap.get(name);
                    String fullPath = normalizedParent.isEmpty() ? name : normalizedParent + "/" + name;

                    return FolderContentsDto.FolderItemDto.builder()
                            .folderName(name)
                            .fullPath(fullPath)
                            .fileCount(stats.fileCount)
                            .subfolderCount(stats.subfolderCount)
                            .lastModified(stats.lastModified)
                            .commonKeywords(stats.keywords.stream().limit(5).collect(Collectors.toList()))
                            .build();
                })
                .sorted(Comparator.comparing(FolderContentsDto.FolderItemDto::getFolderName))
                .collect(Collectors.toList());

        log.info("Final result: {} files, {} subfolders in {}/{}", 
                files.size(), subfolders.size(), paraBucket, paraFolder);

        return FolderContentsDto.builder()
                .folderPath(paraBucket + "/" + normalizedParent)
                .paraBucket(paraBucket)
                .paraFolder(normalizedParent)
                .totalFiles(files.size())
                .totalSubfolders(subfolders.size())
                .files(files)
                .subfolders(subfolders)
                .build();
    }

    private static class FolderStats {
        int fileCount = 0;
        int subfolderCount = 0;
        String lastModified = null;
        Set<String> keywords = new HashSet<>();
    }

    private boolean isDirectFile(String itemParaFolder, String parentFolder) {
        if (parentFolder == null || parentFolder.trim().isEmpty()) {
            // 루트 레벨에서는 직접 파일이 없어야 함
            return false;
        }
        return itemParaFolder.equals(parentFolder);
    }

    private boolean isDirectSubfolder(String subfolderPath, String parentFolder) {
        if (parentFolder == null || parentFolder.trim().isEmpty()) {
            return !subfolderPath.contains("/");
        }

        if (!subfolderPath.startsWith(parentFolder + "/")) {
            return false;
        }

        String relativePath = subfolderPath.substring(parentFolder.length() + 1);
        return !relativePath.contains("/");
    }

    private String extractSubfolderName(String subfolderPath, String parentFolder) {
        if (parentFolder == null || parentFolder.trim().isEmpty()) {
            int slashIndex = subfolderPath.indexOf("/");
            return slashIndex > 0 ? subfolderPath.substring(0, slashIndex) : subfolderPath;
        }

        if (subfolderPath.startsWith(parentFolder + "/")) {
            String relativePath = subfolderPath.substring(parentFolder.length() + 1);
            int slashIndex = relativePath.indexOf("/");
            return slashIndex > 0 ? relativePath.substring(0, slashIndex) : relativePath;
        }
        return subfolderPath;
    }

    private String normalizeFolderPath(String path, String paraBucket) {
        if (path == null || path.trim().isEmpty()) {
            return "";
        }

        String[] rawSegments = path.trim().replace("\\", "/").split("/");
        List<String> segments = new ArrayList<>();
        for (String segment : rawSegments) {
            if (segment != null && !segment.trim().isEmpty()) {
                segments.add(segment.trim().toLowerCase(Locale.ROOT));
            }
        }

        if (segments.isEmpty()) {
            return "";
        }

        if (paraBucket != null && !paraBucket.trim().isEmpty()) {
            String bucketLower = paraBucket.trim().toLowerCase(Locale.ROOT);
            if (!segments.isEmpty() && segments.get(0).equals(bucketLower)) {
                segments.remove(0);
            }
        }

        return String.join("/", segments);
    }

    private String buildFolderPattern(String normalizedFolder, String bucketLower) {
        String escapedNormalized = Pattern.quote(normalizedFolder);

        if (bucketLower == null || bucketLower.isEmpty()) {
            return "^" + escapedNormalized + "(?:$|/.*)";
        }

        String escapedPrefixed = Pattern.quote(bucketLower + "/" + normalizedFolder);
        return "^(?:" + escapedNormalized + "|" + escapedPrefixed + ")(?:$|/.*)";
    }

    private FolderContentsDto.FileItemDto convertToFileItemDto(OrganizedFileDocument file) {
        String displayName = (file.getKoreanFileName() != null && !file.getKoreanFileName().trim().isEmpty())
                ? file.getKoreanFileName()
                : file.getEnglishFileName();

        return FolderContentsDto.FileItemDto.builder()
                .id(file.getId().toString())
                .koreanFileName(file.getKoreanFileName())
                .englishFileName(file.getEnglishFileName())
                .displayName(displayName)
                .originalRelativePath(file.getOriginalRelativePath())
                .sizeBytes(file.getSizeBytes())
                .modifiedAt(file.getModifiedAt())
                .keywords(file.getKeywords() != null ? file.getKeywords() : Collections.emptyList())
                .reason(file.getReason())
                .isDevelopment(file.isDevelopment())
                .build();
    }

    public List<String> getFolderBreadcrumb(String paraBucket, String paraFolder) {
        List<String> breadcrumb = new ArrayList<>();
        breadcrumb.add(paraBucket);
        
        if (paraFolder != null && !paraFolder.trim().isEmpty()) {
            String[] folders = paraFolder.split("/");
            for (String folder : folders) {
                if (!folder.trim().isEmpty()) {
                    breadcrumb.add(folder.trim());
                }
            }
        }
        
        return breadcrumb;
    }
}
