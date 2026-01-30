package searchengine.responses;

import lombok.Data;
import searchengine.dto.objects.SearchDataDto;

import java.util.List;

@Data
public class SearchResult {
    private boolean result;
    private Integer count;
    private List<SearchDataDto> data;
}

