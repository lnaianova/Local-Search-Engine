package searchengine.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import javax.persistence.*;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "page", indexes = {@Index(name = "path_index", columnList = "path")})
@Getter
@Setter
@NoArgsConstructor
public class PageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Integer id;

    @JoinColumn(name = "site_id", nullable = false)
    @ManyToOne
    private SiteEntity site;

    @Column(name = "path", nullable = false)
    private String path;

    @Column(name = "code", nullable = false)
    private Integer code;

    @Column(columnDefinition = "MEDIUMTEXT", name = "content", nullable = false)
    private String content;

    @OneToMany(mappedBy = "pageEntity", cascade = CascadeType.ALL)
    private Set<IndexEntity> indexPageList = new HashSet<>();
}
