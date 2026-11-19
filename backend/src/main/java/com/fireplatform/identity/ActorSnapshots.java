package com.fireplatform.identity;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Read-only current actor snapshot; caller has already locked and authorized this identity. */
@Service
public class ActorSnapshots {
    public record Actor(String id,String kind,String name) { }
    private final JdbcTemplate jdbc;
    public ActorSnapshots(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    @Transactional(propagation=Propagation.MANDATORY) public Actor current(String actor) { return jdbc.queryForObject("SELECT id,user_type,display_name FROM app_users WHERE id=?",(rs,n)->new Actor(rs.getString(1),rs.getString(2),rs.getString(3)),actor); }
}
