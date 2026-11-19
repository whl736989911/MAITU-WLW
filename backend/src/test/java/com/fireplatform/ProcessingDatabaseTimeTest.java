package com.fireplatform;

import com.fireplatform.configuration.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

class ProcessingDatabaseTimeTest {
    @Test void immediateAvailabilityIsNeverRoundedPastActualStatementClock() {
        String url="jdbc:h2:mem:processing-clock;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"; var data=new DriverManagerDataSource(url,"sa",""); var jdbc=new JdbcTemplate(data); jdbc.execute("CREATE TABLE q(id INT PRIMARY KEY,available TIMESTAMP WITH TIME ZONE)");
        var env=new MockEnvironment().withProperty("spring.datasource.url",url); env.setActiveProfiles("test"); var time=new DatabaseTime(jdbc,new RuntimeConfig(env)); var tx=new TransactionTemplate(new DataSourceTransactionManager(data));
        for(int id=0;id<128;id++) { final int key=id; tx.executeWithoutResult(status->{ var now=time.now(); assertThat(now.getNano()%1000).isZero(); jdbc.update("INSERT INTO q(id,available) VALUES (?,?)",key,now); });
            tx.executeWithoutResult(status->{ var due=jdbc.queryForList("SELECT id FROM q WHERE id=? AND available<="+time.expression()+" FOR UPDATE SKIP LOCKED",Integer.class,key); assertThat(due).containsExactly(key); });
        }
    }
}
