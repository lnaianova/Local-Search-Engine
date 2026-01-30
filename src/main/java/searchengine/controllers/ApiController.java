package searchengine.controllers;

import lombok.Data;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import searchengine.dto.statistics.StatisticsResponse;
import searchengine.repository.IndexRepository;
import searchengine.repository.LemmaRepository;
import searchengine.repository.PageRepository;
import searchengine.repository.SearchDataRepository;
import searchengine.responses.ApplicationErrorException;
import searchengine.responses.SearchData;
import searchengine.responses.SearchResult;
import searchengine.responses.StatusIndexingResponse;
import searchengine.model.Status;
import searchengine.services.IndexingService;
import searchengine.services.PageSearcher;
import searchengine.services.SearchService;
import searchengine.services.StatisticsService;
import java.io.IOException;

@RestController
@RequestMapping("/api")
@Data
public class ApiController {

    @ExceptionHandler(ApplicationErrorException.class)
    public ResponseEntity<String> handlerResourceNotFoundException(ApplicationErrorException r) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(r.getMessage());
    }

    private final StatisticsService statisticsService;
    private final IndexingService indexingService;
    private final LemmaRepository lemmaRepository;
    private final PageRepository pageRepository;
    private final IndexRepository indexRepository;
    private final SearchDataRepository searchDataRepository;

    public ApiController(StatisticsService statisticsService, IndexingService indexingService,
                         LemmaRepository lemmaRepository, PageRepository pageRepository,
                         IndexRepository indexRepository, SearchDataRepository searchDataRepository) {
        this.statisticsService = statisticsService;
        this.indexingService = indexingService;
        this.lemmaRepository = lemmaRepository;
        this.pageRepository = pageRepository;
        this.indexRepository = indexRepository;
        this.searchDataRepository = searchDataRepository;
    }

    @GetMapping("/statistics")
    public ResponseEntity<StatisticsResponse> statistics() {
        return ResponseEntity.ok(statisticsService.getStatistics());
    }

    @GetMapping("/startIndexing")
    public ResponseEntity<StatusIndexingResponse> startIndexing() throws IOException, InterruptedException {
        if (!PageSearcher.running) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(new StatusIndexingResponse(false, "Идет остановка ранее запущенной индексации, " +
                            "подождите..."));
        }
        if (IndexingService.indexingStatus.equals(Status.INDEXING)){
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(new StatusIndexingResponse(false, "Индексация уже запущена"));
        }
        try {
            indexingService.startIndexing();
        } catch (ApplicationErrorException e) {
            throw new ApplicationErrorException("Запуск индексации невозможен");
        }

        return ResponseEntity.status(HttpStatus.OK)
                .body(new StatusIndexingResponse(true, "Индексация начата"));
    }

    @GetMapping("/stopIndexing")
    public ResponseEntity<StatusIndexingResponse> stopIndexing() {
        if (IndexingService.indexingStatus.equals(Status.INDEXED)) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(new StatusIndexingResponse(false, "Индексация не запущена"));
        }
        try {
            indexingService.stopIndexing();
        } catch (ApplicationErrorException e) {
            throw new ApplicationErrorException("Ошибка завершения индексации");
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        return ResponseEntity.status(HttpStatus.OK)
                .body(new StatusIndexingResponse(true,
                        "Процесс запущен, дождитесь завершения работы всех потоков"));
    }

    @PostMapping("/indexPage")
    public ResponseEntity<StatusIndexingResponse> indexPage(@RequestParam ("url") String url) {
        if (IndexingService.indexingStatus.equals(Status.INDEXING)){
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(new StatusIndexingResponse(false, "Индексация уже запущена"));
        }
        if (indexingService.urlCheck(url)) {
            indexingService.pageIndexing(url);
            return ResponseEntity.status(HttpStatus.OK)
                    .body(new StatusIndexingResponse(true, "Страница проиндексирована"));
        }
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(new StatusIndexingResponse(false, "Данная страница находится за " +
                        "пределами сайтов, указанных в конфигурационном файле"));
    }

    @GetMapping("/search")
    public ResponseEntity<Object> searchPages(@RequestParam ("query")String query,
                                              @RequestParam (name = "site", required = false) String site,
                                              @RequestParam (name = "offset", defaultValue = "0") Integer offset,
                                              @RequestParam (name = "limit", defaultValue = "5") Integer limit)
            throws IOException, InterruptedException {
        if (query.isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new StatusIndexingResponse(false,
                    "Задан пустой поисковый запрос"));
        }
        searchDataRepository.deleteAll();
        SearchService searchService = new SearchService(lemmaRepository, pageRepository,
                indexRepository, searchDataRepository);
        if (site == null) {
            site = "";
        }
        return ResponseEntity.status(HttpStatus.OK).body(searchService.getResponse(query, site, 0, 5));
    }
}