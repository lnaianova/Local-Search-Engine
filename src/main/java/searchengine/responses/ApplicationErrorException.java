package searchengine.responses;

public class ApplicationErrorException extends RuntimeException {
    public ApplicationErrorException(String message) {
        super(message);
    }
}
