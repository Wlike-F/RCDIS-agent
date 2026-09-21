package com.rcdis.agent;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rcdis.agent.agent.ConversationLocks;

class ConversationLocksTests {

    @Test
    void h2FallbackSerializesSameConversation() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:conversation-lock-test;DB_CLOSE_DELAY=-1");
        ConversationLocks locks = new ConversationLocks(dataSource);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximumActive = new AtomicInteger();
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            List<Future<?>> futures = new ArrayList<>();
            futures.add(executor.submit(() -> locks.runLocked("conversation-1", () -> {
                recordConcurrency(active, maximumActive, firstEntered, releaseFirst);
            })));
            assertTimeoutPreemptively(Duration.ofSeconds(2), () -> firstEntered.await());
            futures.add(executor.submit(() -> locks.runLocked("conversation-1", () -> {
                recordConcurrency(active, maximumActive, null, null);
            })));
            releaseFirst.countDown();
            assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
                for (Future<?> future : futures) {
                    future.get();
                }
            });
        } finally {
            executor.shutdownNow();
        }

        assertThat(maximumActive.get()).isEqualTo(1);
    }

    @Test
    void postgreSqlUsesSessionAdvisoryLockAndUnlock() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection detectionConnection = mock(Connection.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        Connection lockConnection = mock(Connection.class);
        PreparedStatement lockStatement = mock(PreparedStatement.class);
        PreparedStatement unlockStatement = mock(PreparedStatement.class);
        ResultSet lockResult = mock(ResultSet.class);
        ResultSet unlockResult = mock(ResultSet.class);

        when(dataSource.getConnection()).thenReturn(detectionConnection, lockConnection);
        when(detectionConnection.getMetaData()).thenReturn(metadata);
        when(metadata.getDatabaseProductName()).thenReturn("PostgreSQL");
        when(lockConnection.prepareStatement(contains("pg_advisory_lock"))).thenReturn(lockStatement);
        when(lockConnection.prepareStatement(contains("pg_advisory_unlock"))).thenReturn(unlockStatement);
        when(lockStatement.executeQuery()).thenReturn(lockResult);
        when(unlockStatement.executeQuery()).thenReturn(unlockResult);
        when(lockResult.next()).thenReturn(true);
        when(unlockResult.next()).thenReturn(true);
        when(unlockResult.getBoolean(1)).thenReturn(true);

        AtomicInteger executions = new AtomicInteger();
        new ConversationLocks(dataSource).runLocked("conversation-2", executions::incrementAndGet);

        assertThat(executions.get()).isEqualTo(1);
        verify(lockStatement).setString(1, "conversation-2");
        verify(unlockStatement).setString(1, "conversation-2");
        verify(lockStatement).executeQuery();
        verify(unlockStatement).executeQuery();
    }

    private static void recordConcurrency(
            AtomicInteger active,
            AtomicInteger maximumActive,
            CountDownLatch entered,
            CountDownLatch release) {
        int current = active.incrementAndGet();
        maximumActive.accumulateAndGet(current, Math::max);
        try {
            if (entered != null) {
                entered.countDown();
            }
            if (release != null) {
                release.await();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Conversation lock test was interrupted", exception);
        } finally {
            active.decrementAndGet();
        }
    }
}
