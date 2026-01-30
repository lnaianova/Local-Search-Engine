package searchengine.services;

import lombok.RequiredArgsConstructor;
import org.apache.lucene.morphology.LuceneMorphology;
import org.apache.lucene.morphology.english.EnglishLuceneMorphology;
import org.apache.lucene.morphology.russian.RussianLuceneMorphology;
import org.springframework.stereotype.Service;
import searchengine.model.IndexEntity;
import searchengine.model.LemmaEntity;
import searchengine.model.PageEntity;
import searchengine.model.SiteEntity;
import searchengine.repository.IndexRepository;
import searchengine.repository.LemmaRepository;
import searchengine.repository.SiteRepository;
import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class LemmaExtractor {

    public static final String WORD_TYPE_REGEX = "[a-zA-Zа-яёА-ЯЁ]+";
    private final SiteRepository siteRepository;
    private final LemmaRepository lemmaRepository;
    private final IndexRepository indexRepository;

    protected static List<String> russianStopWords = new ArrayList<>(Arrays.asList( "и", "в",
            "во", "не", "что", "он", "на", "я", "с", "со", "как", "а", "то", "наш",
            "все", "она", "так", "его", "но", "да", "ты", "к", "у", "же", "вы", "за",
            "бы", "по", "только", "ее", "мне", "было", "вот", "от", "меня", "еще", "нет",
            "о", "из", "ему", "теперь", "когда", "даже", "ну", "ли", "если", "уже",
            "или", "ни", "быть", "был", "него", "до", "вас", "нибудь", "опять", "уж",
            "с", "то", "же", "все", "это", "так", "как", "но", "на", "по", "из", "от", "об", "для"
    ));

    protected static List<String> englishStopWords = new ArrayList<>(Arrays.asList( "a", "an",
            "the", "and", "or", "but", "in", "on", "at", "to", "for", "of",
            "with", "by", "from", "up", "about", "into", "through", "during", "before",
            "after", "above", "below", "between", "among", "is", "are", "was", "were",
            "be", "been", "being", "have", "has", "had", "do", "does", "did", "will",
            "would", "shall", "should", "may", "might", "must", "can", "could", "this",
            "that", "these", "those", "i", "you", "he", "she", "it", "we", "they",
            "the", "with", "also", "over"
    ));

    public static HashSet<String> getWords(String text) {
        HashSet<String> words = new HashSet<>();
        Pattern pattern = Pattern.compile(WORD_TYPE_REGEX);
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            int start = matcher.start();
            int end = matcher.end();
            String word = text.substring(start, end);
            if (word.length() > 1 && isCyrillic(word) && !russianStopWords.contains(word.toLowerCase()) ||
                    word.length() > 1 && isLatin(word) && !englishStopWords.contains(word.toLowerCase())) {
                words.add(word.toLowerCase());
            }
        }
        return words;
    }

    public static boolean isCyrillic(String word) {
        String regex = "[а-яёА-ЯЁ]+";
        return word.matches(regex);
    }

    public static boolean isLatin(String word) {
        return word.matches("[a-zA-Z]+");
    }

    private void lemmasCreate(Map<String, Integer> lemmas, Integer siteId, PageEntity pageEntity) {
        if (PageSearcher.running) {
            List<LemmaEntity> allLemmaEntity = lemmaRepository.findAll();
            List<String> allLemmas = allLemmaEntity.stream().map(LemmaEntity::getLemma).toList();
            for (Map.Entry<String, Integer> entry : lemmas.entrySet()) {
                LemmaEntity lemma;
                if (!allLemmas.contains(entry.getKey())) {
                    lemma = new LemmaEntity();
                    Optional<SiteEntity> site = siteRepository.findById(siteId);
                    site.ifPresent(lemma::setSiteEntity);
                    lemma.setLemma(entry.getKey());
                    lemma.setFrequency(1);
                    if (PageSearcher.running) {
                        lemmaRepository.save(lemma);
                    }
                } else {
                    lemma = allLemmaEntity.stream()
                            .filter(i -> i.getLemma().equals(entry.getKey())).toList().get(0);
                    lemma.setFrequency(lemma.getFrequency() + 1);
                    if (PageSearcher.running) {
                        lemmaRepository.save(lemma);
                    }
                }
                IndexEntity indexEntity = new IndexEntity();
                indexEntity.setLemmaEntity(lemma);
                indexEntity.setPageEntity(pageEntity);
                indexEntity.setRate(entry.getValue());
                if (PageSearcher.running) {
                    indexRepository.save(indexEntity);
                }
            }
        }
    }

    protected void deleteLemmaForPage(PageEntity pageEntity) {
        List<IndexEntity> indexEntities = indexRepository.findAll().stream()
                .filter(i -> i.getPageEntity().equals(pageEntity)).toList();
        List<LemmaEntity> lemmaEntities = indexEntities.stream().map(IndexEntity::getLemmaEntity).toList();
        for (LemmaEntity lemmaEntity : lemmaEntities) {
            if (lemmaEntity.getFrequency() == 1) {
                lemmaRepository.delete(lemmaEntity);
            } else {
                lemmaEntity.setFrequency(lemmaEntity.getFrequency() - 1);
            }
        }
        for (IndexEntity index : indexEntities) {
            indexRepository.delete(index);
        }
    }

    public static Map<String, Integer> lemmaExtract(String text) throws IOException {
        Map<String, Integer> lemmas = new HashMap<>();
        String[] splitText = getWords(text).toArray(new String[0]);
        LuceneMorphology russianMorphology = new RussianLuceneMorphology();
        LuceneMorphology englishMorphology = new EnglishLuceneMorphology();
        for (String word : splitText) {
            try {
                if (isCyrillic(word)) {
                    List<String> russianForms = russianMorphology.getNormalForms(word);
                    if (!russianForms.isEmpty()) {
                        String lemma = russianForms.get(0);
                        lemmas.put(lemma, lemmas.getOrDefault(lemma, 0) + 1);
                    }
                }
                if (isLatin(word)) {
                    List<String> englishForms = englishMorphology.getNormalForms(word);
                    if (!englishForms.isEmpty()) {
                        String lemma = englishForms.get(0);
                        lemmas.put(lemma, lemmas.getOrDefault(lemma, 0) + 1);
                    }
                }
            } catch (Exception e) {
                System.out.println(e.getMessage());
            }
        }
        return lemmas;
    }

    protected void getAllLemmas(String text, Integer siteId, PageEntity pageEntity) throws IOException {
        Map<String, Integer> lemmas = lemmaExtract(text);
        lemmasCreate(lemmas, siteId, pageEntity);
    }
}
