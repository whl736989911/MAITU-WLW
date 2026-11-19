package com.fireplatform.processing;

import com.fireplatform.configuration.DatabaseTime;
import com.fireplatform.ingestion.CapturedMessageCatalog;
import com.fireplatform.product.PublishedMessageNormalizer;
import com.fireplatform.shared.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** N07 队列：租约、尝试、崩溃恢复。任务行只在短事务内加锁，解析在锁外进行。 */
@Service
public class ReparseQueue {
    private final JdbcTemplate jdbc; private final DatabaseTime time;
    public ReparseQueue(JdbcTemplate jdbc,DatabaseTime time) { this.jdbc=jdbc; this.time=time; }
    private static final int MAX_ATTEMPTS=3, LEASE_SECONDS=30, RETRY_DELAY_MS=500;

    @Transactional public ReparseModels.Lease claim(String owner) {
        String nowSql=time.expression();
        var rows=jdbc.query("SELECT id,status,attempt_count,lease_token,tenant_id FROM reparse_jobs WHERE (status='PENDING' AND available_at<="+nowSql+") OR (status='RUNNING' AND lease_until<="+nowSql+") ORDER BY available_at,id LIMIT 1 FOR UPDATE SKIP LOCKED",(rs,n)->new Object[]{rs.getString(1),rs.getString(2),rs.getInt(3),rs.getLong(4),rs.getString(5)});
        if(rows.isEmpty()) return null;
        var row=rows.getFirst(); String id=(String)row[0], status=(String)row[1]; int attempts=(Integer)row[2]; long token=(Long)row[3];
        var now=time.now();
        if("RUNNING".equals(status)) {
            // 上一位持有者未完成：记录 EXPIRED，交给下一次尝试（崩溃恢复，不重复计数为成功）。
            jdbc.update("UPDATE reparse_attempts SET outcome='EXPIRED',finished_at=?,error_code='LEASE_EXPIRED' WHERE job_id=? AND lease_token=? AND outcome='RUNNING' AND finished_at IS NULL",now,id,token);
        }
        if(attempts>=MAX_ATTEMPTS) {
            jdbc.update("UPDATE reparse_jobs SET status='FAILED',revision=revision+1,finished_at=?,last_error_code='ATTEMPTS_EXHAUSTED',lease_owner=NULL,lease_until=NULL WHERE id=?",now,id);
            return null;
        }
        long next=token+1; String attempt=UUID.randomUUID().toString();
        jdbc.update("UPDATE reparse_jobs SET status='RUNNING',revision=revision+1,attempt_count=attempt_count+1,lease_token=?,lease_owner=?,lease_until=?,started_at=COALESCE(started_at,?),finished_at=NULL WHERE id=?",next,owner,now.plusSeconds(LEASE_SECONDS),now,id);
        jdbc.update("INSERT INTO reparse_attempts(id,job_id,attempt_no,lease_token,worker_id,started_at,outcome) VALUES (?,?,?,?,?,?,'RUNNING')",attempt,id,attempts+1,next,owner,now);
        return new ReparseModels.Lease(id,owner,next,attempt);
    }

    @Transactional public void succeed(ReparseModels.Lease lease,String resultJson) {
        var now=time.now();
        int changed=jdbc.update("UPDATE reparse_attempts SET outcome='SUCCEEDED',finished_at=?,result_json=? WHERE id=? AND job_id=? AND lease_token=? AND outcome='RUNNING' AND finished_at IS NULL",now,resultJson,lease.attemptId(),lease.jobId(),lease.token());
        if(changed!=1) return;
        jdbc.update("UPDATE reparse_jobs SET status='SUCCEEDED',revision=revision+1,finished_at=?,last_error_code=NULL,result_json=?,lease_owner=NULL,lease_until=NULL WHERE id=? AND status='RUNNING' AND lease_owner=? AND lease_token=? AND lease_until>"+time.expression(),now,resultJson,lease.jobId(),lease.owner(),lease.token());
    }

    /** 技术失败或版本不可用：按尝试次数上限决定回到 PENDING 还是终态 FAILED。 */
    @Transactional public void retry(ReparseModels.Lease lease,String code) {
        var now=time.now();
        var counts=jdbc.queryForList("SELECT attempt_count,max_attempts FROM reparse_jobs WHERE id=? AND lease_owner=? AND lease_token=?",Integer.class,lease.jobId(),lease.owner(),lease.token());
        boolean exhausted=!counts.isEmpty() && counts.getFirst()>=MAX_ATTEMPTS;
        String outcome=exhausted?"FAILED":"FAILED";
        int changed=jdbc.update("UPDATE reparse_attempts SET outcome=?,finished_at=?,error_code=? WHERE id=? AND job_id=? AND lease_token=? AND outcome='RUNNING' AND finished_at IS NULL",outcome,now,code,lease.attemptId(),lease.jobId(),lease.token());
        if(changed!=1) return;
        String status=exhausted?"FAILED":"PENDING";
        jdbc.update("UPDATE reparse_jobs SET status=?,revision=revision+1,available_at=?,finished_at=?,last_error_code=?,lease_owner=NULL,lease_until=NULL WHERE id=? AND status='RUNNING' AND lease_owner=? AND lease_token=? AND lease_until>"+time.expression(),status,now.plusNanos(RETRY_DELAY_MS*1000000L),exhausted?now:null,code,lease.jobId(),lease.owner(),lease.token());
    }

    public ReparseModels.Parsed parse(CapturedMessageCatalog.Fact fact,PublishedMessageNormalizer normalizer,PublishedMessageNormalizer.Normalized normalized) {
        return new ReparseModels.Parsed(normalized.eventType(),normalized.batteryPercent()==null?null:normalized.batteryPercent().toPlainString(),normalized.diagnostics().isEmpty());
    }
}
