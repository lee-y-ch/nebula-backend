package com.filenori.nebula.repository;

import com.filenori.nebula.entity.OrganizedFileDocument;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.Aggregation;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface OrganizedFileRepository extends MongoRepository<OrganizedFileDocument, ObjectId> {

    List<OrganizedFileDocument> findByUserIdAndOriginalRelativePathIn(ObjectId userId, Collection<String> originalRelativePaths);

    List<OrganizedFileDocument> findByUserIdAndParaBucket(ObjectId userId, String paraBucket);

    @Query(value = "{ 'userId': ?0, 'paraBucket': ?1 }", fields = "{ 'paraFolder': 1 }")
    List<OrganizedFileDocument> findFoldersByUserIdAndBucket(ObjectId userId, String paraBucket);

    List<OrganizedFileDocument> findByUserIdAndParaBucketAndParaFolder(ObjectId userId, String paraBucket, String paraFolder);

    @Query(value = "{ 'userId': ?0, 'paraBucket': ?1 }", 
           fields = "{ 'paraFolder': 1, 'koreanFileName': 1, 'englishFileName': 1, 'keywords': 1, 'directory': 1 }")
    List<OrganizedFileDocument> findFolderAnalysisDataByUserIdAndBucket(ObjectId userId, String paraBucket);

    // 특정 폴더의 직접 하위 파일들만 조회 (하위 폴더 제외)
    @Query("{ 'userId': ?0, 'paraBucket': ?1, 'paraFolder': ?2, 'directory': false }")
    List<OrganizedFileDocument> findFilesByUserIdAndBucketAndFolder(ObjectId userId, String paraBucket, String paraFolder);

    // Aggregation으로 폴더 내용 조회 (단순화)
    @Aggregation(pipeline = {
        "{ $match: { 'userId': ?0, 'paraBucket': ?1, 'paraFolder': { $regex: ?2 } } }",
        "{ $sort: { 'directory': -1, 'koreanFileName': 1, 'englishFileName': 1 } }"
    })
    List<OrganizedFileDocument> findFolderContentsByAggregation(ObjectId userId, String paraBucket, String folderPattern);

    // OrganizedFileService용 추가 메서드들
    List<OrganizedFileDocument> findByUserId(ObjectId userId);
    
    boolean existsByIdAndUserId(ObjectId fileId, ObjectId userId);
}
