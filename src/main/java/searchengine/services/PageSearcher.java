package searchengine.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.transaction.annotation.Transactional;
import searchengine.dto.objects.PageDto;
import searchengine.model.PageEntity;
import searchengine.model.SiteEntity;
import searchengine.repository.PageRepository;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.RecursiveAction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@RequiredArgsConstructor
@Transactional
public class PageSearcher extends RecursiveAction {

    protected final String siteUrl;
    private final PageRepository pageRepository;
    private final SiteEntity siteEntity;
    protected final LemmaExtractor lemmaExtractor;
    private volatile boolean valid;
    public static volatile boolean running = true;

    public static Document getDocument(String url) throws IOException, InterruptedException {
        Thread.sleep(200);
        return Jsoup.connect(url).userAgent("Mozilla/5.0 (Windows; U; WindowsNT 5.1; en-US; rv1.8.1.6) " +
                        "Gecko/20070725 Firefox/2.0.0.6").referrer("http://www.google.com")
                .ignoreContentType(true).ignoreHttpErrors(true).get();
    }

    private synchronized boolean isValid(String absUrl) {
        String formats = ".*(yml|yaml|nc|eps|ws|sql|png|jpeg|jpg|gif|webp|bmp|svg|ico|webm|ogg|oga|p3|mav|pdf|doc|docx|xls|xlsx|ppt|pptx|txt|rtf|zip|rar|7z|tgz|js|css|xml|json|woff|woff2|ttf|otf|apk|exe|bin|JPG|JPEG)$";
        Pattern pattern = Pattern.compile(formats);
        Matcher matcher = pattern.matcher(absUrl);
        valid = !matcher.matches() && !LinksStorage.pages.contains(absUrl) && !absUrl.endsWith(formats)
                && absUrl.startsWith(siteEntity.getUrl()) && !trimLink(absUrl).equals("#");
        return valid;
    }

    private synchronized String trimLink(String url) {
        int index = siteEntity.getUrl().length();
        return url.substring(index);
    }

    protected void indexPage() throws IOException, InterruptedException, URISyntaxException {
        Document document = getDocument(siteUrl);
        HttpRequest request = HttpRequest.newBuilder(new URI(siteUrl)).build();
        HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_2).build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        String code = Integer.toString(response.statusCode());
        String content = response.body();
        String trim = trimLink(siteUrl);
        PageEntity pageEntity = new PageEntity();
        pageEntity.setPath(trim);
        pageEntity.setCode(Integer.valueOf(code));
        pageEntity.setSite(siteEntity);
        pageEntity.setContent(content);
        List<PageEntity> pageEntityList = pageRepository.findAll().stream()
                .filter(i -> i.getPath().equals(trim)).toList();
        if (!pageEntityList.isEmpty()) {
            PageEntity pageEntity1 = pageEntityList.get(0);
            lemmaExtractor.deleteLemmaForPage(pageEntity1);
            pageRepository.delete(pageEntity1);
        }
        if (running) {
            pageRepository.save(pageEntity);
            if (Integer.parseInt(code) < 400) {
                getContent(document, pageEntity.getSite().getId(), pageEntity);
            }
        }
    }

    public static String documentClear(Document document) {
        StringBuilder builder = new StringBuilder();
        builder.append(document.title());
        builder.append(" ");
        Element descriptionElement = document.select("meta[name=description]").first();
        if (descriptionElement != null) {
            builder.append(descriptionElement.attr("content"));
            builder.append(" ");
        }
        builder.append(document.body().text());
        return builder.toString();
    }

    protected void getContent(Document document, Integer siteId, PageEntity pageEntity) {
        String text = documentClear(document);
        try {
            lemmaExtractor.getAllLemmas(text, siteId, pageEntity);
        } catch (IOException e) {
            log.info(e.getMessage());
        }
    }

    protected CopyOnWriteArraySet<String> getPages(String url) throws IOException, InterruptedException, URISyntaxException {
        CopyOnWriteArraySet<String> linkSet = new CopyOnWriteArraySet<>();
        Document document = getDocument(url);
        Elements elements = document.select("a[href]");
        for (Element item : elements) {
            String absLink = item.attr("abs:href");
            if (isValid(absLink)) {
                Thread.sleep(130);
                LinksStorage.pages.add(absLink);
                linkSet.add(absLink);
                String code;
                String content;
                HttpRequest request = HttpRequest.newBuilder(new URI(absLink)).build();
                HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_2).build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                code = Integer.toString(response.statusCode());
                content = response.body();
                String trim = trimLink(absLink);
                PageEntity pageEntity = new PageEntity();
                pageEntity.setPath(trim);
                pageEntity.setCode(Integer.valueOf(code));
                pageEntity.setSite(siteEntity);
                pageEntity.setContent(content);
                synchronized (pageRepository) {
                    if (running) {
                        pageRepository.save(pageEntity);
                    }
                }
                if (Integer.parseInt(code) < 400) {
                    getContent(document, pageEntity.getSite().getId(), pageEntity);
                }
            }
        }
        return linkSet;
    }

    public static PageDto mapPageToDto(PageEntity pageEntity) {
        PageDto pageDto = new PageDto();
        pageDto.setId(pageEntity.getId());
        pageDto.setPath(pageEntity.getPath());
        pageDto.setSite(pageEntity.getSite());
        pageDto.setCode(pageEntity.getCode());
        pageDto.setContent(pageEntity.getContent());
        return pageDto;
    }

    @Override
    protected void compute() {
        if (running) {
            CopyOnWriteArraySet<String> urlSet = null;
            try {
                urlSet = getPages(siteUrl);
            } catch (IOException | InterruptedException | URISyntaxException e) {
                throw new RuntimeException(e);
            }
            if (urlSet.isEmpty()) {
                log.info("Набор ссылок обработан!");
            }
            CopyOnWriteArraySet<PageSearcher> newTaskList = new CopyOnWriteArraySet<>();
            for (String item : urlSet) {
                PageSearcher searcher = new PageSearcher(item, pageRepository, siteEntity, lemmaExtractor);
                if (running) {
                    searcher.fork();
                    newTaskList.add(searcher);
                }
            }
            newTaskList.forEach(ForkJoinTask::join);
        } else {
            IndexingService.error = "Индексация остановлена пользователем";
            Thread.currentThread().interrupt();
            log.info("Current thread is stopped");
            siteEntity.setLastError(IndexingService.error);
        }
    }
}
