package com.fireplatform;

import java.sql.*;
import org.h2.api.Trigger;

/** Test-only deterministic fault: expire the owning lease after business writes, before final CAS. Not shipped. */
public class LeaseExpiryTrigger implements Trigger {
    @Override public void fire(Connection connection,Object[] oldRow,Object[] newRow) throws SQLException {
        try(var statement=connection.prepareStatement("UPDATE processing_tasks SET lease_until=TIMESTAMP WITH TIME ZONE '2000-01-01 00:00:00+00' WHERE id=?")) { statement.setString(1,(String)newRow[0]); statement.executeUpdate(); }
    }
}
