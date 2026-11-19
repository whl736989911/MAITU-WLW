package com.fireplatform.simcard;

import java.sql.SQLException;
import java.util.*;
import java.util.regex.Pattern;

/** Only the driver-identified tenant/ICCID unique constraint is a business conflict. */
public final class SimCardConflicts {
    private SimCardConflicts(){ }
    private static final String NAME="uq_sim_cards_tenant_iccid";
    private static final Pattern H2_NAME = Pattern.compile(
            "\\AUnique index or primary key violation: \"(?:PUBLIC\\.)?(UQ_SIM_CARDS_TENANT_ICCID) INDEX "
                    + "(?:PUBLIC\\.)?\\1_INDEX_[0-9A-F]+ ON (?:PUBLIC\\.)?SIM_CARDS\\(TENANT_ID NULLS (FIRST|LAST), ICCID NULLS \\2\\)",
            Pattern.CASE_INSENSITIVE);
    public static boolean iccid(Throwable failure){
        Set<Throwable> seen=Collections.newSetFromMap(new IdentityHashMap<>());for(Throwable cause=failure;cause!=null&&seen.size()<32&&seen.add(cause);cause=cause.getCause()){
            if(!(cause instanceof SQLException sql)||!"23505".equals(sql.getSQLState()))continue;
            if(sql.getClass().getName().equals("org.postgresql.util.PSQLException")){
                try{Object detail=sql.getClass().getMethod("getServerErrorMessage").invoke(sql);if(detail!=null&&NAME.equals(detail.getClass().getMethod("getConstraint").invoke(detail)))return true;}catch(ReflectiveOperationException ignored){return false;}
            }
            if(sql.getClass().getName().equals("org.h2.jdbc.JdbcSQLIntegrityConstraintViolationException")&&sql.getMessage()!=null&&H2_NAME.matcher(sql.getMessage()).find())return true;
        }return false;
    }
}
