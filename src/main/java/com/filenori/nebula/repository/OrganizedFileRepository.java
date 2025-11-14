package com.filenori.nebula.repository;

import com.filenori.nebula.entity.OrganizedFileDocument;
import org.bson.types.ObjectId;
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
}
