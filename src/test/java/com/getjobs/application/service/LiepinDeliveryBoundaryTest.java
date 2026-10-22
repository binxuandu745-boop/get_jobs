package com.getjobs.application.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.getjobs.application.entity.LiepinEntity;
import com.getjobs.application.mapper.LiepinConfigMapper;
import com.getjobs.application.mapper.LiepinMapper;
import com.getjobs.application.mapper.LiepinOptionMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.sqlite.SQLiteDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Uses a disposable shared in-memory SQLite database; no application or user database is opened. */
class LiepinDeliveryBoundaryTest {
    private LiepinMapper jobMapper;
    private SQLiteDataSource dataSource;
    private Connection memoryAnchor;
    private LiepinService service;

    @BeforeEach
    void createMemoryDatabase() throws SQLException {
        jobMapper = mock(LiepinMapper.class);
        dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:file:liepin-boundary-" + UUID.randomUUID() + "?mode=memory&cache=shared");
        memoryAnchor = dataSource.getConnection();
        try (Statement statement = memoryAnchor.createStatement()) {
            statement.execute("CREATE TABLE liepin_data (job_id BIGINT PRIMARY KEY, delivered INTEGER, update_time DATETIME)");
        }
        service = service(dataSource);
    }

    @AfterEach
    void closeMemoryDatabase() throws SQLException {
        memoryAnchor.close();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {0})
    void reserveAuthorizesOnlyTheFirstAttemptAndPersistsUnknown(Integer state) throws SQLException {
        insert(101L, state);

        assertTrue(service.reserveCommunication(101L));
        assertEquals(2, delivered(101L));
        assertFalse(service.reserveCommunication(101L));
        assertEquals(2, delivered(101L));
        try (Statement statement = memoryAnchor.createStatement();
             ResultSet rows = statement.executeQuery("SELECT update_time FROM liepin_data WHERE job_id=101")) {
            assertTrue(rows.next());
            assertNotNull(rows.getString(1));
        }
        verifyNoInteractions(jobMapper);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 7})
    void existingConfirmedUnknownAndUnrecognizedStatesCannotBeReserved(int state) throws SQLException {
        insert(101L, state);

        assertFalse(service.reserveCommunication(101L));
        assertEquals(state, delivered(101L));
    }

    @Test
    void competingReservationsAuthorizeAtMostOneAttempt() throws Exception {
        insert(101L, 0);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Boolean>> outcomes = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                outcomes.add(executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    try {
                        return service.reserveCommunication(101L);
                    } catch (IllegalStateException databaseContention) {
                        // SQLite shared-cache contention may fail closed instead of returning zero rows.
                        assertTrue(databaseContention.getMessage().startsWith("猎聘沟通状态写入失败: "));
                        assertNull(databaseContention.getCause());
                        return false;
                    }
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            int authorized = 0;
            for (Future<Boolean> outcome : outcomes) {
                if (outcome.get(5, TimeUnit.SECONDS)) authorized++;
            }
            assertEquals(1, authorized);
            assertEquals(2, delivered(101L));
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void explicitConfirmationChangesOnlyReservedStateAndNeverReopensIt() throws SQLException {
        insert(101L, 2);

        assertTrue(service.confirmCommunication(101L));
        assertEquals(1, delivered(101L));
        assertFalse(service.confirmCommunication(101L));
        assertFalse(service.reserveCommunication(101L));
        assertEquals(1, delivered(101L));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {0, 1, 7})
    void confirmationRequiresAnExistingReservation(Integer state) throws SQLException {
        insert(101L, state);

        assertFalse(service.confirmCommunication(101L));
        assertEquals(state, delivered(101L));
    }

    @Test
    void missingRowsAndNullIdsCannotAuthorizeOrConfirm() {
        assertFalse(service.reserveCommunication(404L));
        assertFalse(service.confirmCommunication(404L));
        DataSource neverOpened = mock(DataSource.class);
        LiepinService invalidIds = service(neverOpened);
        assertFalse(invalidIds.reserveCommunication(null));
        assertFalse(invalidIds.confirmCommunication(null));
        verifyNoInteractions(neverOpened, jobMapper);
    }

    @Test
    void databaseAcquisitionFailuresAreSanitizedAndNeverReportSuccess() throws SQLException {
        DataSource failing = mock(DataSource.class);
        when(failing.getConnection()).thenThrow(new SQLException("private database path and token"));
        LiepinService unavailable = service(failing);

        for (Runnable action : List.<Runnable>of(
                () -> unavailable.reserveCommunication(101L),
                () -> unavailable.confirmCommunication(101L))) {
            IllegalStateException failure = assertThrows(IllegalStateException.class, action::run);
            assertEquals("猎聘沟通状态写入失败: SQLException", failure.getMessage());
            assertNull(failure.getCause());
        }
        verifyNoInteractions(jobMapper);
    }

    @Test
    void failedConfirmationLeavesTheReservationUnknownAndNonRetryable() throws SQLException {
        insert(101L, 0);
        assertTrue(service.reserveCommunication(101L));
        DataSource failing = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(failing.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(any())).thenReturn(statement);
        when(statement.executeUpdate()).thenThrow(new SQLException("sensitive SQL details"));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> service(failing).confirmCommunication(101L));

        assertEquals("猎聘沟通状态写入失败: SQLException", failure.getMessage());
        assertNull(failure.getCause());
        assertEquals(2, delivered(101L));
        assertFalse(service.reserveCommunication(101L));
        verify(statement).setLong(eq(2), eq(101L));
        verify(statement).close();
        verify(connection).close();
    }

    @Test
    void manualCommitConnectionsPersistReservationBeforeReturningSuccess() throws SQLException {
        insert(101L, 0);
        DataSource manualCommit = mock(DataSource.class);
        Connection connection = dataSource.getConnection();
        connection.setAutoCommit(false);
        when(manualCommit.getConnection()).thenReturn(connection);

        assertTrue(service(manualCommit).reserveCommunication(101L));

        assertEquals(2, delivered(101L));
        assertTrue(connection.isClosed());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {0, 1, 2, 7})
    void ordinarySnapshotUpdatesCannotResetDeliveryState(Integer state) {
        LiepinEntity stored = snapshot(101L, state);
        LiepinEntity incoming = snapshot(101L, 0);
        when(jobMapper.selectById(101L)).thenReturn(stored);

        service.saveOrUpdateSnapshot(incoming);

        assertEquals(state == null ? 0 : state, incoming.getDelivered());
        // Even a stale snapshot read must not put a delivery-state assignment in the update.
        verify(jobMapper).update(isNull(), argThat(update ->
                update.getSqlSet().contains("update_time") && !update.getSqlSet().contains("delivered")));
    }

    @Test
    void existingBatchSnapshotsRetainUnknownAndHistoricalConfirmedStates() {
        LiepinEntity unknown = snapshot(101L, 2);
        LiepinEntity historical = snapshot(202L, 1);
        LiepinEntity apiUnknown = snapshot(101L, 0);
        LiepinEntity apiHistorical = snapshot(202L, 0);
        when(jobMapper.selectList(any())).thenReturn(List.of(historical, unknown));

        service.insertSnapshotsIfNotExistsBatch(List.of(apiUnknown, apiHistorical));

        assertEquals(2, apiUnknown.getDelivered());
        assertEquals(1, apiHistorical.getDelivered());
        assertEquals(2, unknown.getDelivered());
        assertEquals(1, historical.getDelivered());
        verify(jobMapper).selectList(any());
        verifyNoMoreInteractions(jobMapper);
    }

    @Test
    void statsExposeUnknownSeparatelyFromUncontactedJobs() {
        when(jobMapper.selectList(any())).thenReturn(List.of(
                snapshot(101L, 1), snapshot(202L, 2), snapshot(303L, 0), snapshot(404L, null)));

        LiepinService.StatsResponse stats = service.getLiepinStats(null, null, null, null, null, null, null);

        assertEquals(4, stats.kpi.total);
        assertEquals(1, stats.kpi.delivered);
        assertEquals(1, stats.kpi.unknown);
        assertEquals(2, stats.kpi.pending);
        Map<String, Long> counts = stats.charts.byStatus.stream()
                .collect(Collectors.toMap(item -> item.name, item -> item.value));
        assertEquals(Map.of("已投递", 1L, "待确认", 1L, "未投递", 2L), counts);
    }

    @Test
    void unknownStatusFilterSelectsStateTwoForStatsAndList() {
        List<QueryWrapper<LiepinEntity>> queries = new ArrayList<>();
        when(jobMapper.selectList(any())).thenAnswer(invocation -> {
            QueryWrapper<LiepinEntity> query = invocation.getArgument(0);
            query.getSqlSegment();
            queries.add(query);
            return List.of();
        });

        service.getLiepinStats(List.of("待确认"), null, null, null, null, null, null);
        service.listLiepinJobs(List.of("待确认"), null, null, null, null, null, null, 1, 20);

        assertEquals(2, queries.size());
        for (QueryWrapper<LiepinEntity> query : queries) {
            assertTrue(query.getSqlSegment().contains("delivered IN"));
            assertEquals(List.of(2), new ArrayList<>(query.getParamNameValuePairs().values()));
        }
    }

    private LiepinService service(DataSource source) {
        return new LiepinService(mock(LiepinConfigMapper.class), mock(LiepinOptionMapper.class), jobMapper, source);
    }

    private void insert(long jobId, Integer state) throws SQLException {
        try (PreparedStatement statement = memoryAnchor.prepareStatement("INSERT INTO liepin_data (job_id, delivered) VALUES (?, ?)")) {
            statement.setLong(1, jobId);
            if (state == null) statement.setNull(2, Types.INTEGER);
            else statement.setInt(2, state);
            statement.executeUpdate();
        }
    }

    private Integer delivered(long jobId) throws SQLException {
        try (PreparedStatement statement = memoryAnchor.prepareStatement("SELECT delivered FROM liepin_data WHERE job_id=?")) {
            statement.setLong(1, jobId);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                int state = rows.getInt(1);
                return rows.wasNull() ? null : state;
            }
        }
    }

    private static LiepinEntity snapshot(long jobId, Integer state) {
        LiepinEntity snapshot = new LiepinEntity();
        snapshot.setJobId(jobId);
        snapshot.setDelivered(state);
        return snapshot;
    }
}
