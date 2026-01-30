package searchengine.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.stereotype.Service;
import searchengine.config.Site;
import searchengine.config.SitesList;
import searchengine.dto.objects.SiteDto;
import searchengine.model.SiteEntity;
import searchengine.model.Status;
import searchengine.repository.LemmaRepository;
import searchengine.repository.PageRepository;
import searchengine.repository.SiteRepository;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.*;

@Service
@Slf4j
@RequiredArgsConstructor
@EnableAsync
public class IndexingService {

    private final SitesList sites;
    public static Status indexingStatus = Status.INDEXED;
    private final SiteRepository siteRepository;
    private final PageRepository pageRepository;
    private final LemmaExtractor lemmaExtractor;
    LinksStorage storage = new LinksStorage();
    public static String error;
    public ConcurrentHashMap<Thread, Integer> taskList = new ConcurrentHashMap<>();
    private final LemmaRepository lemmaRepository;

    public List<SiteDto> getSiteList() {
        log.info("Получение списка сайтов...");
        List<Site> siteList = sites.getSites();
        List<SiteDto> siteDtoList = new ArrayList<>();
        for (Site site : siteList) {
            SiteDto siteDto = new SiteDto();
            siteDto.setName(site.getName());
            siteDto.setUrl(site.getUrl());
            siteDtoList.add(siteDto);
        }
        return siteDtoList;
    }

    public boolean urlCheck(String url) {
        int count = 0;
        List<SiteDto> siteDtoList = getSiteList();
        for (SiteDto siteDto : siteDtoList) {
            if (url.startsWith(siteDto.getUrl())) {
                count++;
            }
        }
        return count != 0;
    }

    public void pageIndexing(String url) {
        indexingStatus = Status.INDEXING;
        clearResources();
        List<SiteEntity> siteEntityList = siteRepository.findAll();
        SiteEntity siteEntity = new SiteEntity();
        boolean contains = false;
        for (SiteEntity entity : siteEntityList) {
            if (url.startsWith(entity.getUrl())) {
                contains = true;
                entity.setStatus(Status.INDEXING);
                entity.setLastError(null);
                Instant instant = Instant.now().atZone(ZoneId.systemDefault()).truncatedTo(ChronoUnit.SECONDS).toInstant();
                entity.setStatusTime(instant);
                siteRepository.save(entity);
                siteEntity = entity;
            }
        }
        if (!contains) {
            List<SiteDto> siteDtoList = getSiteList();
            for (SiteDto siteDto : siteDtoList) {
                if (url.startsWith(siteDto.getUrl())) {
                    siteRepository.save(mapToEntity(siteDto));
                }
            }
        }
        SiteEntity finalSiteEntity = siteEntity;
        PageSearcher pageSearcher = new PageSearcher(url, pageRepository, finalSiteEntity, lemmaExtractor);
        try {
            pageSearcher.indexPage();
            finalSiteEntity.setStatus(Status.INDEXED);
        } catch (Exception e) {
            finalSiteEntity.setStatus(Status.FAILED);
            finalSiteEntity.setLastError(e.getMessage());
        }
        siteRepository.save(finalSiteEntity);
        indexingStatus = Status.INDEXED;
        log.info("ИНДЕКСАЦИЯ СТРАНИЦЫ ЗАВЕРШЕНА!");
    }

    private void clearResources() {
        error = null;
        PageSearcher.running = true;
        taskList.clear();
        LinksStorage.pages.clear();
    }

    public void startProcess() {
        indexingStatus = Status.INDEXING;
        clearResources();
        lemmaRepository.deleteAll();
        siteRepository.deleteAll();
    }

    @Async
    public void startIndexing() {
        startProcess();
        List<SiteDto> siteDtoList = getSiteList();
        for (SiteDto site : siteDtoList) {
            String parentLink = site.getUrl();
            SiteEntity siteEntity = mapToEntity(site);
            siteRepository.save(siteEntity);
            Runnable task = () -> {
                PageSearcher pageSearcher = new PageSearcher(parentLink, pageRepository, siteEntity, lemmaExtractor);
                ForkJoinPool forkJoinPool = new ForkJoinPool();
                forkJoinPool.invoke(pageSearcher);
            };
            Thread thread = new Thread(task);
            taskList.put(thread, siteEntity.getId());
        }

        for (Map.Entry<Thread, Integer> item : taskList.entrySet()) {
            item.getKey().start();
        }

        for (Map.Entry<Thread, Integer> item : taskList.entrySet()) {
            Optional<SiteEntity> entity = siteRepository.findById(item.getValue());
            try {
                item.getKey().join();
                if (error != null) {
                    entity.ifPresent(i -> i.setLastError(error));
                    entity.ifPresent(i -> i.setStatus(Status.FAILED));
                } else {
                    entity.ifPresent(i -> i.setStatus(Status.INDEXED));
                }
                entity.ifPresent(siteRepository::save);
            } catch (Exception e) {
                error = e.getMessage();
                entity.ifPresent(i -> i.setStatus(Status.FAILED));
                entity.ifPresent(i -> i.setLastError(error));
                entity.ifPresent(siteRepository::save);
            }
        }
        indexingStatus = Status.INDEXED;
        log.info("ИНДЕКСАЦИЯ ЗАВЕРШЕНА!");
        PageSearcher.running = true;
    }

    public void stopIndexing() throws InterruptedException {
        PageSearcher.running = false;
        error = "Индексация остановлена пользователем";
    }

    public static SiteEntity mapToEntity(SiteDto site) {
        SiteEntity siteEntity = new SiteEntity();
        siteEntity.setStatus(Status.INDEXING);
        Instant instant = Instant.now().atZone(ZoneId.systemDefault())
                .truncatedTo(ChronoUnit.SECONDS).toInstant();
        siteEntity.setStatusTime(instant);
        siteEntity.setUrl(site.getUrl());
        siteEntity.setName(site.getName());
        return siteEntity;
    }
}
