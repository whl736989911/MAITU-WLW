package com.fireplatform.identity;

import com.fireplatform.shared.TransactionRequirements;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.*;

/** Public first-lock boundary for commit-ordered creation. Marker is transaction-local and cleared on completion. */
@Service
public class CoordinationFence {
    private static final Object KEY=new Object();private final JdbcTemplate jdbc;private final TransactionRequirements transactions;
    public CoordinationFence(JdbcTemplate jdbc,TransactionRequirements transactions){this.jdbc=jdbc;this.transactions=transactions;}
    @Transactional(propagation=Propagation.MANDATORY) public void lock(){transactions.readCommitted();Object holder=TransactionSynchronizationManager.getResource(jdbc.getDataSource());if(holder==null)throw new IllegalStateException("Transaction connection is not bound");if(TransactionSynchronizationManager.hasResource(KEY)){if(TransactionSynchronizationManager.getResource(KEY)==holder)return;throw new IllegalStateException("Independent nested creation transaction is unsupported");}jdbc.queryForObject("SELECT id FROM security_management_lock WHERE id=1 FOR UPDATE",Integer.class);TransactionSynchronizationManager.bindResource(KEY,holder);TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void afterCompletion(int status){if(TransactionSynchronizationManager.hasResource(KEY))TransactionSynchronizationManager.unbindResource(KEY);}});}
    public void requireHeld(){transactions.readCommitted();if(!TransactionSynchronizationManager.hasResource(KEY)||TransactionSynchronizationManager.getResource(KEY)!=TransactionSynchronizationManager.getResource(jdbc.getDataSource()))throw new IllegalStateException("Creation coordination must precede identity and device locks");}
}
