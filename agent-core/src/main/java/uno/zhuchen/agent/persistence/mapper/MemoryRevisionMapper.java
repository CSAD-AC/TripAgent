package uno.zhuchen.agent.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import uno.zhuchen.agent.domain.entity.MemoryRevisionEntity;

@Mapper
public interface MemoryRevisionMapper extends BaseMapper<MemoryRevisionEntity> {
}
