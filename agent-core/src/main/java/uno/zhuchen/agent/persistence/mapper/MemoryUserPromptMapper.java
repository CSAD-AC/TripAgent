package uno.zhuchen.agent.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import uno.zhuchen.agent.domain.entity.MemoryUserPromptEntity;

@Mapper
public interface MemoryUserPromptMapper extends BaseMapper<MemoryUserPromptEntity> {
}
