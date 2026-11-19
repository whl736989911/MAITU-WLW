package com.fireplatform.notification;

import com.fireplatform.identity.ActorSnapshots;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service @Transactional(propagation=Propagation.MANDATORY)
public class NotificationJournal {
    private final JdbcTemplate jdbc;
    public NotificationJournal(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public void append(String record,String action,String before,String after,OffsetDateTime at,ActorSnapshots.Actor actor,String reason){jdbc.update("INSERT INTO notification_history(id,record_id,action,from_status,to_status,recorded_at,actor_kind,actor_id,actor_name,reason) VALUES (?,?,?,?,?,?,?,?,?,?)",UUID.randomUUID().toString(),record,action,before,after,at,actor==null?"SYSTEM":"PLATFORM",actor==null?null:actor.id(),actor==null?null:actor.name(),reason);}
}
