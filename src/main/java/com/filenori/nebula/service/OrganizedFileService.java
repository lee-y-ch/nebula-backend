package com.filenori.nebula.service;

import com.filenori.nebula.dto.response.ParaFolderNodeDto;
import com.filenori.nebula.dto.response.ParaFolderTreeResponseDto;
import com.filenori.nebula.entity.OrganizedFileDocument;
import com.filenori.nebula.repository.OrganizedFileRepository;
import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class OrganizedFileService {

    private static final Set<String> SUPPORTED_BUCKETS = Set.of("projects", "areas", "resources", "archive");

    private final OrganizedFileRepository organizedFileRepository;

    public ParaFolderTreeResponseDto getFolders(String userIdRaw, String bucketRaw, String pathRaw) {
        ObjectId userId = parseUserId(userIdRaw);
        String bucket = normalizeBucket(bucketRaw);
        String bucketKey = bucket.toLowerCase(Locale.ROOT);
        List<String> pathSegments = normalizePath(pathRaw, bucketKey);

        List<OrganizedFileDocument> documents = organizedFileRepository
                .findByUserIdAndParaBucket(userId, bucket);

        if (pathSegments.isEmpty()) {
            List<ParaFolderNodeDto> folders = buildRootNodes(documents);
            return new ParaFolderTreeResponseDto(bucket, "", folders);
        }

        List<ParaFolderNodeDto> folders = buildChildNodes(pathSegments, documents);
        return new ParaFolderTreeResponseDto(bucket, String.join("/", pathSegments), folders);
    }

    private ObjectId parseUserId(String userIdRaw) {
        if (userIdRaw == null || userIdRaw.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "userId is required");
        }

        try {
            return new ObjectId(userIdRaw.trim());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "userId must be a valid ObjectId");
        }
    }

    private String normalizeBucket(String bucketRaw) {
        if (bucketRaw == null || bucketRaw.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "bucket is required");
        }

        String normalized = bucketRaw.trim().toLowerCase(Locale.ROOT);
        if (!SUPPORTED_BUCKETS.contains(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported bucket: " + bucketRaw);
        }

        return normalized.substring(0, 1).toUpperCase(Locale.ROOT) + normalized.substring(1);
    }

    private List<String> normalizePath(String pathRaw, String bucketKey) {
        if (pathRaw == null || pathRaw.isBlank()) {
            return List.of();
        }

        List<String> segments = Arrays.stream(pathRaw.split("/"))
                .map(segment -> segment == null ? null : segment.trim())
                .filter(segment -> segment != null && !segment.isEmpty())
                .map(segment -> segment.toLowerCase(Locale.ROOT))
                .collect(Collectors.toCollection(ArrayList::new));

        if (!segments.isEmpty() && bucketKey != null && bucketKey.equals(segments.get(0))) {
            segments.remove(0);
        }

        return List.copyOf(segments);
    }

    private List<ParaFolderNodeDto> buildRootNodes(List<OrganizedFileDocument> documents) {
        Map<String, RootAggregate> aggregated = new HashMap<>();

        for (OrganizedFileDocument document : documents) {
            VirtualPath virtualPath = buildVirtualPath(document);
            if (virtualPath.keys().isEmpty()) {
                continue;
            }

            String rootKey = virtualPath.keys().get(0);
            String displayCandidate = virtualPath.display().get(0);
            boolean hasChildren = virtualPath.keys().size() > 1;
            String koreanCandidate = (document.isDirectory() && virtualPath.keys().size() == 1)
                    ? document.getKoreanFileName()
                    : null;

            registerRootNode(aggregated, rootKey, displayCandidate, hasChildren, koreanCandidate);
        }

        return aggregated.entrySet().stream()
                .sorted(Comparator.comparing(entry -> entry.getValue().displayNameOrFallback(entry.getKey()), String.CASE_INSENSITIVE_ORDER))
                .map(entry -> new ParaFolderNodeDto(
                        entry.getValue().displayNameOrFallback(entry.getKey()),
                        entry.getKey(),
                        entry.getValue().hasChildren,
                        entry.getValue().koreanFileName))
                .toList();
    }

    private void registerRootNode(Map<String, RootAggregate> aggregated,
                                  String key,
                                  String displayCandidate,
                                  boolean hasChildren,
                                  String koreanCandidate) {
        RootAggregate aggregate = aggregated.computeIfAbsent(key, unused -> new RootAggregate());
        aggregate.update(displayCandidate, koreanCandidate, hasChildren);
    }

    private List<ParaFolderNodeDto> buildChildNodes(List<String> pathSegments,
                                                    List<OrganizedFileDocument> documents) {
        Map<String, ChildAggregate> aggregated = new HashMap<>();

        for (OrganizedFileDocument document : documents) {
            VirtualPath virtualPath = buildVirtualPath(document);
            if (virtualPath.keys().isEmpty() || virtualPath.keys().size() <= pathSegments.size()) {
                continue;
            }

            if (!matchesPrefix(virtualPath.keys(), pathSegments)) {
                continue;
            }

            int childIndex = pathSegments.size();
            String childKey = virtualPath.keys().get(childIndex);
            String displayCandidate = virtualPath.display().get(childIndex);
            boolean hasChildren = virtualPath.keys().size() > childIndex + 1;
            boolean isExactDirectory = document.isDirectory() && virtualPath.keys().size() == childIndex + 1;
            String koreanCandidate = isExactDirectory ? document.getKoreanFileName() : null;

            ChildAggregate aggregate = aggregated.computeIfAbsent(childKey, unused -> new ChildAggregate());
            aggregate.update(displayCandidate, koreanCandidate, hasChildren);
        }

        return aggregated.entrySet().stream()
                .sorted(Comparator.comparing(entry -> entry.getValue().displayNameOrFallback(entry.getKey()), String.CASE_INSENSITIVE_ORDER))
                .map(entry -> new ParaFolderNodeDto(
                        entry.getValue().displayNameOrFallback(entry.getKey()),
                        buildPath(pathSegments, entry.getKey()),
                        entry.getValue().hasChildren,
                        entry.getValue().koreanFileName))
                .toList();
    }

    private VirtualPath buildVirtualPath(OrganizedFileDocument document) {
        List<String> normalizedKeys = new ArrayList<>();
        List<String> displaySegments = new ArrayList<>();

        String paraFolderKey = normalizeKey(document.getParaFolder());
        List<String> originalSegments = splitOriginalSegments(document.getOriginalRelativePath());

        if (paraFolderKey != null) {
            normalizedKeys.add(paraFolderKey);
            displaySegments.add(toTitleCase(paraFolderKey));
        } else if (!originalSegments.isEmpty()) {
            String first = originalSegments.get(0);
            normalizedKeys.add(first.toLowerCase(Locale.ROOT));
            displaySegments.add(first);
        }

        int startIndex = paraFolderKey != null ? 0 : 1;
        for (int index = startIndex; index < originalSegments.size(); index++) {
            String segment = originalSegments.get(index);
            if (segment == null || segment.isBlank()) {
                continue;
            }
            String normalizedSegment = segment.toLowerCase(Locale.ROOT);
            if (paraFolderKey != null && !normalizedKeys.isEmpty() && normalizedKeys.get(0).equals(normalizedSegment)) {
                continue;
            }
            normalizedKeys.add(normalizedSegment);
            displaySegments.add(segment);
        }

        return new VirtualPath(normalizedKeys, displaySegments);
    }

    private boolean matchesPrefix(List<String> keys, List<String> prefix) {
        if (keys.size() < prefix.size()) {
            return false;
        }

        for (int index = 0; index < prefix.size(); index++) {
            if (!keys.get(index).equals(prefix.get(index))) {
                return false;
            }
        }

        return true;
    }

    private List<String> splitOriginalSegments(String path) {
        if (path == null || path.isBlank()) {
            return List.of();
        }

        return Arrays.stream(path.split("/"))
                .map(segment -> segment == null ? null : segment.trim())
                .filter(segment -> segment != null && !segment.isEmpty())
                .collect(Collectors.toList());
    }

    private String normalizeKey(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }

    private String buildPath(List<String> base, String childKey) {
        List<String> combined = new ArrayList<>(base);
        combined.add(childKey);
        return String.join("/", combined);
    }

    private String toTitleCase(String key) {
        if (key == null) {
            return null;
        }

        String trimmed = key.trim();
        if (trimmed.isEmpty()) {
            return key;
        }

        if (trimmed.length() == 1) {
            return trimmed.toUpperCase(Locale.ROOT);
        }

        String lower = trimmed.toLowerCase(Locale.ROOT);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    private final class RootAggregate {
        private String displayName;
        private String koreanFileName;
        private boolean hasChildren;

        private void update(String displayCandidate, String koreanCandidate, boolean childExists) {
            if (displayName == null && displayCandidate != null && !displayCandidate.isBlank()) {
                displayName = displayCandidate;
            }
            if (koreanFileName == null && koreanCandidate != null && !koreanCandidate.isBlank()) {
                koreanFileName = koreanCandidate;
            }
            if (childExists) {
                hasChildren = true;
            }
        }

        private String displayNameOrFallback(String key) {
            if (displayName != null) {
                return displayName;
            }
            return toTitleCase(key);
        }
    }

    private final class ChildAggregate {
        private String displayName;
        private String koreanFileName;
        private boolean hasChildren;

        private void update(String displayCandidate, String koreanCandidate, boolean childExists) {
            if (displayName == null && displayCandidate != null && !displayCandidate.isBlank()) {
                displayName = displayCandidate;
            }
            if (koreanFileName == null && koreanCandidate != null && !koreanCandidate.isBlank()) {
                koreanFileName = koreanCandidate;
            }
            if (childExists) {
                hasChildren = true;
            }
        }

        private String displayNameOrFallback(String key) {
            return displayName != null ? displayName : toTitleCase(key);
        }
    }

    private record VirtualPath(List<String> keys, List<String> display) {
    }
}
