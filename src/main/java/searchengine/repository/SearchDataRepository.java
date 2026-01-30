package searchengine.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import searchengine.responses.SearchData;

public interface SearchDataRepository extends JpaRepository<SearchData, Integer> {
}
