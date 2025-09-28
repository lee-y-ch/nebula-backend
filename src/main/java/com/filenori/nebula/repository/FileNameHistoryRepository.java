package com.filenori.nebula.repository;

import com.filenori.nebula.entity.FileNameHistory;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface FileNameHistoryRepository extends MongoRepository<FileNameHistory, String> {

}
