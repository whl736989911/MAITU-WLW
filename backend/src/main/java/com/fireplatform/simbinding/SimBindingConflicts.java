package com.fireplatform.simbinding;

import java.sql.SQLException;
import java.util.*;
import java.util.regex.Pattern;

/** Only this real driver-owned unique identity denotes a device occupied by another card. */
public final class SimBindingConflicts {
    private SimBindingConflicts(){ }
    private static final String NAME="uq_sim_card_associations_tenant_device";
    private static final Pattern H2 = Pattern.compile(
            "\\AUnique index or primary key violation: \"(?:PUBLIC\\.)?(UQ_SIM_CARD_ASSOCIATIONS_TENANT_DEVICE) INDEX "
                    + "(?:PUBLIC\\.)?\\1_INDEX_[0-9A-F]+ ON (?:PUBLIC\\.)?SIM_CARD_ASSOCIATIONS\\(TENANT_ID NULLS (FIRST|LAST), DEVICE_ID NULLS \\2\\)",
            Pattern.CASE_INSENSITIVE);
    public static boolean occupied(Throwable failure){Set<Throwable> seen=Collections.newSetFromMap(new IdentityHashMap<>());for(Throwable cause=failure;cause!=null&&seen.size()<32&&seen.add(cause);cause=cause.getCause()){
        if(!(cause instanceof SQLException sql)||!"23505".equals(sql.getSQLState()))continue;
        if(sql.getClass().getName().equals("org.postgresql.util.PSQLException")){try{Object details=sql.getClass().getMethod("getServerErrorMessage").invoke(sql);if(details!=null&&NAME.equals(details.getClass().getMethod("getConstraint").invoke(details)))return true;}catch(ReflectiveOperationException ignored){return false;}}
        if(sql.getClass().getName().equals("org.h2.jdbc.JdbcSQLIntegrityConstraintViolationException")&&sql.getMessage()!=null&&H2.matcher(sql.getMessage()).find())return true;
    }return false;}
}
