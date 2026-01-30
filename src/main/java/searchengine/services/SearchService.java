package searchengine.services;

import lombok.Data;
import org.apache.lucene.morphology.LuceneMorphology;
import org.apache.lucene.morphology.english.EnglishLuceneMorphology;
import org.apache.lucene.morphology.russian.RussianLuceneMorphology;
import org.jsoup.nodes.Document;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import searchengine.dto.objects.SearchDataDto;
import searchengine.model.IndexEntity;
import searchengine.model.LemmaEntity;
import searchengine.model.PageEntity;
import searchengine.repository.IndexRepository;
import searchengine.repository.LemmaRepository;
import searchengine.repository.PageRepository;
import searchengine.repository.SearchDataRepository;
import searchengine.responses.ApplicationErrorException;
import searchengine.responses.SearchData;
import searchengine.responses.SearchResult;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Service
@Data
public class SearchService {

    private String text;
    private String url;
    private SearchResult searchResult;
    private final LemmaRepository lemmaRepository;
    private final PageRepository pageRepository;
    private final IndexRepository indexRepository;
    private final SearchDataRepository searchDataRepository;
    private int snippetLength = 40;
    private int maxWordsCount = 8;
    private AtomicInteger tagCount = new AtomicInteger(0);

    public SearchResult getResponse(String text, String url, Integer offset, Integer limit) throws IOException, InterruptedException {
        searchDataRepository.deleteAll();
        Map<String, Integer> lemmas = getLemmaList(text);
        Map<PageEntity, Map<String, Float>> relevantValues = getRelevantPagesList(lemmas, url);
        Map<PageEntity, Float> relationRelevantValues = getRelativeRelevantValues(relevantValues);
        searchResult = new SearchResult();
        searchResult.setResult(true);
        searchResult.setCount(relationRelevantValues.size());
        for (Map.Entry<PageEntity, Float> entry : relationRelevantValues.entrySet()) {
            SearchData searchData = new SearchData();
            searchData.setSite(entry.getKey().getSite().getUrl());
            searchData.setSiteName(entry.getKey().getSite().getName());
            searchData.setUri(entry.getKey().getPath());
            searchData.setTitle(getTitle(entry.getKey()));
            String snippet = getSnippet(entry.getKey(), lemmas.keySet().stream().toList());
            searchData.setSnippet(snippet);
            searchData.setRelevance(entry.getValue());
            searchDataRepository.save(searchData);
        }
        Pageable pageable = PageRequest.of(offset, limit);
        Page<SearchData> pages = searchDataRepository.findAll(pageable);
        List<SearchDataDto> pagesDto = pages.stream().map(SearchService::mapToDto).toList();
        searchResult.setData(pagesDto);
        return searchResult;
    }

    public static SearchDataDto mapToDto(SearchData searchData) {
        SearchDataDto searchDataDto = new SearchDataDto();
        searchDataDto.setSite(searchData.getSite());
        searchDataDto.setSiteName(searchData.getSite());
        searchDataDto.setUri(searchData.getUri());
        searchDataDto.setTitle(searchData.getTitle());
        searchDataDto.setSnippet(searchData.getSnippet());
        searchDataDto.setRelevance(searchData.getRelevance());
        return searchDataDto;
    }

    public Map<String, Integer> getLemmaList(String text) throws IOException {
        Map<String, Integer> lemmas = LemmaExtractor.lemmaExtract(text);
        List<String> lemmasOnly = lemmas.keySet().stream().toList();
        Map<String, Integer> newLemmasList = new HashMap<>();
        List<LemmaEntity> lemmaEntities = lemmaRepository.findAll();
        for (String lemma : lemmasOnly) {
            for (LemmaEntity lemmaEntity : lemmaEntities) {
                if (lemma.equals(lemmaEntity.getLemma())) {
                    Integer count = lemmaEntity.getFrequency();
                    newLemmasList.put(lemma, count);
                }
            }
        }
        return newLemmasList.entrySet().stream().sorted(Map.Entry.comparingByValue(Comparator.reverseOrder()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (e1, e2) -> e1,
                        LinkedHashMap::new));
    }

    public String getTitle(PageEntity pageEntity) {
        String document = pageEntity.getContent();
        String startTag = "<title>";
        String endTag = "</title>";
        int start = document.indexOf(startTag) + 7;
        int end = document.indexOf(endTag);
        return document.substring(start, end);
    }

    public String[] tagCreator(String content, List<String> lemmas) throws IOException {
        LuceneMorphology russianMorphology = new RussianLuceneMorphology();
        LuceneMorphology englishMorphology = new EnglishLuceneMorphology();
        String[] pageContent = content.split("\\s+");
        for (int i = 0; i < pageContent.length; i++) {
            for (String lemma : lemmas) {
                if (pageContent[i].length() > 1 && pageContent[i].toLowerCase().charAt(0) == lemma.toLowerCase().charAt(0)
                && pageContent[i].toLowerCase().charAt(1) == lemma.toLowerCase().charAt(1)) {
                    String word = pageContent[i].replaceAll("[^\\p{L}]", "");
                    if (LemmaExtractor.isCyrillic(word.toLowerCase())) {
                        List<String> baseWords = russianMorphology.getNormalForms(word.toLowerCase());
                        if (baseWords.contains(lemma.toLowerCase()) && word.toLowerCase().startsWith(lemma.toLowerCase()) ||
                                (baseWords.contains(lemma.toLowerCase()) || word.toLowerCase().startsWith(lemma.toLowerCase()))) {
                            word = "<b>".concat(word).concat("</b>");
                            pageContent[i] = word;
                            tagCount.incrementAndGet();
                        }
                    } else if (LemmaExtractor.isLatin(word.toLowerCase())) {
                        List<String> baseWords = englishMorphology.getNormalForms(word.toLowerCase());
                        if (baseWords.contains(lemma.toLowerCase()) && word.toLowerCase().startsWith(lemma.toLowerCase()) ||
                                (baseWords.contains(lemma.toLowerCase()) || word.toLowerCase().startsWith(lemma.toLowerCase()))) {
                            word = "<b>".concat(word).concat("</b>");
                            pageContent[i] = word;
                            tagCount.incrementAndGet();
                        }
                    }
                }
            }
        }
        return pageContent;
    }

