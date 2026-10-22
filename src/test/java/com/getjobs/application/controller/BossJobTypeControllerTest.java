package com.getjobs.application.controller;

import com.getjobs.application.entity.BossConfigEntity;
import com.getjobs.application.mapper.BlacklistMapper;
import com.getjobs.application.mapper.BossConfigMapper;
import com.getjobs.application.mapper.BossIndustryMapper;
import com.getjobs.application.mapper.BossJobDataMapper;
import com.getjobs.application.mapper.BossOptionMapper;
import com.getjobs.application.service.BossService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import javax.sql.DataSource;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class BossJobTypeControllerTest {
    private BossOptionMapper options;
    private BossConfigMapper configs;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        options = mock(BossOptionMapper.class);
        configs = mock(BossConfigMapper.class);
        BossService service = new BossService(options, mock(BossIndustryMapper.class), configs,
                mock(BlacklistMapper.class), mock(BossJobDataMapper.class), mock(DataSource.class));
        when(configs.updateById(any(BossConfigEntity.class))).thenReturn(1);
        mvc = MockMvcBuilders.standaloneSetup(new BossConfigController(service)).build();
    }

    @Test
    void theConfirmedInternshipCodeIsSavedAsTheSingleInternshipNameInAnOldDatabase() throws Exception {
        mvc.perform(put("/api/boss/config").contentType("application/json")
                        .content("{\"id\":7,\"jobType\":\"1902\",\"sayHi\":\"saved greeting\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.jobType").value("实习"));
        verify(configs).updateById(argThat((BossConfigEntity row) -> Long.valueOf(7).equals(row.getId())
                && "实习".equals(row.getJobType()) && "saved greeting".equals(row.getSayHi())));
        verify(options, never()).insert(any(com.getjobs.application.entity.BossOptionEntity.class));
    }

    @Test
    void invalidOrMultipleJobTypesReturn400AndNeverWriteConfiguration() throws Exception {
        for (String type : new String[]{"unknown-type", "1999", "[1901,1902]", "实习，兼职"}) {
            mvc.perform(put("/api/boss/config").contentType("application/json")
                            .content("{\"id\":7,\"jobType\":\"" + type + "\"}"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false));
        }
        verify(configs, never()).updateById(any(BossConfigEntity.class));
        verify(configs, never()).insert(any(BossConfigEntity.class));
    }

    @Test
    void databaseFailuresRemain500AndDoNotExposeTheUnderlyingDetails() throws Exception {
        when(options.selectOne(any())).thenThrow(new DataAccessResourceFailureException("private database path"));
        mvc.perform(put("/api/boss/config").contentType("application/json")
                        .content("{\"id\":7,\"jobType\":\"1902\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().string(not(containsString("private database path"))));
        verify(configs, never()).updateById(any(BossConfigEntity.class));
    }

    @Test
    void aConflictingInternshipDictionaryReturns500WithoutOverwritingIt() throws Exception {
        var conflict = new com.getjobs.application.entity.BossOptionEntity();
        conflict.setType("jobType"); conflict.setCode("1902"); conflict.setName("unexpected-name");
        when(options.selectOne(any())).thenReturn(conflict);
        mvc.perform(put("/api/boss/config").contentType("application/json")
                        .content("{\"id\":7,\"jobType\":\"实习\"}"))
                .andExpect(status().isInternalServerError());
        verify(configs, never()).updateById(any(BossConfigEntity.class));
        verify(options, never()).insert(any(com.getjobs.application.entity.BossOptionEntity.class));
    }
}
