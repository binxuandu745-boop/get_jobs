package com.getjobs.application.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.getjobs.application.entity.BossConfigEntity;
import com.getjobs.application.entity.BossOptionEntity;
import com.getjobs.application.mapper.BlacklistMapper;
import com.getjobs.application.mapper.BossConfigMapper;
import com.getjobs.application.mapper.BossIndustryMapper;
import com.getjobs.application.mapper.BossJobDataMapper;
import com.getjobs.application.mapper.BossOptionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Mock dictionaries only: no user database or recruitment browser is opened. */
class BossJobTypeConfigurationTest {
    private BossOptionMapper options;
    private BossConfigMapper configs;
    private BossService service;
    private List<BossOptionEntity> dictionary;

    @BeforeEach
    void setUp() {
        options = mock(BossOptionMapper.class);
        configs = mock(BossConfigMapper.class);
        service = new BossService(options, mock(BossIndustryMapper.class), configs,
                mock(BlacklistMapper.class), mock(BossJobDataMapper.class), mock(DataSource.class));
        dictionary = new ArrayList<>(List.of(option("不限", "0"), option("全职", "1901"), option("兼职", "1903")));
        when(options.selectOne(any())).thenAnswer(call -> matching(call.getArgument(0)).stream().findFirst().orElse(null));
        when(options.selectCount(any())).thenAnswer(call -> (long) matching(call.getArgument(0)).size());
        when(options.selectList(any())).thenAnswer(call -> matching(call.getArgument(0)));
        when(options.insert(any(BossOptionEntity.class))).thenAnswer(call -> {
            dictionary.add(call.getArgument(0));
            return 1;
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"实习", "1902", "[实习]", "[\"1902\"]"})
    void anOldDatabaseWithoutInternshipStillLoadsTheConfirmed1902Code(String raw) {
        BossConfigEntity stored = new BossConfigEntity();
        stored.setJobType(raw);
        when(configs.selectOne(any())).thenReturn(stored);
        assertEquals("1902", service.loadBossConfig().getJobType());
        assertEquals("实习", service.normalizeJobTypeToName(raw));
        assertEquals(3, dictionary.size(), "normalization must work before dictionary repair is requested");
        verify(options, never()).insert(any(BossOptionEntity.class));
    }

    @Test
    void readingJobTypeOptionsAddsInternshipOnceWithoutChangingExistingOptions() {
        var first = service.getOptionsByType("jobType");
        var second = service.getOptionsByType("jobType");
        assertEquals(4, first.size());
        assertEquals(4, second.size());
        assertEquals(1, second.stream().filter(row -> "1902".equals(row.getCode()) && "实习".equals(row.getName())).count());
        assertTrue(second.stream().anyMatch(row -> "1903".equals(row.getCode()) && "兼职".equals(row.getName())));
        verify(options, times(1)).insert(argThat((BossOptionEntity row) ->
                "jobType".equals(row.getType()) && "实习".equals(row.getName()) && "1902".equals(row.getCode())));
    }

    @Test
    void conflictingDictionaryEntriesFailExplicitlyInsteadOfBeingOverwritten() {
        dictionary.add(option("其他类型", "1902"));
        assertThrows(IllegalStateException.class, () -> service.getOptionsByType("jobType"));
        assertThrows(IllegalStateException.class, () -> service.normalizeJobTypeToCode("实习"));
        assertEquals("其他类型", dictionary.getLast().getName());
        verify(options, never()).insert(any(BossOptionEntity.class));
    }

    @Test
    void anInternshipNameMappedToAnotherCodeAlsoCannotBeSilentlyAccepted() {
        dictionary.add(option("实习", "1999"));
        assertThrows(IllegalStateException.class, () -> service.getOptionsByType("jobType"));
        assertThrows(IllegalStateException.class, () -> service.normalizeJobTypeToCode("1999"));
        verify(options, never()).insert(any(BossOptionEntity.class));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", "不限", "0", "[]"})
    void absentOrUnrestrictedTypesRemainZero(String raw) {
        assertEquals("0", service.normalizeJobTypeToCode(raw));
        assertEquals("不限", service.normalizeJobTypeToName(raw));
    }

    @Test
    void otherKnownDictionaryNamesAndCodesKeepTheirExistingMeaning() {
        assertEquals("1901", service.normalizeJobTypeToCode("全职"));
        assertEquals("1901", service.normalizeJobTypeToCode("1901"));
        assertEquals("全职", service.normalizeJobTypeToName("1901"));
        assertEquals("1903", service.normalizeJobTypeToCode("兼职"));
        assertEquals("兼职", service.normalizeJobTypeToName("1903"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"未知类型", "1998", "[实习,全职]", "1901,1902", "实习，兼职", "[, ]", "[实习", "[\"实习\",\"实习\"]"})
    void unknownMalformedAndMultipleTypesAreRejectedBeforeConfigurationWrites(String raw) {
        assertThrows(IllegalArgumentException.class, () -> service.normalizeJobTypeToCode(raw));
        assertThrows(IllegalArgumentException.class, () -> service.normalizeJobTypeToName(raw));
        BossConfigEntity stored = new BossConfigEntity();
        stored.setJobType(raw);
        when(configs.selectOne(any())).thenReturn(stored);
        assertThrows(IllegalArgumentException.class, service::loadBossConfig);
        verify(configs, never()).updateById(any(BossConfigEntity.class));
    }

    @Test
    void dictionaryRepairCannotReportSuccessWhenItsInsertWasNotWritten() {
        when(options.insert(any(BossOptionEntity.class))).thenReturn(0);
        assertThrows(IllegalStateException.class, () -> service.getOptionsByType("jobType"));
    }

    private static BossOptionEntity option(String name, String code) {
        BossOptionEntity row = new BossOptionEntity();
        row.setType("jobType"); row.setName(name); row.setCode(code);
        return row;
    }

    private List<BossOptionEntity> matching(QueryWrapper<BossOptionEntity> query) {
        String sql = query.getSqlSegment();
        var values = query.getParamNameValuePairs().values();
        return dictionary.stream().filter(row -> values.contains(row.getType()))
                .filter(row -> !sql.contains("code =") || values.contains(row.getCode()))
                .filter(row -> !sql.contains("name =") || values.contains(row.getName())).toList();
    }
}
