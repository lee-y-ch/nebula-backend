package com.filenori.nebula.repository;

import com.filenori.nebula.entity.OrganizedFileDocument;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface OrganizedFileRepository extends MongoRepository<OrganizedFileDocument, ObjectId> {

    List<OrganizedFileDocument> findByUserIdAndOriginalRelativePathIn(ObjectId userId, Collection<String> originalRelativePaths);
}
