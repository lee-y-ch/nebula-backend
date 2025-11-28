package com.filenori.nebula.service;

import com.filenori.nebula.dto.request.OrganizedFileSearchRequestDto;
import com.filenori.nebula.dto.response.OrganizedFileSearchResponseDto;
import com.filenori.nebula.dto.response.OrganizedFileSearchResponseDto.SearchResultDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrganizedFileSearchService {

    private static final String COLLECTION_NAME = "organized_files";

    private final MongoTemplate mongoTemplate;
    private final SageMakerEmbeddingService embeddingService;

    @Value("${vectorSearch.indexName:organized_files_embedding_index}")
    private String vectorIndexName;

    @Value("${vectorSearch.embeddingPath:embedding}")
    private String embeddingFieldPath;

    @Value("${vectorSearch.defaultNumCandidates:120}")
    private int defaultNumCandidates;

    @Value("${vectorSearch.defaultMinScore:0.45}")
    private double defaultMinScore;

    public OrganizedFileSearchResponseDto search(OrganizedFileSearchRequestDto requestDto) {
        validateRequest(requestDto);

        ObjectId userId = new ObjectId(requestDto.getUserId());
        String query = requestDto.getQuery().trim();
        int limit = Optional.ofNullable(requestDto.getLimit()).filter(l -> l > 0 && l <= 200).orElse(20);
        int numCandidates = Optional.ofNullable(requestDto.getNumCandidates())
                .filter(value -> value >= limit)
                .orElse(Math.max(defaultNumCandidates, limit * 4));
        double minScore = Optional.ofNullable(requestDto.getMinScore())
                .filter(score -> score >= 0 && score <= 1)
                .orElse(defaultMinScore);

        List<Double> queryVector = embeddingService.embedText(query)
                .orElseThrow(() -> new IllegalStateException("임베딩을 생성할 수 없습니다. SageMaker 설정을 확인해주세요."));

        long started = System.currentTimeMillis();
        List<SearchResultDto> results = runVectorSearch(userId, queryVector, limit, numCandidates, minScore);
        long tookMs = System.currentTimeMillis() - started;

        return OrganizedFileSearchResponseDto.builder()
                .results(results)
                .total(results.size())
                .tookMs(tookMs)
                .build();
    }

    private void validateRequest(OrganizedFileSearchRequestDto requestDto) {
        if (!StringUtils.hasText(requestDto.getUserId())) {
            throw new IllegalArgumentException("userId는 필수입니다.");
        }
        if (!ObjectId.isValid(requestDto.getUserId())) {
            throw new IllegalArgumentException("userId 형식이 올바르지 않습니다.");
        }
        if (!StringUtils.hasText(requestDto.getQuery())) {
            throw new IllegalArgumentException("query는 비어 있을 수 없습니다.");
        }
    }

    private List<SearchResultDto> runVectorSearch(ObjectId userId,
                                                  List<Double> queryVector,
                                                  int limit,
                                                  int numCandidates,
                                                  double minScore) {
        List<Document> pipeline = new ArrayList<>();

        Document filter = new Document("userId", userId);

        Document vectorSearchStage = new Document("$vectorSearch",
                new Document("index", vectorIndexName)
                        .append("path", embeddingFieldPath)
                        .append("queryVector", queryVector)
                        .append("numCandidates", numCandidates)
                        .append("limit", limit)
                        .append("filter", filter)
        );

        pipeline.add(vectorSearchStage);
        pipeline.add(new Document("$addFields", new Document("similarity",
                new Document("$meta", "vectorSearchScore"))));
        pipeline.add(new Document("$match", new Document("similarity",
                new Document("$gte", minScore))));
        pipeline.add(new Document("$project",
                new Document("koreanFileName", 1)
                        .append("englishFileName", 1)
                        .append("originalRelativePath", 1)
                        .append("paraBucket", 1)
                        .append("paraFolder", 1)
                        .append("keywords", 1)
                        .append("reason", 1)
                        .append("sizeBytes", 1)
                        .append("modifiedAt", 1)
                        .append("similarity", 1)
        ));

        List<SearchResultDto> results = new ArrayList<>();
        mongoTemplate.getCollection(COLLECTION_NAME)
                .aggregate(pipeline)
                .forEach(document -> results.add(mapToDto(document)));

        return results;
    }

    private SearchResultDto mapToDto(Document document) {
        List<String> keywords = document.getList("keywords", String.class);
        Double similarity = document.getDouble("similarity");

        return SearchResultDto.builder()
                .id(document.getObjectId("_id").toHexString())
                .koreanFileName(document.getString("koreanFileName"))
                .englishFileName(document.getString("englishFileName"))
                .originalRelativePath(document.getString("originalRelativePath"))
                .paraBucket(document.getString("paraBucket"))
                .paraFolder(document.getString("paraFolder"))
                .reason(document.getString("reason"))
                .keywords(CollectionUtils.isEmpty(keywords) ? List.of() : keywords)
                .similarity(similarity != null ? similarity : 0.0)
                .sizeBytes(document.getLong("sizeBytes") != null ? document.getLong("sizeBytes") : 0L)
                .modifiedAt(document.getString("modifiedAt"))
                .build();
    }
}

