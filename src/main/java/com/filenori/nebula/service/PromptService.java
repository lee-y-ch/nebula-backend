package com.filenori.nebula.service;

import com.filenori.nebula.dto.request.KeywordRequestDto;
import com.filenori.nebula.dto.response.FileNameResponseDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class PromptService {

    private final OpenAiService openAiService;
    // private final FielNameHistoryRepository repository; // MongoDB 저장용

    public FileNameResponseDto generateFileNameFromKeywords(KeywordRequestDto requestDto) {
        String prompt = createPromptFromKeywords(requestDto.getKeywords());

        FileNameResponseDto responseFromAi = openAiService.requestFileNameToGpt(prompt);

        // 결과를 MongoDB에 저장
//        FileNameHistory history = new FileNameHistory(requestDto.getKeywords(), responseFromAi.getGeneratedFileName());
//        repository.save(history);

        return responseFromAi;
    }

    private String createPromptFromKeywords(List<String> keywords) {
        // 임시 prompt
        return "You are a helpful assistant that creates a concise and descriptive file name from a given list of keywords. The file name should be in English, snake_case, and end with '.txt'. " +
                "Analyze the following keywords and generate the most appropriate file name. " +
                "Keywords: " + String.join(", ", keywords) + ". " +
                "Please respond ONLY with a JSON object in the following format: {\"fileName\": \"your_generated_name.txt\", \"reason\": \"A brief explanation of why you chose this name.\"}.";
    }

}



