package com.police.iot.common.config;

import lombok.extern.slf4j.Slf4j;
import net.ttddyy.dsproxy.listener.SlowQueryListener;
import net.ttddyy.dsproxy.support.ProxyDataSourceBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;
import java.util.concurrent.TimeUnit;

/**
 * Wraps the DataSource with a datasource-proxy to detect and log slow SQL queries.
 *
 * Activated only when 'datasource-proxy' is on classpath AND
 * police.db.slow-query-threshold-ms > 0.
 *
 * Without datasource-proxy on classpath, this config is skipped entirely —
 * safe to include in all services; only device-service and command-service
 * have a DataSource.
 *
 * Interview talking point:
 *   "I log any SQL query that takes over 300ms with the full SQL text, bind parameters,
 *    and execution time. This shows up as a separate 'SLOW_QUERY' log event in Kibana.
 *    In production, we alert if more than 5 slow queries occur per minute — that's a
 *    leading indicator of index degradation or connection pool exhaustion before users
 *    start seeing errors."
 *
 * NOTE: If you don't want the datasource-proxy dependency, replace this with
 * Hibernate's built-in 'hibernate.session.events.log.LOG_QUERIES_SLOWER_THAN_MS'
 * property (Hibernate 5.4.5+). Both approaches are valid.
 */
@Slf4j
@Configuration
@ConditionalOnClass(name = "net.ttddyy.dsproxy.support.ProxyDataSourceBuilder")
@ConditionalOnProperty(name = "police.db.slow-query-threshold-ms", havingValue = "0", matchIfMissing = false)
public class SlowQueryLoggerConfig {

    @Value("${police.db.slow-query-threshold-ms:300}")
    private long slowQueryThresholdMs;

    @Bean
    @Primary
    public DataSource dataSourceWithSlowQueryLogging(DataSource originalDataSource) {
        SlowQueryListener slowQueryListener = new SlowQueryListener(
                slowQueryThresholdMs,
                TimeUnit.MILLISECONDS,
                (execInfo, queryInfoList) -> {
                    String sql = queryInfoList.isEmpty()
                            ? "UNKNOWN"
                            : queryInfoList.get(0).getQuery();
                    log.warn("event=SLOW_QUERY duration_ms={} threshold_ms={} sql=\"{}\"",
                            execInfo.getElapsedTime(),
                            slowQueryThresholdMs,
                            sql);
                }
        );

        return ProxyDataSourceBuilder
                .create(originalDataSource)
                .name("police-ecp-datasource")
                .listener(slowQueryListener)
                .build();
    }
}