    public String getSnippet(PageEntity pageEntity, List<String> lemmas)
            throws IOException, InterruptedException {
        String absUrl = pageEntity.getSite().getUrl().concat(pageEntity.getPath());
        Document document = PageSearcher.getDocument(absUrl);
        String content = PageSearcher.documentClear(document);
        String[] pageContent = tagCreator(content, lemmas);
        StringBuilder builder = new StringBuilder();
        if (tagCount.get() == 0) {
            return builder.toString().strip();
        } else {
            String[] snippet;
            if (pageContent.length <= maxWordsCount) {
                snippet = pageContent;
                builder.append(String.join(" ", snippet));
            } else {
                if (tagCount.get() > maxWordsCount) {
                    tagCount.set(maxWordsCount);
                }
                int partSize = (snippetLength - tagCount.get()) / tagCount.get();
                for (int i = 0; i < pageContent.length; i++) {
                    if (pageContent[i].startsWith("<b>")) {
                        builder.append(pageContent[i]).append(" ");
                        if ((pageContent.length - 1) - (i + 1) > partSize) {
                            for (int a = 1; a <= partSize; a++) {
                                builder.append(pageContent[i + a]).append(" ");
                            }
                            i = i + partSize;
                        } else {
                            for (int a = 1; a < (pageContent.length - 1) - (i + 1); a++) {
                                builder.append(pageContent[i + a]).append(" ");
                            }
                        }
                    }
                }
            }
        }
        return builder.toString().strip();
    }

    public Map<PageEntity, Float> getRelativeRelevantValues(Map<PageEntity, Map<String, Float>> pages) {
        Map<PageEntity, Float> relevantValues = new HashMap<>();
        for (Map.Entry<PageEntity, Map<String, Float>> entry : pages.entrySet()) {
            PageEntity page = entry.getKey();
            Map<String, Float> lemmasRate = entry.getValue();
            Float absRelevance = lemmasRate.values().stream().reduce(0f, Float::sum);
            relevantValues.put(page, absRelevance);
        }
        Float maxVal = relevantValues.values().stream().max(Float::compareTo).orElse(0f);
        Map<PageEntity, Float> relationRelevantValues = new HashMap<>();
        for (Map.Entry<PageEntity, Float> item : relevantValues.entrySet()) {
            Float relationRelevance = item.getValue() / (maxVal == 0 ? item.getValue() : maxVal);
            relationRelevantValues.put(item.getKey(), relationRelevance);
        }
        return relationRelevantValues.entrySet().stream().sorted(Map.Entry.<PageEntity, Float>comparingByValue().reversed())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (e1, e2) -> e1,
                        LinkedHashMap::new));
    }

    public Map<PageEntity, Map<String, Float>> getRelevantPagesList(Map<String, Integer> lemmas, String url) {
        Map<PageEntity, Map<String, Float>> relevantValuesList = new HashMap<>();
        List<IndexEntity> indexEntities = indexRepository.findAll();
        if (!url.isEmpty()) {
            indexEntities = indexEntities.stream()
                    .filter(i -> i.getPageEntity().getSite().getUrl().equals(url)).toList();
            if (indexEntities.isEmpty()) {
                throw new ApplicationErrorException("Указанная страница не найдена");
            }
        }

        List<PageEntity> allRelevantPages = new ArrayList<>();
        List<String> lemmasList = lemmas.keySet().stream().toList();
        for (String entry : lemmasList) {
            List<PageEntity> oneStepPages = new ArrayList<>(indexEntities.stream().filter(i ->
                    i.getLemmaEntity().getLemma().equals(entry)).map(IndexEntity::getPageEntity).toList());
            allRelevantPages.addAll(oneStepPages);
            oneStepPages.clear();
        }

        HashSet<PageEntity> relevantPages = new HashSet<>();
        if (!allRelevantPages.isEmpty()) {
            for (PageEntity pageEntity : allRelevantPages) {
                if (Collections.frequency(allRelevantPages, pageEntity) == lemmasList.size()) {
                    relevantPages.add(pageEntity);
                }
            }
            for (PageEntity page : relevantPages) {
                Map<String, Float> lemmasRate = new HashMap<>();
                for (String lemma : lemmasList) {
                    List<IndexEntity> indexEntityList = indexRepository.findAll().stream()
                            .filter(i -> i.getPageEntity().equals(page))
                            .filter(i -> i.getLemmaEntity().getLemma().equals(lemma)).toList();
                    if (!indexEntityList.isEmpty()) {
                        float indexRate = indexEntityList.get(0).getRate();
                        if (lemmasRate.isEmpty() || !lemmasRate.containsKey(lemma)) {
                            lemmasRate.put(lemma, indexRate);
                        } else {
                            float count = lemmasRate.get(lemma);
                            count += indexRate;
                            lemmasRate.put(lemma, count);
                        }
                    }
                }
                relevantValuesList.put(page, lemmasRate);
            }
        }
        return relevantValuesList;
    }
}

