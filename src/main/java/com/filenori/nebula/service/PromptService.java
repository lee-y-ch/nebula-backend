package com.filenori.nebula.service;

import com.filenori.nebula.dto.request.KeywordRequestDto;
import com.filenori.nebula.dto.response.FileNameResponseDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PromptService {

    private final OpenAiService openAiService;
    // private final FielNameHistoryRepository repository; // MongoDB 저장용

    public FileNameResponseDto generateFileNameFromKeywords(KeywordRequestDto requestDto) {
        String prompt = createPromptFromRequest(requestDto);

        FileNameResponseDto responseFromAi = openAiService.requestFileNameToGpt(prompt);

        // 결과를 MongoDB에 저장
//        FileNameHistory history = new FileNameHistory(requestDto.getEntries(), responseFromAi.getGeneratedFileName());
//        repository.save(history);

        return responseFromAi;
    }

    private String createPromptFromRequest(KeywordRequestDto requestDto) {
        List<KeywordRequestDto.Entry> entries = requestDto.getEntries();

        String entriesSummary = (entries == null || entries.isEmpty())
                ? "No entries were provided."
                : entries.stream()
                .map(entry -> "- %s (keywords: %s, sizeBytes: %d, modifiedAt: %s, isDirectory: %s, isDevelopment: %s)".formatted(
                        entry.getRelativePath(),
                        entry.getKeywords() == null || entry.getKeywords().isEmpty()
                                ? "none"
                                : String.join(", ", entry.getKeywords()),
                        entry.getSizeBytes(),
                        entry.getModifiedAt(),
                        entry.isDirectory(),
                        entry.isDevelopment()))
                .collect(Collectors.joining("\n"));

        return """
                You are a helpful assistant that creates a concise and descriptive file name summarizing provided file metadata.
                The file name must be in English, follow snake_case, and end with '.txt'.
                Analyse the metadata and select or compose one representative file name.
                Respond ONLY with a JSON object formatted exactly as {"generatedFileName": "example_name.txt", "reason": "Short explanation."}.
                Directory: %s
                Generated At: %s
                Page: %s, PageCount: %s, PageSize: %s, TotalEntries: %s
                Entries:
                %s
                """.formatted(
                requestDto.getDirectory(),
                requestDto.getGeneratedAt(),
                requestDto.getPage(),
                requestDto.getPageCount(),
                requestDto.getPageSize(),
                requestDto.getTotalEntries(),
                entriesSummary);
    }

}
