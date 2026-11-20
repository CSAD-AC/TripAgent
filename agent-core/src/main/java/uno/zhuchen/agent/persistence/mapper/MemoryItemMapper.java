package uno.zhuchen.agent.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import uno.zhuchen.agent.domain.entity.MemoryItemEntity;

@Mapper
public interface MemoryItemMapper extends BaseMapper<MemoryItemEntity> {
}
