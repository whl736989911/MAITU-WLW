package com.fireplatform.devicegroup;

import java.sql.SQLException;
import java.util.*;
import java.util.regex.Pattern;

/** Read driver-owned structured constraint identity; never expose the exception message. */
public final class DeviceGroupConflicts {
    private DeviceGroupConflicts() { }
    private static final String NAME="uq_device_groups_tenant_name";
    private static final Pattern H2_NAME=Pattern.compile("\"(?:PUBLIC\\.)?UQ_DEVICE_GROUPS_TENANT_NAME_INDEX_[0-9A-F]+ ON (?:PUBLIC\\.)?DEVICE_GROUPS\\(TENANT_ID NULLS FIRST, NAME NULLS FIRST\\)",Pattern.CASE_INSENSITIVE);
    public static boolean name(Throwable failure){
        var seen=Collections.newSetFromMap(new IdentityHashMap<Throwable,Boolean>());for(Throwable cause=failure;cause!=null&&seen.size()<32&&seen.add(cause);cause=cause.getCause()){
            if(!(cause instanceof SQLException sql)||!"23505".equals(sql.getSQLState()))continue;
            if(sql.getClass().getName().equals("org.postgresql.util.PSQLException")){
                try{Object detail=sql.getClass().getMethod("getServerErrorMessage").invoke(sql);if(detail!=null&&NAME.equals(detail.getClass().getMethod("getConstraint").invoke(detail)))return true;}catch(ReflectiveOperationException ignored){return false;}
            }
            if(sql.getClass().getName().equals("org.h2.jdbc.JdbcSQLIntegrityConstraintViolationException")&&sql.getMessage()!=null&&H2_NAME.matcher(sql.getMessage()).find())return true;
        }return false;
    }
}
