package searchengine.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import searchengine.responses.SearchData;

public interface SearchDataRepository extends JpaRepository<SearchData, Integer> {
}
