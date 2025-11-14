package com.filenori.nebula.service;

import com.filenori.nebula.dto.request.FolderRestructureRequestDto;
import com.filenori.nebula.dto.response.FolderAnalysisDto;
import com.filenori.nebula.dto.response.FolderRestructureResponseDto;
import com.filenori.nebula.entity.OrganizedFileDocument;
import com.filenori.nebula.repository.OrganizedFileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.types.ObjectId;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class FolderRestructureService {

    private final OrganizedFileRepository organizedFileRepository;
    private final OpenAiService openAiService;

    public Mono<FolderRestructureResponseDto> analyzeFolderStructure(FolderRestructureRequestDto requestDto) {
        log.info("=== Starting folder structure analysis ===");
        log.info("User: {}, PARA Bucket: {}", requestDto.getUserId(), requestDto.getParaBucket());

        ObjectId userId = new ObjectId(requestDto.getUserId());
        String paraBucket = requestDto.getParaBucket();

        // 1. 해당 PARA 버킷의 모든 폴더 분석 데이터 조회
        List<FolderAnalysisDto> folderAnalyses = analyzeFoldersByBucket(userId, paraBucket);
        log.info("Found {} folders for analysis in bucket: {}", folderAnalyses.size(), paraBucket);

        if (folderAnalyses.size() < 2) {
            log.info("Not enough folders to suggest merging");
            return Mono.just(new FolderRestructureResponseDto(
                    Collections.emptyList(), 
                    "폴더가 충분하지 않아 통합 제안을 할 수 없습니다."));
        }

        // 2. GPT에게 폴더 통합 제안 요청
        return requestFolderRestructureSuggestion(folderAnalyses, paraBucket)
                .doOnNext(response -> log.info("Received folder restructure suggestions: {}", 
                        response.getMergeSuggestions().size()))
                .onErrorResume(error -> {
                    log.error("Failed to get folder restructure suggestions", error);
                    return Mono.just(new FolderRestructureResponseDto(
                            Collections.emptyList(),
                            "폴더 구조 분석 중 오류가 발생했습니다: " + error.getMessage()));
                });
    }

    private List<FolderAnalysisDto> analyzeFoldersByBucket(ObjectId userId, String paraBucket) {
        // PARA 버킷의 모든 파일 데이터 조회
        List<OrganizedFileDocument> documents = organizedFileRepository
                .findFolderAnalysisDataByUserIdAndBucket(userId, paraBucket);

        // 폴더별로 그룹핑
        Map<String, List<OrganizedFileDocument>> folderGroups = documents.stream()
                .filter(doc -> doc.getParaFolder() != null && !doc.getParaFolder().trim().isEmpty())
                .collect(Collectors.groupingBy(OrganizedFileDocument::getParaFolder));

        List<FolderAnalysisDto> analyses = new ArrayList<>();
        
        for (Map.Entry<String, List<OrganizedFileDocument>> entry : folderGroups.entrySet()) {
            String folderName = entry.getKey();
            List<OrganizedFileDocument> folderFiles = entry.getValue();

            // 폴더 분석
            FolderAnalysisDto analysis = buildFolderAnalysis(folderName, folderFiles);
            analyses.add(analysis);
        }

        log.info("Analyzed {} folders in bucket {}", analyses.size(), paraBucket);
        return analyses;
    }

    private FolderAnalysisDto buildFolderAnalysis(String folderName, List<OrganizedFileDocument> files) {
        int fileCount = 0;
        int subfolderCount = 0;
        List<String> sampleFileNames = new ArrayList<>();
        Set<String> allKeywords = new HashSet<>();

        for (OrganizedFileDocument file : files) {
            if (file.isDirectory()) {
                subfolderCount++;
            } else {
                fileCount++;
                
                // 대표 파일명 수집 (한글명 우선, 없으면 영문명)
                if (sampleFileNames.size() < 5) {
                    String displayName = file.getKoreanFileName() != null && !file.getKoreanFileName().trim().isEmpty()
                            ? file.getKoreanFileName()
                            : file.getEnglishFileName();
                    if (displayName != null) {
                        sampleFileNames.add(displayName);
                    }
                }
                
                // 키워드 수집
                if (file.getKeywords() != null) {
                    allKeywords.addAll(file.getKeywords());
                }
            }
        }

        // 공통 키워드 상위 5개 추출
        List<String> commonKeywords = allKeywords.stream()
                .limit(5)
                .collect(Collectors.toList());

        // 폴더 용도 추론 (간단한 휴리스틱)
        String folderPurpose = inferFolderPurpose(folderName, commonKeywords, fileCount, subfolderCount);

        return FolderAnalysisDto.builder()
                .folderName(folderName)
                .fileCount(fileCount)
                .subfolderCount(subfolderCount)
                .sampleFileNames(sampleFileNames)
                .commonKeywords(commonKeywords)
                .folderPurpose(folderPurpose)
                .build();
    }

    private String inferFolderPurpose(String folderName, List<String> keywords, int fileCount, int subfolderCount) {
        StringBuilder purpose = new StringBuilder();
        purpose.append(String.format("폴더명: %s, ", folderName));
        purpose.append(String.format("파일 %d개, 하위폴더 %d개", fileCount, subfolderCount));
        
        if (!keywords.isEmpty()) {
            purpose.append(String.format(" | 주요키워드: %s", String.join(", ", keywords)));
        }
        
        return purpose.toString();
    }

    private Mono<FolderRestructureResponseDto> requestFolderRestructureSuggestion(
            List<FolderAnalysisDto> folderAnalyses, String paraBucket) {
        
        String systemPrompt = buildSystemPrompt();
        String userPrompt = buildUserPrompt(folderAnalyses, paraBucket);

        log.info("Sending folder restructure request to OpenAI");
        log.debug("System prompt: {}", systemPrompt);
        log.debug("User prompt: {}", userPrompt);

        return openAiService.requestFolderRestructureSuggestion(userPrompt, systemPrompt);
    }

    private String buildSystemPrompt() {
        return """
                You are a file organization expert specializing in P.A.R.A. methodology folder structure optimization.
                
                TASK: Analyze folder structures and suggest mergers to reduce redundancy and improve organization.
                
                RULES:
                1. Look for folders that have similar purposes or overlapping content
                2. Consider folder names, file counts, and keywords to identify merge candidates
                3. Suggest meaningful new names for merged folders
                4. Only suggest merges that make logical sense
                5. Provide clear rationale for each suggestion
                
                OUTPUT: JSON response with merge suggestions following the schema.
                """;
    }

    private String buildUserPrompt(List<FolderAnalysisDto> folderAnalyses, String paraBucket) {
        StringBuilder prompt = new StringBuilder();
        prompt.append(String.format("PARA Bucket: %s\n\n", paraBucket));
        prompt.append("현재 폴더 구조 분석:\n\n");

        for (int i = 0; i < folderAnalyses.size(); i++) {
            FolderAnalysisDto analysis = folderAnalyses.get(i);
            prompt.append(String.format("%d. 폴더명: %s\n", i + 1, analysis.getFolderName()));
            prompt.append(String.format("   - 파일 수: %d개, 하위폴더 수: %d개\n", 
                    analysis.getFileCount(), analysis.getSubfolderCount()));
            
            if (!analysis.getSampleFileNames().isEmpty()) {
                prompt.append(String.format("   - 대표 파일들: %s\n", 
                        String.join(", ", analysis.getSampleFileNames())));
            }
            
            if (!analysis.getCommonKeywords().isEmpty()) {
                prompt.append(String.format("   - 주요 키워드: %s\n", 
                        String.join(", ", analysis.getCommonKeywords())));
            }
            
            prompt.append(String.format("   - 용도: %s\n\n", analysis.getFolderPurpose()));
        }

        prompt.append("위 폴더들 중에서 유사한 용도나 중복되는 내용을 가진 폴더들을 찾아 통합 제안을 해주세요. ");
        prompt.append("각 제안에 대해 명확한 근거를 제시해주세요.");

        return prompt.toString();
    }

    public Mono<String> applyFolderRestructure(String userId, String paraBucket, 
                                               FolderRestructureResponseDto.MergeSuggestion suggestion) {
        log.info("=== Applying folder restructure ===");
        log.info("User: {}, Bucket: {}, Merging {} folders into {}", 
                userId, paraBucket, suggestion.getSourceFolders().size(), suggestion.getTargetFolder());

        ObjectId userObjectId = new ObjectId(userId);

        // 소스 폴더들의 파일들을 타겟 폴더로 이동
        for (String sourceFolder : suggestion.getSourceFolders()) {
            List<OrganizedFileDocument> filesToMove = organizedFileRepository
                    .findByUserIdAndParaBucketAndParaFolder(userObjectId, paraBucket, sourceFolder);

            for (OrganizedFileDocument file : filesToMove) {
                // 새로운 폴더 구조로 업데이트
                OrganizedFileDocument updatedFile = OrganizedFileDocument.builder()
                        .id(file.getId())
                        .userId(file.getUserId())
                        .baseDirectory(file.getBaseDirectory())
                        .originalRelativePath(file.getOriginalRelativePath())
                        .directory(file.isDirectory())
                        .development(file.isDevelopment())
                        .sizeBytes(file.getSizeBytes())
                        .modifiedAt(file.getModifiedAt())
                        .keywords(file.getKeywords())
                        .koreanFileName(file.getKoreanFileName())
                        .englishFileName(file.getEnglishFileName())
                        .paraBucket(file.getParaBucket())
                        .paraFolder(suggestion.getSuggestedName())  // 새 폴더명으로 변경
                        .paraFullPath(paraBucket + "/" + suggestion.getSuggestedName())
                        .reason(file.getReason())
                        .createdAt(file.getCreatedAt())
                        .build();

                organizedFileRepository.save(updatedFile);
            }
            
            log.info("Moved {} files from folder '{}' to '{}'", 
                    filesToMove.size(), sourceFolder, suggestion.getSuggestedName());
        }

        return Mono.just(String.format("폴더 재구성이 완료되었습니다. %d개 폴더가 '%s'로 통합되었습니다.",
                suggestion.getSourceFolders().size(), suggestion.getSuggestedName()));
    }
}