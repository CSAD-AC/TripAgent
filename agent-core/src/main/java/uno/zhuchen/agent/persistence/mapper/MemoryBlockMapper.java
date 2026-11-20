package uno.zhuchen.agent.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import uno.zhuchen.agent.domain.entity.MemoryBlockEntity;

@Mapper
public interface MemoryBlockMapper extends BaseMapper<MemoryBlockEntity> {
}
