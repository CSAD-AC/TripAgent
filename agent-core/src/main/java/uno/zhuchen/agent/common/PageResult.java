package uno.zhuchen.agent.common;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 分页结果封装。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PageResult<T> {

    private List<T> data;
    private int total;
    private int page;
    private int size;

    public static <T> PageResult<T> of(List<T> data, int total, int page, int size) {
        return PageResult.<T>builder()
                .data(data)
                .total(total)
                .page(page)
                .size(size)
                .build();
    }
}
