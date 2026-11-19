package com.fireplatform.shared;

/** SQL over trusted aliases/clock expressions; user values are always parameters. */
public final class NotificationSuppressionSql {
    private NotificationSuppressionSql() { }

    public static String activeAt(String alias,String at) {
        return alias+".status='ACTIVE' AND "+alias+".enabled=TRUE AND "+alias+".deleted=FALSE AND "
            +alias+".starts_at<="+at+" AND "+alias+".ends_at>"+at;
    }
}
