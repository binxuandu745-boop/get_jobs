package com.getjobs.application.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.getjobs.application.entity.BossJobDataEntity;
import com.getjobs.application.mapper.BlacklistMapper;
import com.getjobs.application.mapper.BossConfigMapper;
import com.getjobs.application.mapper.BossIndustryMapper;
import com.getjobs.application.mapper.BossJobDataMapper;
import com.getjobs.application.mapper.BossOptionMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Pure mapper mocks: no application, live database or browser is opened. */
class BossScreenshotObservationTest {
    private BossJobDataMapper mapper;
    private BossService service;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "screenshot-observation"),
                BossJobDataEntity.class);
        mapper = mock(BossJobDataMapper.class);
        service = new BossService(mock(BossOptionMapper.class), mock(BossIndustryMapper.class),
                mock(BossConfigMapper.class), mock(BlacklistMapper.class), mapper, mock(DataSource.class));
    }

    private BossJobDataEntity observation() {
        BossJobDataEntity entity = new BossJobDataEntity();
        entity.setEncryptId("job-1");
        entity.setEncryptUserId("hr-1");
        entity.setJobName("Java实习生");
        entity.setSalary("150-200元/天");
        entity.setLocation("杭州");
        entity.setDegree(null);
        entity.setCompanyName("截图公司");
        entity.setJobDescription("截图中的岗位职责");
        entity.setExperience("不能保留的网站经验字段");
        entity.setDeliveryStatus("未投递");
        entity.setHrName("网站HR");
        return entity;
    }

    @Test
    void aNewJobIsInsertedWithWebsiteMetadataAndNoUnobservedExperience() {
        when(mapper.selectOne(any())).thenReturn(null);
        when(mapper.insert(any(BossJobDataEntity.class))).thenReturn(1);
        BossJobDataEntity entity = observation();
        service.recordScreenshotObservation(entity);
        verify(mapper).insert(same(entity));
        assertNull(entity.getExperience());
        assertEquals("网站HR", entity.getHrName());
        assertNotNull(entity.getCreatedAt());
        assertEquals(entity.getCreatedAt(), entity.getUpdatedAt());
        verify(mapper, never()).update(any(), any());
        QueryWrapper<BossJobDataEntity> query = capturedQuery();
        assertTrue(query.getSqlSegment().contains("encrypt_user_id"));
        assertTrue(query.getSqlSegment().contains("LIMIT 1"));
        assertTrue(query.getParamNameValuePairs().containsValue("job-1"));
        assertTrue(query.getParamNameValuePairs().containsValue("hr-1"));
    }

    @Test
    void anExistingJobGetsExplicitNullsAndImageValuesWhileItsOtherMetadataIsUntouched() {
        BossJobDataEntity existing = new BossJobDataEntity();
        existing.setId(42L);
        existing.setDeliveryStatus("未投递");
        when(mapper.selectOne(any())).thenReturn(existing);
        when(mapper.update(isNull(), any())).thenReturn(1);
        BossJobDataEntity entity = observation();
        entity.setSalary(null);
        entity.setDeliveryStatus("已过滤");
        service.recordScreenshotObservation(entity);
        LambdaUpdateWrapper<BossJobDataEntity> update = capturedUpdate();
        Map<String, Object> sets = assignments(update);
        assertEquals(9, sets.size());
        assertEquals("Java实习生", sets.get("job_name"));
        assertEquals("截图公司", sets.get("company_name"));
        assertEquals("截图中的岗位职责", sets.get("job_description"));
        assertEquals("杭州", sets.get("location"));
        assertTrue(sets.containsKey("salary"));
        assertNull(sets.get("salary"));
        assertTrue(sets.containsKey("degree"));
        assertNull(sets.get("degree"));
        assertTrue(sets.containsKey("experience"));
        assertNull(sets.get("experience"));
        assertEquals("已过滤", sets.get("delivery_status"));
        assertInstanceOf(LocalDateTime.class, sets.get("updated_at"));
        assertFalse(sets.containsKey("hr_name"));
        assertFalse(sets.containsKey("job_url"));
        assertFalse(sets.containsKey("created_at"));
        assertTrue(update.getSqlSegment().contains("id"));
        assertTrue(update.getParamNameValuePairs().containsValue(42L));
        verify(mapper, never()).insert(any(BossJobDataEntity.class));
    }

    @Test
    void anAlreadyDeliveredRecordKeepsItsStatusEvenWhenTheNewScreenshotRejectsIt() {
        BossJobDataEntity existing = new BossJobDataEntity();
        existing.setId(7L);
        existing.setDeliveryStatus("已投递");
        when(mapper.selectOne(any())).thenReturn(existing);
        when(mapper.update(isNull(), any())).thenReturn(1);
        BossJobDataEntity entity = observation();
        entity.setDeliveryStatus("已过滤");
        service.recordScreenshotObservation(entity);
        assertEquals("已投递", assignments(capturedUpdate()).get("delivery_status"));
    }

    @Test
    void aMissingRecruiterFallsBackToOneExistingRecordForTheJobId() {
        when(mapper.selectOne(any())).thenReturn(null);
        when(mapper.insert(any(BossJobDataEntity.class))).thenReturn(1);
        BossJobDataEntity entity = observation();
        entity.setEncryptUserId(null);
        service.recordScreenshotObservation(entity);
        QueryWrapper<BossJobDataEntity> query = capturedQuery();
        String sql = query.getSqlSegment();
        assertTrue(sql.contains("encrypt_id"));
        assertFalse(sql.contains("encrypt_user_id"));
        assertTrue(sql.contains("LIMIT 1"));
    }

    @Test
    void noWriteAndDatabaseFailuresPropagateToTheCaller() {
        BossJobDataEntity entity = observation();
        when(mapper.selectOne(any())).thenReturn(null);
        when(mapper.insert(any(BossJobDataEntity.class))).thenReturn(0);
        assertThrows(IllegalStateException.class, () -> service.recordScreenshotObservation(entity));
        BossJobDataEntity existing = new BossJobDataEntity();
        existing.setId(1L);
        when(mapper.selectOne(any())).thenReturn(existing);
        when(mapper.update(isNull(), any())).thenReturn(0);
        assertThrows(IllegalStateException.class, () -> service.recordScreenshotObservation(entity));
        RuntimeException databaseFailure = new IllegalStateException("database unavailable");
        when(mapper.selectOne(any())).thenThrow(databaseFailure);
        assertSame(databaseFailure, assertThrows(RuntimeException.class,
                () -> service.recordScreenshotObservation(entity)));
    }

    @Test
    void missingJobIdentityIsRejectedBeforeAnyDatabaseCall() {
        assertThrows(IllegalArgumentException.class, () -> service.recordScreenshotObservation(null));
        assertThrows(IllegalArgumentException.class, () -> service.recordScreenshotObservation(new BossJobDataEntity()));
        verifyNoInteractions(mapper);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private QueryWrapper<BossJobDataEntity> capturedQuery() {
        ArgumentCaptor<QueryWrapper> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(mapper).selectOne(captor.capture());
        return captor.getValue();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private LambdaUpdateWrapper<BossJobDataEntity> capturedUpdate() {
        ArgumentCaptor<LambdaUpdateWrapper> captor = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(mapper).update(isNull(), captor.capture());
        return captor.getValue();
    }

    private Map<String, Object> assignments(LambdaUpdateWrapper<BossJobDataEntity> update) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String assignment : update.getSqlSet().split(",")) {
            String[] pair = assignment.split("=", 2);
            String placeholder = pair[1];
            String key = placeholder.substring(placeholder.lastIndexOf('.') + 1, placeholder.indexOf('}'));
            result.put(pair[0], update.getParamNameValuePairs().get(key));
        }
        return result;
    }
}
