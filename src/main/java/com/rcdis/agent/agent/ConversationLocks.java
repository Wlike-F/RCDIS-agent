package com.rcdis.agent.agent;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.locks.ReentrantLock;

import javax.sql.DataSource;

import org.springframework.stereotype.Component;

/**
 * Serializes conversation mutations locally and across PostgreSQL application instances.
 *
 * <p>PostgreSQL session advisory locks are held on a dedicated connection for the complete
 * critical section. H2 and other test databases retain in-process serialization without running
 * PostgreSQL-specific SQL. Fixed stripes avoid an unbounded registry of conversation ids.</p>
 */
@Component
public class ConversationLocks {

    private static final int STRIPE_COUNT = 256;
    private static final String LOCK_SQL = "SELECT pg_advisory_lock(hashtextextended(?, 0))";
    private static final String UNLOCK_SQL = "SELECT pg_advisory_unlock(hashtextextended(?, 0))";

    private final DataSource dataSource;
    private final ReentrantLock[] localStripes;

    public ConversationLocks(DataSource dataSource) {
        this.dataSource = dataSource;
        this.localStripes = createStripes();
    }

    public void runLocked(String conversationId, Runnable operation) {
        if (conversationId == null || conversationId.isBlank()) {
            throw new IllegalArgumentException("Conversation id is required for locking");
        }
        ReentrantLock localLock = localStripes[stripeIndex(conversationId)];
        localLock.lock();
        try {
            if (isPostgreSql()) {
                runWithPostgreSqlLock(conversationId, operation);
                return;
            }
            operation.run();
        } finally {
            localLock.unlock();
        }
    }

    private void runWithPostgreSqlLock(String conversationId, Runnable operation) {
        try (Connection connection = dataSource.getConnection()) {
            executeLockStatement(connection, LOCK_SQL, conversationId, false);
            RuntimeException operationFailure = null;
            try {
                operation.run();
            } catch (RuntimeException exception) {
                operationFailure = exception;
                throw exception;
            } finally {
                try {
                    executeLockStatement(connection, UNLOCK_SQL, conversationId, true);
                } catch (RuntimeException unlockFailure) {
                    if (operationFailure != null) {
                        operationFailure.addSuppressed(unlockFailure);
                    } else {
                        throw unlockFailure;
                    }
                }
            }
        } catch (SQLException exception) {
            throw new IllegalStateException(
                    "Failed to obtain database connection for conversation lock: " + conversationId,
                    exception);
        }
    }

    private void executeLockStatement(
            Connection connection, String sql, String conversationId, boolean verifyUnlocked) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, conversationId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new IllegalStateException(
                            "Conversation advisory lock returned no result: " + conversationId);
                }
                if (verifyUnlocked && !result.getBoolean(1)) {
                    throw new IllegalStateException(
                            "Conversation advisory lock was not owned by this session: " + conversationId);
                }
            }
        } catch (SQLException exception) {
            throw new IllegalStateException(
                    "Conversation advisory lock SQL failed: " + conversationId,
                    exception);
        }
    }

    private boolean isPostgreSql() {
        try (Connection connection = dataSource.getConnection()) {
            return "PostgreSQL".equalsIgnoreCase(connection.getMetaData().getDatabaseProductName());
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to detect database for conversation locking", exception);
        }
    }

    private static ReentrantLock[] createStripes() {
        ReentrantLock[] stripes = new ReentrantLock[STRIPE_COUNT];
        for (int index = 0; index < stripes.length; index++) {
            stripes[index] = new ReentrantLock();
        }
        return stripes;
    }

    private static int stripeIndex(String conversationId) {
        return Math.floorMod(conversationId.hashCode(), STRIPE_COUNT);
    }
}
