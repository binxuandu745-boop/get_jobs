package com.getjobs.application.service;

import com.getjobs.application.entity.LiepinEntity;
import com.getjobs.application.mapper.LiepinConfigMapper;
import com.getjobs.application.mapper.LiepinMapper;
import com.getjobs.application.mapper.LiepinOptionMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import javax.sql.DataSource;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/** Uses mocked persistence only; the existing-only batch must not open a database connection. */
class LiepinSnapshotDeliveryStateTest {
    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {0})
    void existingDeliveryFlagsAreCopiedByIdWithoutReplacingApiFactsOrWriting(Integer secondDeliveryState) {
        LiepinConfigMapper configMapper = mock(LiepinConfigMapper.class);
        LiepinOptionMapper optionMapper = mock(LiepinOptionMapper.class);
        LiepinMapper jobMapper = mock(LiepinMapper.class);
        DataSource dataSource = mock(DataSource.class);
        LiepinService service = new LiepinService(configMapper, optionMapper, jobMapper, dataSource);

        LiepinEntity existingDelivered = snapshot(101L, "旧岗位一", "1-2K", 1);
        LiepinEntity existingOther = snapshot(202L, "旧岗位二", "2-3K", secondDeliveryState);
        LiepinEntity apiFirst = snapshot(101L, "Java开发工程师", "6-8K", null);
        LiepinEntity apiSecond = snapshot(202L, "后端研发工程师", "5-7K", null);
        // Return the stored rows in reverse order to verify that delivery flags follow the job ID.
        when(jobMapper.selectList(any())).thenReturn(List.of(existingOther, existingDelivered));

        service.insertSnapshotsIfNotExistsBatch(List.of(apiFirst, apiSecond));

        assertEquals(1, apiFirst.getDelivered());
        assertEquals(secondDeliveryState, apiSecond.getDelivered());
        assertEquals("Java开发工程师", apiFirst.getJobTitle());
        assertEquals("6-8K", apiFirst.getJobSalaryText());
        assertEquals("后端研发工程师", apiSecond.getJobTitle());
        assertEquals("5-7K", apiSecond.getJobSalaryText());
        assertEquals("旧岗位一", existingDelivered.getJobTitle());
        assertEquals("1-2K", existingDelivered.getJobSalaryText());
        assertEquals(1, existingDelivered.getDelivered());
        assertEquals(secondDeliveryState, existingOther.getDelivered());
        verify(jobMapper, times(1)).selectList(any());
        verifyNoMoreInteractions(jobMapper);
        verifyNoInteractions(configMapper, optionMapper, dataSource);
    }

    private static LiepinEntity snapshot(Long jobId, String title, String salary, Integer delivered) {
        LiepinEntity entity = new LiepinEntity();
        entity.setJobId(jobId);
        entity.setJobTitle(title);
        entity.setJobSalaryText(salary);
        entity.setDelivered(delivered);
        return entity;
    }
}
