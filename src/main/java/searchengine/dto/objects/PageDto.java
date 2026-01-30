package searchengine.dto.objects;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import searchengine.model.SiteEntity;

@Getter
@Setter
@NoArgsConstructor
public class PageDto {
    private Integer id;
    private SiteEntity site;
    private String path;
    private Integer code;
    private String content;
}
