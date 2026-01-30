package searchengine.responses;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class StatusIndexingResponse {
    private boolean result;
    private String message;

    public StatusIndexingResponse(boolean result) {
        this.result = result;
    }

    public StatusIndexingResponse(boolean result, String message) {
        this.result = result;
        this.message = message;
    }
}
