package com.fireplatform.shared;

import java.sql.Connection;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Assert the physical connection, not a nullable transaction annotation hint. Never changes caller isolation. */
@Service
public class TransactionRequirements {
    private final JdbcTemplate jdbc;
    public TransactionRequirements(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public void repeatableReadOnly(){
        if(!TransactionSynchronizationManager.isActualTransactionActive()||!TransactionSynchronizationManager.isCurrentTransactionReadOnly())throw ApiError.conflict("TRANSACTION_ISOLATION_REQUIRED","此读取必须在实际只读REPEATABLE_READ事务内执行");
        Integer isolation=jdbc.execute((ConnectionCallback<Integer>)Connection::getTransactionIsolation);
        if(isolation==null||isolation!=Connection.TRANSACTION_REPEATABLE_READ)throw ApiError.conflict("TRANSACTION_ISOLATION_REQUIRED","此读取必须在实际只读REPEATABLE_READ事务内执行");
    }
    public void readCommitted(){if(!TransactionSynchronizationManager.isActualTransactionActive()||TransactionSynchronizationManager.isCurrentTransactionReadOnly())throw new IllegalStateException("An active writable transaction is required");Integer isolation=jdbc.execute((ConnectionCallback<Integer>)Connection::getTransactionIsolation);if(isolation==null||isolation!=Connection.TRANSACTION_READ_COMMITTED)throw ApiError.conflict("TRANSACTION_ISOLATION_REQUIRED","此写入必须在READ_COMMITTED事务内执行");}
}
