package searchengine.responses;

import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.*;

@Data
@NoArgsConstructor
@Entity

public class SearchData {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;
    private String site;
    private String siteName;
    private String uri;
    private String title;

    @Column(columnDefinition = "MEDIUMTEXT")
    private String snippet;
    private float relevance;
}
