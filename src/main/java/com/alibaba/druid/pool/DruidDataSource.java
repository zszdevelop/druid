/*
 * Copyright 1999-2018 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.druid.pool;

import static com.alibaba.druid.util.Utils.getBoolean;

import java.io.Closeable;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.ServiceLoader;
import java.util.StringTokenizer;
import java.util.TimeZone;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import javax.management.JMException;
import javax.management.MBeanRegistration;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.naming.NamingException;
import javax.naming.Reference;
import javax.naming.Referenceable;
import javax.naming.StringRefAddr;
import javax.sql.ConnectionEvent;
import javax.sql.ConnectionEventListener;
import javax.sql.ConnectionPoolDataSource;
import javax.sql.PooledConnection;

import com.alibaba.druid.Constants;
import com.alibaba.druid.DbType;
import com.alibaba.druid.TransactionTimeoutException;
import com.alibaba.druid.VERSION;
import com.alibaba.druid.filter.AutoLoad;
import com.alibaba.druid.filter.Filter;
import com.alibaba.druid.filter.FilterChainImpl;
import com.alibaba.druid.mock.MockDriver;
import com.alibaba.druid.pool.DruidPooledPreparedStatement.PreparedStatementKey;
import com.alibaba.druid.pool.vendor.*;
import com.alibaba.druid.proxy.DruidDriver;
import com.alibaba.druid.proxy.jdbc.DataSourceProxyConfig;
import com.alibaba.druid.proxy.jdbc.TransactionInfo;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.ast.statement.SQLSelectQuery;
import com.alibaba.druid.sql.ast.statement.SQLSelectQueryBlock;
import com.alibaba.druid.sql.ast.statement.SQLSelectStatement;
import com.alibaba.druid.sql.parser.SQLParserUtils;
import com.alibaba.druid.sql.parser.SQLStatementParser;
import com.alibaba.druid.stat.DruidDataSourceStatManager;
import com.alibaba.druid.stat.JdbcDataSourceStat;
import com.alibaba.druid.stat.JdbcSqlStat;
import com.alibaba.druid.stat.JdbcSqlStatValue;
import com.alibaba.druid.support.clickhouse.BalancedClickhouseDriver;
import com.alibaba.druid.support.logging.Log;
import com.alibaba.druid.support.logging.LogFactory;
import com.alibaba.druid.util.JMXUtils;
import com.alibaba.druid.util.JdbcConstants;
import com.alibaba.druid.util.JdbcUtils;
import com.alibaba.druid.util.StringUtils;
import com.alibaba.druid.util.Utils;
import com.alibaba.druid.wall.WallFilter;
import com.alibaba.druid.wall.WallProviderStatValue;

/**
 * @author ljw [ljw2083@alibaba-inc.com]
 * @author wenshao [szujobs@hotmail.com]
 */
public class DruidDataSource extends DruidAbstractDataSource implements DruidDataSourceMBean, ManagedDataSource, Referenceable, Closeable, Cloneable, ConnectionPoolDataSource, MBeanRegistration {

    private final static Log LOG = LogFactory.getLog(DruidDataSource.class);
    private static final long serialVersionUID = 1L;
    // stats
    private volatile long recycleErrorCount = 0L;
    private long connectCount = 0L;
    private long closeCount = 0L;
    private volatile long connectErrorCount = 0L;
    private long recycleCount = 0L;
    private long removeAbandonedCount = 0L;
    private long notEmptyWaitCount = 0L;
    private long notEmptySignalCount = 0L;
    private long notEmptyWaitNanos = 0L;
    private int keepAliveCheckCount = 0;
    private int activePeak = 0;
    private long activePeakTime = 0;
    private int poolingPeak = 0;
    private long poolingPeakTime = 0;
    // store
    private volatile DruidConnectionHolder[] connections;
    private int poolingCount = 0;
    private int activeCount = 0;
    private volatile long discardCount = 0;
    private int notEmptyWaitThreadCount = 0;
    private int notEmptyWaitThreadPeak = 0;
    //
    private DruidConnectionHolder[] evictConnections;
    private DruidConnectionHolder[] keepAliveConnections;

    // threads
    private volatile ScheduledFuture<?> destroySchedulerFuture;
    private DestroyTask destroyTask;

    private volatile Future<?> createSchedulerFuture;

    private CreateConnectionThread createConnectionThread;
    private DestroyConnectionThread destroyConnectionThread;
    private LogStatsThread logStatsThread;
    private int createTaskCount;

    private volatile long createTaskIdSeed = 1L;
    private long[] createTasks;

    private final CountDownLatch initedLatch = new CountDownLatch(2);

    private volatile boolean enable = true;

    private boolean resetStatEnable = true;
    private volatile long resetCount = 0L;

    private String initStackTrace;

    private volatile boolean closing = false;
    private volatile boolean closed = false;
    private long closeTimeMillis = -1L;

    protected JdbcDataSourceStat dataSourceStat;

    private boolean useGlobalDataSourceStat = false;
    private boolean mbeanRegistered = false;
    public static ThreadLocal<Long> waitNanosLocal = new ThreadLocal<Long>();
    private boolean logDifferentThread = true;
    private volatile boolean keepAlive = false;
    private boolean asyncInit = false;
    protected boolean killWhenSocketReadTimeout = false;
    protected boolean checkExecuteTime = false;

    private static List<Filter> autoFilters = null;
    private boolean loadSpifilterSkip = false;
    private volatile DataSourceDisableException disableException = null;

    protected static final AtomicLongFieldUpdater<DruidDataSource> recycleErrorCountUpdater
            = AtomicLongFieldUpdater.newUpdater(DruidDataSource.class, "recycleErrorCount");
    protected static final AtomicLongFieldUpdater<DruidDataSource> connectErrorCountUpdater
            = AtomicLongFieldUpdater.newUpdater(DruidDataSource.class, "connectErrorCount");
    protected static final AtomicLongFieldUpdater<DruidDataSource> resetCountUpdater
            = AtomicLongFieldUpdater.newUpdater(DruidDataSource.class, "resetCount");
    protected static final AtomicLongFieldUpdater<DruidDataSource> createTaskIdSeedUpdater
            = AtomicLongFieldUpdater.newUpdater(DruidDataSource.class, "createTaskIdSeed");

    public DruidDataSource() {
        this(false);
    }

    public DruidDataSource(boolean fairLock) {
        super(fairLock);

        configFromPropety(System.getProperties());
    }

    public boolean isAsyncInit() {
        return asyncInit;
    }

    public void setAsyncInit(boolean asyncInit) {
        this.asyncInit = asyncInit;
    }

    public void configFromPropety(Properties properties) {
        {
            String property = properties.getProperty("druid.name");
            if (property != null) {
                this.setName(property);
            }
        }
        {
            String property = properties.getProperty("druid.url");
            if (property != null) {
                this.setUrl(property);
            }
        }
        {
            String property = properties.getProperty("druid.username");
            if (property != null) {
                this.setUsername(property);
            }
        }
        {
            String property = properties.getProperty("druid.password");
            if (property != null) {
                this.setPassword(property);
            }
        }
        {
            Boolean value = getBoolean(properties, "druid.testWhileIdle");
            if (value != null) {
                this.testWhileIdle = value;
            }
        }
        {
            Boolean value = getBoolean(properties, "druid.testOnBorrow");
            if (value != null) {
                this.testOnBorrow = value;
            }
        }
        {
            String property = properties.getProperty("druid.validationQuery");
            if (property != null && property.length() > 0) {
                this.setValidationQuery(property);
            }
        }
        {
            Boolean value = getBoolean(properties, "druid.useGlobalDataSourceStat");
            if (value != null) {
                this.setUseGlobalDataSourceStat(value);
            }
        }
        {
            Boolean value = getBoolean(properties, "druid.useGloalDataSourceStat"); // compatible for early versions
            if (value != null) {
                this.setUseGlobalDataSourceStat(value);
            }
        }
        {
            Boolean value = getBoolean(properties, "druid.asyncInit"); // compatible for early versions
            if (value != null) {
                this.setAsyncInit(value);
            }
        }
        {
            String property = properties.getProperty("druid.filters");

            if (property != null && property.length() > 0) {
                try {
                    this.setFilters(property);
                } catch (SQLException e) {
                    LOG.error("setFilters error", e);
                }
            }
        }
        {
            String property = properties.getProperty(Constants.DRUID_TIME_BETWEEN_LOG_STATS_MILLIS);
            if (property != null && property.length() > 0) {
                try {
                    long value = Long.parseLong(property);
                    this.setTimeBetweenLogStatsMillis(value);
                } catch (NumberFormatException e) {
                    LOG.error("illegal property '" + Constants.DRUID_TIME_BETWEEN_LOG_STATS_MILLIS + "'", e);
                }
            }
        }
        {
            String property = properties.getProperty(Constants.DRUID_STAT_SQL_MAX_SIZE);
            if (property != null && property.length() > 0) {
                try {
                    int value = Integer.parseInt(property);
                    if (dataSourceStat != null) {
                        dataSourceStat.setMaxSqlSize(value);
                    }
                } catch (NumberFormatException e) {
                    LOG.error("illegal property '" + Constants.DRUID_STAT_SQL_MAX_SIZE + "'", e);
                }
            }
        }
        {
            Boolean value = getBoolean(properties, "druid.clearFiltersEnable");
            if (value != null) {
                this.setClearFiltersEnable(value);
            }
        }
        {
            Boolean value = getBoolean(properties, "druid.resetStatEnable");
            if (value != null) {
                this.setResetStatEnable(value);
            }
        }
        {
            String property = properties.getProperty("druid.notFullTimeoutRetryCount");
            if (property != null && property.length() > 0) {
                try {
                    int value = Integer.parseInt(property);
                    this.setNotFullTimeoutRetryCount(value);
                } catch (NumberFormatException e) {
                    LOG.error("illegal property 'druid.notFullTimeoutRetryCount'", e);
                }
            }
        }
        {
            String property = properties.getProperty("druid.timeBetweenEvictionRunsMillis");
            if (property != null && property.length() > 0) {
                try {
                    long value = Long.parseLong(property);
                    this.setTimeBetweenEvictionRunsMillis(value);
                } catch (NumberFormatException e) {
                    LOG.error("illegal property 'druid.timeBetweenEvictionRunsMillis'", e);
                }
            }
        }
        {
            String property = properties.getProperty("druid.maxWaitThreadCount");
            if (property != null && property.length() > 0) {
                try {
                    int value = Integer.parseInt(property);
                    this.setMaxWaitThreadCount(value);
                } catch (NumberFormatException e) {
                    LOG.error("illegal property 'druid.maxWaitThreadCount'", e);
                }
            }
        }
        {
            String property = properties.getProperty("druid.maxWait");
            if (property != null && property.length() > 0) {
                try {
                    int value = Integer.parseInt(property);
                    this.setMaxWait(value);
                } catch (NumberFormatException e) {
                    LOG.error("illegal property 'druid.maxWait'", e);
                }
            }
        }
        {
            Boolean value = getBoolean(properties, "druid.failFast");
            if (value != null) {
                this.setFailFast(value);
            }
        }
        {
            String property = properties.getProperty("druid.phyTimeoutMillis");
            if (property != null && property.length() > 0) {
                try {
                    long value = Long.parseLong(property);
                    this.setPhyTimeoutMillis(value);
                } catch (NumberFormatException e) {
                    LOG.error("illegal property 'druid.phyTimeoutMillis'", e);
                }
            }
        }
        {
            String property = properties.getProperty("druid.phyMaxUseCount");
            if (property != null && property.length() > 0) {
                try {
                    long value = Long.parseLong(property);
                    this.setPhyMaxUseCount(value);
                } catch (NumberFormatException e) {
                    LOG.error("illegal property 'druid.phyMaxUseCount'", e);
                }
            }
        }
        {
            String property = properties.getProperty("druid.minEvictableIdleTimeMillis");
            if (property != null && property.length() > 0) {
                try {
                    long value = Long.parseLong(property);
                    this.setMinEvictableIdleTimeMillis(value);
                } catch (NumberFormatException e) {
                    LOG.error("illegal property 'druid.minEvictableIdleTimeMillis'", e);
                }
            }
        }
        {
            String property = properties.getProperty("druid.maxEvictableIdleTimeMillis");
            if (property != null && property.length() > 0) {
                try {
                    long value = Long.parseLong(property);
                    this.setMaxEvictableIdleTimeMillis(value);
                } catch (NumberFormatException e) {
                    LOG.error("illegal property 'druid.maxEvictableIdleTimeMillis'", e);
                }
            }
        }
        {
            Boolean value = getBoolean(properties, "druid.keepAlive");
            if (value != null) {
                this.setKeepAlive(value);
            }
        }
        {
            String property = properties.getProperty("druid.keepAliveBetweenTimeMillis");
            if (property != null && property.length() > 0) {
                try {
                    long value = Long.parseLong(property);
                    this.setKeepAliveBetweenTimeMillis(value);
                } catch (NumberFormatException e) {
                    LOG.error("illegal property 'druid.keepAliveBetweenTimeMillis'", e);
                }
            }
        }
        {
            Boolean value = getBoolean(properties, "druid.poolPreparedStatements");
            if (value != null) {
                this.setPoolPreparedStatements0(value);
            }
        }
        {
            Boolean value = getBoolean(properties, "druid.initVariants");
            if (value != null) {
                this.setInitVariants(value);
            }
        }
        {
            Boolean value = getBoolean(properties, "druid.initGlobalVariants");
            if (value != null) {
                this.setInitGlobalVariants(value);
            }
        }
        {
            Boolean value = getBoolean(properties, "druid.useUnfairLock");
            if (value != null) {
                this.setUseUnfairLock(value);
            }
        }
        {
            String property = properties.getProperty("druid.driverClassName");
            if (property != null) {
                this.setDriverClassName(property);
            }
        }
        {
            String property = properties.getProperty("druid.initialSize");
            if (property != null && property.length() > 0) {
                try {
                    int value = Integer.parseInt(property);
                    this.setInitialSize(value);
                } catch (NumberFormatException e) {
                    LOG.error("illegal property 'druid.initialSize'", e);
                }
            }
        }
        {
            String property = properties.getProperty("druid.minIdle");
            if (property != null && property.length() > 0) {
                try {
                    int value = Integer.parseInt(property);
                    this.setMinIdle(value);
                } catch (NumberFormatException e) {
                    LOG.error("illegal property 'druid.minIdle'", e);
                }
            }
        }
        {
            String property = properties.getProperty("druid.maxActive");
            if (property != null && property.length() > 0) {
                try {
                    int value = Integer.parseInt(property);
                    this.setMaxActive(value);
                } catch (NumberFormatException e) {
                    LOG.error("illegal property 'druid.maxActive'", e);
                }
            }
        }
        {
            Boolean value = getBoolean(properties, "druid.killWhenSocketReadTimeout");
            if (value != null) {
                setKillWhenSocketReadTimeout(value);
            }
        }
        {
            String property = properties.getProperty("druid.connectProperties");
            if (property != null) {
                this.setConnectionProperties(property);
            }
        }
        {
            String property = properties.getProperty("druid.maxPoolPreparedStatementPerConnectionSize");
            if (property != null && property.length() > 0) {
                try {
                    int value = Integer.parseInt(property);
                    //set 配置的参数
                    this.setMaxPoolPreparedStatementPerConnectionSize(value);
                } catch (NumberFormatException e) {
                    LOG.error("illegal property 'druid.maxPoolPreparedStatementPerConnectionSize'", e);
                }
            }
        }
        {
            String property = properties.getProperty("druid.initConnectionSqls");
            if (property != null && property.length() > 0) {
                try {
                    StringTokenizer tokenizer = new StringTokenizer(property, ";");
                    setConnectionInitSqls(Collections.list(tokenizer));
                } catch (NumberFormatException e) {
                    LOG.error("illegal property 'druid.initConnectionSqls'", e);
                }
            }
        }
        {
            String property = System.getProperty("druid.load.spifilter.skip");
            if (property != null && !"false".equals(property)) {
                loadSpifilterSkip = true;
            }
        }
        {
            String property = System.getProperty("druid.checkExecuteTime");
            if (property != null && !"false".equals(property)) {
                checkExecuteTime = true;
            }
        }
    }

    public boolean isKillWhenSocketReadTimeout() {
        return killWhenSocketReadTimeout;
    }

    public void setKillWhenSocketReadTimeout(boolean killWhenSocketTimeOut) {
        this.killWhenSocketReadTimeout = killWhenSocketTimeOut;
    }

    public boolean isUseGlobalDataSourceStat() {
        return useGlobalDataSourceStat;
    }

    public void setUseGlobalDataSourceStat(boolean useGlobalDataSourceStat) {
        this.useGlobalDataSourceStat = useGlobalDataSourceStat;
    }

    public boolean isKeepAlive() {
        return keepAlive;
    }

    public void setKeepAlive(boolean keepAlive) {
        this.keepAlive = keepAlive;
    }

    public String getInitStackTrace() {
        return initStackTrace;
    }

    public boolean isResetStatEnable() {
        return resetStatEnable;
    }

    public void setResetStatEnable(boolean resetStatEnable) {
        this.resetStatEnable = resetStatEnable;
        if (dataSourceStat != null) {
            dataSourceStat.setResetStatEnable(resetStatEnable);
        }
    }

    public long getDiscardCount() {
        return discardCount;
    }

    public void restart() throws SQLException {
        this.restart(null);
    }

    public void restart(Properties properties) throws SQLException {
        lock.lock();
        try {
            if (activeCount > 0) {
                throw new SQLException("can not restart, activeCount not zero. " + activeCount);
            }
            if (LOG.isInfoEnabled()) {
                LOG.info("{dataSource-" + this.getID() + "} restart");
            }

            this.close();
            this.resetStat();
            this.inited = false;
            this.enable = true;
            this.closed = false;

            if (properties != null) {
                configFromPropety(properties);
            }
        } finally {
            lock.unlock();
        }
    }

    public void resetStat() {
        if (!isResetStatEnable()) {
            return;
        }

        lock.lock();
        try {
            connectCount = 0;
            closeCount = 0;
            discardCount = 0;
            recycleCount = 0;
            createCount = 0L;
            directCreateCount = 0;
            destroyCount = 0L;
            removeAbandonedCount = 0;
            notEmptyWaitCount = 0;
            notEmptySignalCount = 0L;
            notEmptyWaitNanos = 0;

            activePeak = activeCount;
            activePeakTime = 0;
            poolingPeak = 0;
            createTimespan = 0;
            lastError = null;
            lastErrorTimeMillis = 0;
            lastCreateError = null;
            lastCreateErrorTimeMillis = 0;
        } finally {
            lock.unlock();
        }

        connectErrorCountUpdater.set(this, 0);
        errorCountUpdater.set(this, 0);
        commitCountUpdater.set(this, 0);
        rollbackCountUpdater.set(this, 0);
        startTransactionCountUpdater.set(this, 0);
        cachedPreparedStatementHitCountUpdater.set(this, 0);
        closedPreparedStatementCountUpdater.set(this, 0);
        preparedStatementCountUpdater.set(this, 0);
        transactionHistogram.reset();
        cachedPreparedStatementDeleteCountUpdater.set(this, 0);
        recycleErrorCountUpdater.set(this, 0);

        resetCountUpdater.incrementAndGet(this);
    }

    public long getResetCount() {
        return this.resetCount;
    }

    public boolean isEnable() {
        return enable;
    }

    public void setEnable(boolean enable) {
        lock.lock();
        try {
            this.enable = enable;
            if (!enable) {
                notEmpty.signalAll();
                notEmptySignalCount++;
            }
        } finally {
            lock.unlock();
        }
    }

    public void setPoolPreparedStatements(boolean value) {
        setPoolPreparedStatements0(value);
    }

    private void setPoolPreparedStatements0(boolean value) {
        if (this.poolPreparedStatements == value) {
            return;
        }

        this.poolPreparedStatements = value;

        if (!inited) {
            return;
        }

        if (LOG.isInfoEnabled()) {
            LOG.info("set poolPreparedStatements " + this.poolPreparedStatements + " -> " + value);
        }

        if (!value) {
            lock.lock();
            try {

                for (int i = 0; i < poolingCount; ++i) {
                    DruidConnectionHolder connection = connections[i];

                    for (PreparedStatementHolder holder : connection.getStatementPool().getMap().values()) {
                        closePreapredStatement(holder);
                    }

                    connection.getStatementPool().getMap().clear();
                }
            } finally {
                lock.unlock();
            }
        }
    }

    public void setMaxActive(int maxActive) {
        if (this.maxActive == maxActive) {
            return;
        }

        if (maxActive == 0) {
            throw new IllegalArgumentException("maxActive can't not set zero");
        }

        if (!inited) {
            this.maxActive = maxActive;
            return;
        }

        if (maxActive < this.minIdle) {
            throw new IllegalArgumentException("maxActive less than minIdle, " + maxActive + " < " + this.minIdle);
        }

        if (LOG.isInfoEnabled()) {
            LOG.info("maxActive changed : " + this.maxActive + " -> " + maxActive);
        }

        lock.lock();
        try {
            int allCount = this.poolingCount + this.activeCount;

            if (maxActive > allCount) {
                this.connections = Arrays.copyOf(this.connections, maxActive);
                evictConnections = new DruidConnectionHolder[maxActive];
                keepAliveConnections = new DruidConnectionHolder[maxActive];
            } else {
                this.connections = Arrays.copyOf(this.connections, allCount);
                evictConnections = new DruidConnectionHolder[allCount];
                keepAliveConnections = new DruidConnectionHolder[allCount];
            }

            this.maxActive = maxActive;
        } finally {
            lock.unlock();
        }
    }

    @SuppressWarnings("rawtypes")
    public void setConnectProperties(Properties properties) {
        if (properties == null) {
            properties = new Properties();
        }

        boolean equals;
        if (properties.size() == this.connectProperties.size()) {
            equals = true;
            for (Map.Entry entry : properties.entrySet()) {
                Object value = this.connectProperties.get(entry.getKey());
                Object entryValue = entry.getValue();
                if (value == null && entryValue != null) {
                    equals = false;
                    break;
                }

                if (!value.equals(entry.getValue())) {
                    equals = false;
                    break;
                }
            }
        } else {
            equals = false;
        }

        if (!equals) {
            if (inited && LOG.isInfoEnabled()) {
                LOG.info("connectProperties changed : " + this.connectProperties + " -> " + properties);
            }

            configFromPropety(properties);

            for (Filter filter : this.filters) {
                filter.configFromProperties(properties);
            }

            if (exceptionSorter != null) {
                exceptionSorter.configFromProperties(properties);
            }

            if (validConnectionChecker != null) {
                validConnectionChecker.configFromProperties(properties);
            }

            if (statLogger != null) {
                statLogger.configFromProperties(properties);
            }
        }

        this.connectProperties = properties;
    }

    /**
     * init方法是使用 DruidDataSource的入口。
     *
     * @throws SQLException
     */
    public void init() throws SQLException {

        // 1. 双层校验
        // 1.1 判断inited状态，这样确保init方法在同一个DataSource对象中只会被执行一次。（后面有加锁）。
        if (inited) {
            return;
        }

        // bug fixed for dead lock, for issue #2980
        DruidDriver.getInstance();

        //1.2  之后内部开启要给ReentrantLock。这个lock调用lockInterruptibly。 如果获取不到lock,则会将当前的线程休眠
        final ReentrantLock lock = this.lock;
        try {
            lock.lockInterruptibly();
        } catch (InterruptedException e) {
            throw new SQLException("interrupt", e);
        }

        //1.3 再次检测inited状态。如果为true,则返回。这里做了一个DoubleCheck。
        boolean init = false;
        try {
            if (inited) {
                return;
            }

            // 1.4 定义initStackTrace ，为后续需要getInitStackTrace方法使用。
            initStackTrace = Utils.toString(Thread.currentThread().getStackTrace());

            // 1.5 生成DruidDataSource的id。这是一个AtomicInteger，从1开始递增，每个DataSource都会加1。
            this.id = DruidDriver.createDataSourceId();
            if (this.id > 1) {
                long delta = (this.id - 1) * 100000;
                this.connectionIdSeedUpdater.addAndGet(this, delta);
                this.statementIdSeedUpdater.addAndGet(this, delta);
                this.resultSetIdSeedUpdater.addAndGet(this, delta);
                this.transactionIdSeedUpdater.addAndGet(this, delta);
            }

            // 2. 初始化
            // 2.1 初始化jdbcUrl。trim处理。
            if (this.jdbcUrl != null) {
                this.jdbcUrl = this.jdbcUrl.trim();
                initFromWrapDriverUrl();
            }

            // 2.2 初始化的Filter处理，默认会增加要给StatFilter。
            for (Filter filter : filters) {
                filter.init(this);
            }

            // 2.3 根据dbType,进行cacheServerConfiguration的特殊处理。部分数据库需要将这个参数设置为false。
            if (this.dbTypeName == null || this.dbTypeName.length() == 0) {
                this.dbTypeName = JdbcUtils.getDbType(jdbcUrl, null);
            }

            DbType dbType = DbType.of(this.dbTypeName);
            if (dbType == DbType.mysql
                    || dbType == DbType.mariadb
                    || dbType == DbType.oceanbase
                    || dbType == DbType.ads) {
                boolean cacheServerConfigurationSet = false;
                if (this.connectProperties.containsKey("cacheServerConfiguration")) {
                    cacheServerConfigurationSet = true;
                } else if (this.jdbcUrl.indexOf("cacheServerConfiguration") != -1) {
                    cacheServerConfigurationSet = true;
                }
                if (cacheServerConfigurationSet) {
                    this.connectProperties.put("cacheServerConfiguration", "true");
                }
            }

            // 2.4 对maxActive、minIdle、timeBetweenLogStatsMillis、maxEvictableIdleTimeMillis、keepAlive、keepAliveBetweenTimeMillis等参数进行校验。
            if (maxActive <= 0) {
                throw new IllegalArgumentException("illegal maxActive " + maxActive);
            }

            if (maxActive < minIdle) {
                throw new IllegalArgumentException("illegal maxActive " + maxActive);
            }

            if (getInitialSize() > maxActive) {
                throw new IllegalArgumentException("illegal initialSize " + this.initialSize + ", maxActive " + maxActive);
            }

            if (timeBetweenLogStatsMillis > 0 && useGlobalDataSourceStat) {
                throw new IllegalArgumentException("timeBetweenLogStatsMillis not support useGlobalDataSourceStat=true");
            }

            if (maxEvictableIdleTimeMillis < minEvictableIdleTimeMillis) {
                throw new SQLException("maxEvictableIdleTimeMillis must be grater than minEvictableIdleTimeMillis");
            }

            if (keepAlive && keepAliveBetweenTimeMillis <= timeBetweenEvictionRunsMillis) {
                throw new SQLException("keepAliveBetweenTimeMillis must be grater than timeBetweenEvictionRunsMillis");
            }

            if (this.driverClass != null) {
                this.driverClass = driverClass.trim();
            }

            // 2.5 初始化SPI
            initFromSPIServiceLoader();

            // 2.6 解决驱动相关的配置
            resolveDriver();

            // 2.7 初始化校验
            initCheck();

            // 2.8 初始化异常存储
            initExceptionSorter();
            // 2.9 初始化validConnectionChecker 不同的数据库的对象不同
            initValidConnectionChecker();
            // 2.10 校验连接查询的sql
            validationQueryCheck();

            // 2.11 之后，dataSourceStat是否采用了Global。对dataSourceStat进行set。
            if (isUseGlobalDataSourceStat()) {
                dataSourceStat = JdbcDataSourceStat.getGlobal();
                if (dataSourceStat == null) {
                    dataSourceStat = new JdbcDataSourceStat("Global", "Global", this.dbTypeName);
                    JdbcDataSourceStat.setGlobal(dataSourceStat);
                }
                if (dataSourceStat.getDbType() == null) {
                    dataSourceStat.setDbType(this.dbTypeName);
                }
            } else {
                dataSourceStat = new JdbcDataSourceStat(this.name, this.jdbcUrl, this.dbTypeName, this.connectProperties);
            }
            dataSourceStat.setResetStatEnable(this.resetStatEnable);

            // 2.12 初始化holder的数组：
            connections = new DruidConnectionHolder[maxActive];
            evictConnections = new DruidConnectionHolder[maxActive];
            keepAliveConnections = new DruidConnectionHolder[maxActive];

            SQLException connectError = null;

            // 3. 创建连接
            // 3.1 判断是否进行异步初始化
            if (createScheduler != null && asyncInit) {
                // 3.2 如果异步初始化，调用通过submitCreateTask进行。
                for (int i = 0; i < initialSize; ++i) {
                    submitCreateTask(true);
                }
            } else if (!asyncInit) {
                // init connections
                // 3.3 如果poolingCount < initialSize，则创建物理连接。
                // 3.3.1 如果initialSize不配置为0，在初始化过程中，这个条件不会被触发，这样只有真正需要Connection的时候，才会去创建物理的连接。
                //  3.3.2 如果指定了initialSize，则在初始化的过程中，初始化线程就创建了initialSize的连接的holder并放置到connections中。
                while (poolingCount < initialSize) {
                    try {
//                        在同步初始化的条件下，初始化操作将通过init线程进行。而后续由于连接池使用过程中动态的收缩和扩展，则是由其他单独的线程来完成。
//                        反之，如果需要进行异步初始化，则会调用submitCreateTask方法来异步进行。
                        PhysicalConnectionInfo pyConnectInfo = createPhysicalConnection();
                        DruidConnectionHolder holder = new DruidConnectionHolder(this, pyConnectInfo);
                        connections[poolingCount++] = holder;
                    } catch (SQLException ex) {
                        LOG.error("init datasource error, url: " + this.getUrl(), ex);
                        if (initExceptionThrow) {
                            connectError = ex;
                            break;
                        } else {
                            Thread.sleep(3000);
                        }
                    }
                }

                if (poolingCount > 0) {
                    poolingPeak = poolingCount;
                    poolingPeakTime = System.currentTimeMillis();
                }
            }

            // 4. 创建线程
            // 4.1 创建日志线程  但是这个线程的条件timeBetweenLogStatsMillis大于0，如果这个参数没有配置，日志线程不会创建。
            createAndLogThread();
            // 4.2 创建一个CreateConnectionThread对象，并启动。初始化变量createConnectionThread。
            // 负责创建连接，起到生产者的作用
            createAndStartCreatorThread();
            // 4.3 创建 DestroyTask对象。同时创建DestroyConnectionThread线程，并start,初始化destroyConnectionThread。
            // 创建并启动线程，该线程的主要作用为销毁线程
            createAndStartDestroyThread();

            // 4.4 在initedLatch处等待。
            // initedLatch会在createAndStartCreatorThread与createAndStartDestroyThread都执行完之后，countdown结束。
            //这个地方是用来确保上述两个方法都执行完毕，再进行后续的操作。
            initedLatch.await();

            // 4.5 之后 init 状态为true,并初始化initedTime时间为当前的Date时间。注册registerMbean。
            init = true;

            initedTime = new Date();
            registerMbean();

            if (connectError != null && poolingCount == 0) {
                throw connectError;
            }

            // 4.6 如果keepAlive为true,还需调用submitCreateTask方法，将连接填充到minIdle。确保空闲的连接可用。
            if (keepAlive) {
                // async fill to minIdle
                if (createScheduler != null) {
                    for (int i = 0; i < minIdle; ++i) {
                        submitCreateTask(true);
                    }
                } else {
                    this.emptySignal();
                }
            }

        } catch (SQLException e) {
            LOG.error("{dataSource-" + this.getID() + "} init error", e);
            throw e;
        } catch (InterruptedException e) {
            throw new SQLException(e.getMessage(), e);
        } catch (RuntimeException e) {
            LOG.error("{dataSource-" + this.getID() + "} init error", e);
            throw e;
        } catch (Error e) {
            LOG.error("{dataSource-" + this.getID() + "} init error", e);
            throw e;

        } finally {
            // 5 finally处理
            // 5.1 修改inited为true,并解锁。
            inited = true;
            lock.unlock();

            // 5.2 判断init和日志的INFO状态，打印一条init完成的日志。
            if (init && LOG.isInfoEnabled()) {
                String msg = "{dataSource-" + this.getID();

                if (this.name != null && !this.name.isEmpty()) {
                    msg += ",";
                    msg += this.name;
                }

                msg += "} inited";

                LOG.info(msg);
            }
        }
    }

    private void submitCreateTask(boolean initTask) {
        createTaskCount++;
        CreateConnectionTask task = new CreateConnectionTask(initTask);
        if (createTasks == null) {
            createTasks = new long[8];
        }

        boolean putted = false;
        for (int i = 0; i < createTasks.length; ++i) {
            if (createTasks[i] == 0) {
                createTasks[i] = task.taskId;
                putted = true;
                break;
            }
        }
        if (!putted) {
            long[] array = new long[createTasks.length * 3 / 2];
            System.arraycopy(createTasks, 0, array, 0, createTasks.length);
            array[createTasks.length] = task.taskId;
            createTasks = array;
        }

        this.createSchedulerFuture = createScheduler.submit(task);
    }

    private boolean clearCreateTask(long taskId) {
        if (createTasks == null) {
            return false;
        }

        if (taskId == 0) {
            return false;
        }

        for (int i = 0; i < createTasks.length; i++) {
            if (createTasks[i] == taskId) {
                createTasks[i] = 0;
                createTaskCount--;

                if (createTaskCount < 0) {
                    createTaskCount = 0;
                }

                if (createTaskCount == 0 && createTasks.length > 8) {
                    createTasks = new long[8];
                }
                return true;
            }
        }

        if (LOG.isWarnEnabled()) {
            LOG.warn("clear create task failed : " + taskId);
        }

        return false;
    }

    /**
     * 日志打印线程
     */
    private void createAndLogThread() {
        if (this.timeBetweenLogStatsMillis <= 0) {
            return;
        }

        //启动LogStatsThread线程
        String threadName = "Druid-ConnectionPool-Log-" + System.identityHashCode(this);
        logStatsThread = new LogStatsThread(threadName);
        logStatsThread.start();

        this.resetStatEnable = false;
    }

    /**
     * 创建并启动线程，该线程的主要作用为销毁线程
     */
    protected void createAndStartDestroyThread() {
        destroyTask = new DestroyTask();

        //如果连接非常多，单个销毁线程的效率会比较低，如果回收过程出现阻塞等情况，那么此时可以自定义一个destroyScheduler线程持，通过这个线程池配置定始调用回收。
        //这个地方如果需要使用需要自行配置destroyScheduler并配置参数，这与启动过程的createScheduler类似
        if (destroyScheduler != null) {
            long period = timeBetweenEvictionRunsMillis;
            if (period <= 0) {
                period = 1000;
            }
            destroySchedulerFuture = destroyScheduler.scheduleAtFixedRate(destroyTask, period, period,
                    TimeUnit.MILLISECONDS);
            initedLatch.countDown();
            return;
        }

        String threadName = "Druid-ConnectionPool-Destroy-" + System.identityHashCode(this);
        //启动销毁线程
        destroyConnectionThread = new DestroyConnectionThread(threadName);
        destroyConnectionThread.start();
    }

    protected void createAndStartCreatorThread() {
        if (createScheduler == null) {
            String threadName = "Druid-ConnectionPool-Create-" + System.identityHashCode(this);
            //启动线程
            createConnectionThread = new CreateConnectionThread(threadName);
            createConnectionThread.start();
            return;
        }

        initedLatch.countDown();
    }

    /**
     * load filters from SPI ServiceLoader
     *
     * @see ServiceLoader
     */
    private void initFromSPIServiceLoader() {
        if (loadSpifilterSkip) {
            return;
        }

        if (autoFilters == null) {
            List<Filter> filters = new ArrayList<Filter>();
            ServiceLoader<Filter> autoFilterLoader = ServiceLoader.load(Filter.class);

            for (Filter filter : autoFilterLoader) {
                AutoLoad autoLoad = filter.getClass().getAnnotation(AutoLoad.class);
                if (autoLoad != null && autoLoad.value()) {
                    filters.add(filter);
                }
            }
            autoFilters = filters;
        }

        for (Filter filter : autoFilters) {
            if (LOG.isInfoEnabled()) {
                LOG.info("load filter from spi :" + filter.getClass().getName());
            }
            addFilter(filter);
        }
    }

    private void initFromWrapDriverUrl() throws SQLException {
        if (!jdbcUrl.startsWith(DruidDriver.DEFAULT_PREFIX)) {
            return;
        }

        DataSourceProxyConfig config = DruidDriver.parseConfig(jdbcUrl, null);
        this.driverClass = config.getRawDriverClassName();

        LOG.error("error url : '" + jdbcUrl + "', it should be : '" + config.getRawUrl() + "'");

        this.jdbcUrl = config.getRawUrl();
        if (this.name == null) {
            this.name = config.getName();
        }

        for (Filter filter : config.getFilters()) {
            addFilter(filter);
        }
    }

    /**
     * 会去重复
     *
     * @param filter
     */
    private void addFilter(Filter filter) {
        boolean exists = false;
        for (Filter initedFilter : this.filters) {
            if (initedFilter.getClass() == filter.getClass()) {
                exists = true;
                break;
            }
        }

        if (!exists) {
            filter.init(this);
            this.filters.add(filter);
        }

    }

    private void validationQueryCheck() {
        if (!(testOnBorrow || testOnReturn || testWhileIdle)) {
            return;
        }

        if (this.validConnectionChecker != null) {
            return;
        }

        if (this.validationQuery != null && this.validationQuery.length() > 0) {
            return;
        }

        String errorMessage = "";

        if (testOnBorrow) {
            errorMessage += "testOnBorrow is true, ";
        }

        if (testOnReturn) {
            errorMessage += "testOnReturn is true, ";
        }

        if (testWhileIdle) {
            errorMessage += "testWhileIdle is true, ";
        }

        LOG.error(errorMessage + "validationQuery not set");
    }

    protected void resolveDriver() throws SQLException {
        if (this.driver == null) {
            if (this.driverClass == null || this.driverClass.isEmpty()) {
                this.driverClass = JdbcUtils.getDriverClassName(this.jdbcUrl);
            }

            if (MockDriver.class.getName().equals(driverClass)) {
                driver = MockDriver.instance;
            } else if ("com.alibaba.druid.support.clickhouse.BalancedClickhouseDriver".equals(driverClass)) {
                Properties info = new Properties();
                info.put("user", username);
                info.put("password", password);
                info.putAll(connectProperties);
                driver = new BalancedClickhouseDriver(jdbcUrl, info);
            } else {
                if (jdbcUrl == null && (driverClass == null || driverClass.length() == 0)) {
                    throw new SQLException("url not set");
                }
                driver = JdbcUtils.createDriver(driverClassLoader, driverClass);
            }
        } else {
            if (this.driverClass == null) {
                this.driverClass = driver.getClass().getName();
            }
        }
    }

    protected void initCheck() throws SQLException {
        DbType dbType = DbType.of(this.dbTypeName);

        if (dbType == DbType.oracle) {
            isOracle = true;

            if (driver.getMajorVersion() < 10) {
                throw new SQLException("not support oracle driver " + driver.getMajorVersion() + "."
                        + driver.getMinorVersion());
            }

            if (driver.getMajorVersion() == 10 && isUseOracleImplicitCache()) {
                this.getConnectProperties().setProperty("oracle.jdbc.FreeMemoryOnEnterImplicitCache", "true");
            }

            oracleValidationQueryCheck();
        } else if (dbType == DbType.db2) {
            db2ValidationQueryCheck();
        } else if (dbType == DbType.mysql
                || JdbcUtils.MYSQL_DRIVER_6.equals(this.driverClass)) {
            isMySql = true;
        }

        if (removeAbandoned) {
            LOG.warn("removeAbandoned is true, not use in production.");
        }
    }

    private void oracleValidationQueryCheck() {
        if (validationQuery == null) {
            return;
        }
        if (validationQuery.length() == 0) {
            return;
        }

        SQLStatementParser sqlStmtParser = SQLParserUtils.createSQLStatementParser(validationQuery, this.dbTypeName);
        List<SQLStatement> stmtList = sqlStmtParser.parseStatementList();

        if (stmtList.size() != 1) {
            return;
        }

        SQLStatement stmt = stmtList.get(0);
        if (!(stmt instanceof SQLSelectStatement)) {
            return;
        }

        SQLSelectQuery query = ((SQLSelectStatement) stmt).getSelect().getQuery();
        if (query instanceof SQLSelectQueryBlock) {
            if (((SQLSelectQueryBlock) query).getFrom() == null) {
                LOG.error("invalid oracle validationQuery. " + validationQuery + ", may should be : " + validationQuery
                        + " FROM DUAL");
            }
        }
    }

    private void db2ValidationQueryCheck() {
        if (validationQuery == null) {
            return;
        }
        if (validationQuery.length() == 0) {
            return;
        }

        SQLStatementParser sqlStmtParser = SQLParserUtils.createSQLStatementParser(validationQuery, this.dbTypeName);
        List<SQLStatement> stmtList = sqlStmtParser.parseStatementList();

        if (stmtList.size() != 1) {
            return;
        }

        SQLStatement stmt = stmtList.get(0);
        if (!(stmt instanceof SQLSelectStatement)) {
            return;
        }

        SQLSelectQuery query = ((SQLSelectStatement) stmt).getSelect().getQuery();
        if (query instanceof SQLSelectQueryBlock) {
            if (((SQLSelectQueryBlock) query).getFrom() == null) {
                LOG.error("invalid db2 validationQuery. " + validationQuery + ", may should be : " + validationQuery
                        + " FROM SYSDUMMY");
            }
        }
    }

    private void initValidConnectionChecker() {
        if (this.validConnectionChecker != null) {
            return;
        }

        String realDriverClassName = driver.getClass().getName();
        if (JdbcUtils.isMySqlDriver(realDriverClassName)) {
            this.validConnectionChecker = new MySqlValidConnectionChecker();

        } else if (realDriverClassName.equals(JdbcConstants.ORACLE_DRIVER)
                || realDriverClassName.equals(JdbcConstants.ORACLE_DRIVER2)) {
            this.validConnectionChecker = new OracleValidConnectionChecker();

        } else if (realDriverClassName.equals(JdbcConstants.SQL_SERVER_DRIVER)
                || realDriverClassName.equals(JdbcConstants.SQL_SERVER_DRIVER_SQLJDBC4)
                || realDriverClassName.equals(JdbcConstants.SQL_SERVER_DRIVER_JTDS)) {
            this.validConnectionChecker = new MSSQLValidConnectionChecker();

        } else if (realDriverClassName.equals(JdbcConstants.POSTGRESQL_DRIVER)
                || realDriverClassName.equals(JdbcConstants.ENTERPRISEDB_DRIVER)
                || realDriverClassName.equals(JdbcConstants.POLARDB_DRIVER)) {
            this.validConnectionChecker = new PGValidConnectionChecker();
        }
    }

    private void initExceptionSorter() {
        if (exceptionSorter instanceof NullExceptionSorter) {
            if (driver instanceof MockDriver) {
                return;
            }
        } else if (this.exceptionSorter != null) {
            return;
        }


        for (Class<?> driverClass = driver.getClass(); ; ) {
            String realDriverClassName = driverClass.getName();
            if (realDriverClassName.equals(JdbcConstants.MYSQL_DRIVER) //
                    || realDriverClassName.equals(JdbcConstants.MYSQL_DRIVER_6)) {
                this.exceptionSorter = new MySqlExceptionSorter();
                this.isMySql = true;
            } else if (realDriverClassName.equals(JdbcConstants.ORACLE_DRIVER)
                    || realDriverClassName.equals(JdbcConstants.ORACLE_DRIVER2)) {
                this.exceptionSorter = new OracleExceptionSorter();
            } else if (realDriverClassName.equals(JdbcConstants.OCEANBASE_DRIVER)) { // 写一个真实的 TestCase
                if (JdbcUtils.OCEANBASE_ORACLE.name().equalsIgnoreCase(dbTypeName)) {
                    this.exceptionSorter = new OceanBaseOracleExceptionSorter();
                } else {
                    this.exceptionSorter = new MySqlExceptionSorter();
                }
            } else if (realDriverClassName.equals("com.informix.jdbc.IfxDriver")) {
                this.exceptionSorter = new InformixExceptionSorter();

            } else if (realDriverClassName.equals("com.sybase.jdbc2.jdbc.SybDriver")) {
                this.exceptionSorter = new SybaseExceptionSorter();

            } else if (realDriverClassName.equals(JdbcConstants.POSTGRESQL_DRIVER)
                    || realDriverClassName.equals(JdbcConstants.ENTERPRISEDB_DRIVER)
                    || realDriverClassName.equals(JdbcConstants.POLARDB_DRIVER)) {
                this.exceptionSorter = new PGExceptionSorter();

            } else if (realDriverClassName.equals("com.alibaba.druid.mock.MockDriver")) {
                this.exceptionSorter = new MockExceptionSorter();
            } else if (realDriverClassName.contains("DB2")) {
                this.exceptionSorter = new DB2ExceptionSorter();

            } else {
                Class<?> superClass = driverClass.getSuperclass();
                if (superClass != null && superClass != Object.class) {
                    driverClass = superClass;
                    continue;
                }
            }

            break;
        }
    }

    @Override
    public DruidPooledConnection getConnection() throws SQLException {
        return getConnection(maxWait);
    }

    public DruidPooledConnection getConnection(long maxWaitMillis) throws SQLException {
        //执行初始化
        init();

        //如果filter存在 则执行filter,通过filter的代理类来得到连接。
        // 这里有两个分支，判断filters是否存在过滤器，如果存在则先执行过滤器中的内容，这采用责任链模式实现。
        if (filters.size() > 0) {
            //责任链执行过程
            FilterChainImpl filterChain = new FilterChainImpl(this);
            return filterChain.dataSource_connect(this, maxWaitMillis);
        } else {
            //如果filter不存在，则直接获取连接。
            return getConnectionDirect(maxWaitMillis);
        }
    }

    @Override
    public PooledConnection getPooledConnection() throws SQLException {
        return getConnection(maxWait);
    }

    @Override
    public PooledConnection getPooledConnection(String user, String password) throws SQLException {
        throw new UnsupportedOperationException("Not supported by DruidDataSource");
    }

    public DruidPooledConnection getConnectionDirect(long maxWaitMillis) throws SQLException {
        int notFullTimeoutRetryCnt = 0;
        //自旋
        for (; ; ) {
            // handle notFullTimeoutRetry
            //调用getConnectionInternal 获取连接
            DruidPooledConnection poolableConnection;
            try {
                // 最终得到connection的方法：
                poolableConnection = getConnectionInternal(maxWaitMillis);
            } catch (GetConnectionTimeoutException ex) {
                //超时异常处理，判断是否达到最大重试次数 且连接池是否已满
                if (notFullTimeoutRetryCnt <= this.notFullTimeoutRetryCount && !isFull()) {
                    notFullTimeoutRetryCnt++;
                    //日志打印
                    if (LOG.isWarnEnabled()) {
                        LOG.warn("get connection timeout retry : " + notFullTimeoutRetryCnt);
                    }
                    continue;
                }
                throw ex;
            }

            if (testOnBorrow) {
                //获取连接时检测
                boolean validate = testConnectionInternal(poolableConnection.holder, poolableConnection.conn);
                if (!validate) {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("skip not validate connection.");
                    }
                    //检测连接是否关闭
                    discardConnection(poolableConnection.holder);
                    continue;
                }
            } else {
                if (poolableConnection.conn.isClosed()) {
                    discardConnection(poolableConnection.holder); // 传入null，避免重复关闭
                    continue;
                }

                if (testWhileIdle) {
                    //空闲检测
                    final DruidConnectionHolder holder = poolableConnection.holder;
                    long currentTimeMillis = System.currentTimeMillis();
                    long lastActiveTimeMillis = holder.lastActiveTimeMillis;
                    long lastExecTimeMillis = holder.lastExecTimeMillis;
                    long lastKeepTimeMillis = holder.lastKeepTimeMillis;

                    if (checkExecuteTime
                            && lastExecTimeMillis != lastActiveTimeMillis) {
                        lastActiveTimeMillis = lastExecTimeMillis;
                    }

                    if (lastKeepTimeMillis > lastActiveTimeMillis) {
                        lastActiveTimeMillis = lastKeepTimeMillis;
                    }

                    long idleMillis = currentTimeMillis - lastActiveTimeMillis;

                    long timeBetweenEvictionRunsMillis = this.timeBetweenEvictionRunsMillis;

                    if (timeBetweenEvictionRunsMillis <= 0) {
                        timeBetweenEvictionRunsMillis = DEFAULT_TIME_BETWEEN_EVICTION_RUNS_MILLIS;
                    }

                    if (idleMillis >= timeBetweenEvictionRunsMillis
                            || idleMillis < 0 // unexcepted branch
                    ) {
                        boolean validate = testConnectionInternal(poolableConnection.holder, poolableConnection.conn);
                        if (!validate) {
                            if (LOG.isDebugEnabled()) {
                                LOG.debug("skip not validate connection.");
                            }

                            discardConnection(poolableConnection.holder);
                            continue;
                        }
                    }
                }
            }

            if (removeAbandoned) {
                StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
                //设置 stackTrace
                poolableConnection.connectStackTrace = stackTrace;
                //设置setConnectedTimeNano
                poolableConnection.setConnectedTimeNano();
                //打开traceEnable
                poolableConnection.traceEnable = true;

                activeConnectionLock.lock();
                try {
                    activeConnections.put(poolableConnection, PRESENT);
                } finally {
                    activeConnectionLock.unlock();
                }
            }

            if (!this.defaultAutoCommit) {
                poolableConnection.setAutoCommit(false);
            }

            return poolableConnection;
        }
    }

    /**
     * 抛弃连接，不进行回收，而是抛弃
     *
     * @param realConnection
     * @deprecated
     */
    public void discardConnection(Connection realConnection) {
        JdbcUtils.close(realConnection);

        lock.lock();
        try {
            activeCount--;
            discardCount++;

            if (activeCount <= minIdle) {
                emptySignal();
            }
        } finally {
            lock.unlock();
        }
    }

    public void discardConnection(DruidConnectionHolder holder) {
        if (holder == null) {
            return;
        }

        Connection conn = holder.getConnection();
        if (conn != null) {
            JdbcUtils.close(conn);
        }

        lock.lock();
        try {
            if (holder.discard) {
                return;
            }

            if (holder.active) {
                activeCount--;
                holder.active = false;
            }
            discardCount++;

            holder.discard = true;

            if (activeCount <= minIdle) {
                emptySignal();
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * 获取内部连接
     * @param maxWait
     * @return
     * @throws SQLException
     */
    private DruidPooledConnection getConnectionInternal(long maxWait) throws SQLException {
        // 首先判断连接池状态 closed 和enable状态是否正确，如果不正确则抛出异常退出。
        if (closed) {
            connectErrorCountUpdater.incrementAndGet(this);
            throw new DataSourceClosedException("dataSource already closed at " + new Date(closeTimeMillis));
        }

        if (!enable) {
            connectErrorCountUpdater.incrementAndGet(this);

            if (disableException != null) {
                throw disableException;
            }

            throw new DataSourceDisableException();
        }

        final long nanos = TimeUnit.MILLISECONDS.toNanos(maxWait);
        final int maxWaitThreadCount = this.maxWaitThreadCount;

        DruidConnectionHolder holder;

        for (boolean createDirect = false; ; ) {
            if (createDirect) {
                //直接创建连接的逻辑
                createStartNanosUpdater.set(this, System.nanoTime());
                if (creatingCountUpdater.compareAndSet(this, 0, 1)) {
                    PhysicalConnectionInfo pyConnInfo = DruidDataSource.this.createPhysicalConnection();
                    holder = new DruidConnectionHolder(this, pyConnInfo);
                    holder.lastActiveTimeMillis = System.currentTimeMillis();

                    creatingCountUpdater.decrementAndGet(this);
                    directCreateCountUpdater.incrementAndGet(this);

                    if (LOG.isDebugEnabled()) {
                        LOG.debug("conn-direct_create ");
                    }

                    boolean discard = false;
                    lock.lock();
                    try {
                        if (activeCount < maxActive) {
                            activeCount++;
                            holder.active = true;
                            if (activeCount > activePeak) {
                                activePeak = activeCount;
                                activePeakTime = System.currentTimeMillis();
                            }
                            break;
                        } else {
                            discard = true;
                        }
                    } finally {
                        lock.unlock();
                    }

                    if (discard) {
                        JdbcUtils.close(pyConnInfo.getPhysicalConnection());
                    }
                }
            }

            try {
                lock.lockInterruptibly();
            } catch (InterruptedException e) {
                connectErrorCountUpdater.incrementAndGet(this);
                throw new SQLException("interrupt", e);
            }

            try {
                // 判断最大等待线程数如果大于0且notEmpty上的等待线程超过了这个值 那么抛出异常
                if (maxWaitThreadCount > 0
                        && notEmptyWaitThreadCount >= maxWaitThreadCount) {
                    connectErrorCountUpdater.incrementAndGet(this);
                    throw new SQLException("maxWaitThreadCount " + maxWaitThreadCount + ", current wait Thread count "
                            + lock.getQueueLength());
                }
                //其他相关参数检测 抛出异常

                if (onFatalError
                        && onFatalErrorMaxActive > 0
                        && activeCount >= onFatalErrorMaxActive) {
                    connectErrorCountUpdater.incrementAndGet(this);

                    StringBuilder errorMsg = new StringBuilder();
                    errorMsg.append("onFatalError, activeCount ")
                            .append(activeCount)
                            .append(", onFatalErrorMaxActive ")
                            .append(onFatalErrorMaxActive);

                    if (lastFatalErrorTimeMillis > 0) {
                        errorMsg.append(", time '")
                                .append(StringUtils.formatDateTime19(
                                        lastFatalErrorTimeMillis, TimeZone.getDefault()))
                                .append("'");
                    }

                    if (lastFatalErrorSql != null) {
                        errorMsg.append(", sql \n")
                                .append(lastFatalErrorSql);
                    }

                    throw new SQLException(
                            errorMsg.toString(), lastFatalError);
                }

                connectCount++;

                //判断如果createScheduler存在，且executor.getQueue().size()大于0 那么将启用createDirect逻辑，退出本持循环
                if (createScheduler != null
                        && poolingCount == 0
                        && activeCount < maxActive
                        && creatingCountUpdater.get(this) == 0
                        && createScheduler instanceof ScheduledThreadPoolExecutor) {
                    ScheduledThreadPoolExecutor executor = (ScheduledThreadPoolExecutor) createScheduler;
                    if (executor.getQueue().size() > 0) {
                        createDirect = true;
                        continue;
                    }
                }

                //如果maxWait大于0，调用 pollLast(nanos)，反之则调用takeLast()
                //获取连接的核心逻辑
                if (maxWait > 0) {
                    holder = pollLast(nanos);
                } else {
                    holder = takeLast();
                }

                if (holder != null) {
                    if (holder.discard) {
                        continue;
                    }

                    activeCount++;
                    holder.active = true;
                    if (activeCount > activePeak) {
                        activePeak = activeCount;
                        activePeakTime = System.currentTimeMillis();
                    }
                }
            } catch (InterruptedException e) {
                connectErrorCountUpdater.incrementAndGet(this);
                throw new SQLException(e.getMessage(), e);
            } catch (SQLException e) {
                connectErrorCountUpdater.incrementAndGet(this);
                throw e;
            } finally {
                lock.unlock();
            }

            break;
        }

        if (holder == null) {
            long waitNanos = waitNanosLocal.get();

            final long activeCount;
            final long maxActive;
            final long creatingCount;
            final long createStartNanos;
            final long createErrorCount;
            final Throwable createError;
            try {
                lock.lock();
                activeCount = this.activeCount;
                maxActive = this.maxActive;
                creatingCount = this.creatingCount;
                createStartNanos = this.createStartNanos;
                createErrorCount = this.createErrorCount;
                createError = this.createError;
            } finally {
                lock.unlock();
            }

            StringBuilder buf = new StringBuilder(128);
            buf.append("wait millis ")//
                    .append(waitNanos / (1000 * 1000))//
                    .append(", active ").append(activeCount)//
                    .append(", maxActive ").append(maxActive)//
                    .append(", creating ").append(creatingCount)//
            ;
            if (creatingCount > 0 && createStartNanos > 0) {
                long createElapseMillis = (System.nanoTime() - createStartNanos) / (1000 * 1000);
                if (createElapseMillis > 0) {
                    buf.append(", createElapseMillis ").append(createElapseMillis);
                }
            }

            if (createErrorCount > 0) {
                buf.append(", createErrorCount ").append(createErrorCount);
            }

            List<JdbcSqlStatValue> sqlList = this.getDataSourceStat().getRuningSqlList();
            for (int i = 0; i < sqlList.size(); ++i) {
                if (i != 0) {
                    buf.append('\n');
                } else {
                    buf.append(", ");
                }
                JdbcSqlStatValue sql = sqlList.get(i);
                buf.append("runningSqlCount ").append(sql.getRunningCount());
                buf.append(" : ");
                buf.append(sql.getSql());
            }

            String errorMessage = buf.toString();

            if (createError != null) {
                throw new GetConnectionTimeoutException(errorMessage, createError);
            } else {
                throw new GetConnectionTimeoutException(errorMessage);
            }
        }

        holder.incrementUseCount();

        // 根据DruidConnectionHolder产生连接池
        DruidPooledConnection poolalbeConnection = new DruidPooledConnection(holder);
        return poolalbeConnection;
    }

    public void handleConnectionException(DruidPooledConnection pooledConnection, Throwable t, String sql) throws SQLException {
        final DruidConnectionHolder holder = pooledConnection.getConnectionHolder();
        if (holder == null) {
            return;
        }

        errorCountUpdater.incrementAndGet(this);
        lastError = t;
        lastErrorTimeMillis = System.currentTimeMillis();

        if (t instanceof SQLException) {
            SQLException sqlEx = (SQLException) t;

            // broadcastConnectionError
            ConnectionEvent event = new ConnectionEvent(pooledConnection, sqlEx);
            for (ConnectionEventListener eventListener : holder.getConnectionEventListeners()) {
                eventListener.connectionErrorOccurred(event);
            }

            // exceptionSorter.isExceptionFatal
            if (exceptionSorter != null && exceptionSorter.isExceptionFatal(sqlEx)) {
                handleFatalError(pooledConnection, sqlEx, sql);
            }

            throw sqlEx;
        } else {
            throw new SQLException("Error", t);
        }
    }

    protected final void handleFatalError(DruidPooledConnection conn, SQLException error, String sql) throws SQLException {
        final DruidConnectionHolder holder = conn.holder;

        if (conn.isTraceEnable()) {
            activeConnectionLock.lock();
            try {
                if (conn.isTraceEnable()) {
                    activeConnections.remove(conn);
                    conn.setTraceEnable(false);
                }
            } finally {
                activeConnectionLock.unlock();
            }
        }

        long lastErrorTimeMillis = this.lastErrorTimeMillis;
        if (lastErrorTimeMillis == 0) {
            lastErrorTimeMillis = System.currentTimeMillis();
        }

        if (sql != null && sql.length() > 1024) {
            sql = sql.substring(0, 1024);
        }

        boolean requireDiscard = false;
        final ReentrantLock lock = conn.lock;
        lock.lock();
        try {
            if ((!conn.isClosed()) || !conn.isDisable()) {
                conn.disable(error);
                requireDiscard = true;
            }

            lastFatalErrorTimeMillis = lastErrorTimeMillis;
            fatalErrorCount++;
            if (fatalErrorCount - fatalErrorCountLastShrink > onFatalErrorMaxActive) {
                onFatalError = true;
            }
            lastFatalError = error;
            lastFatalErrorSql = sql;
        } finally {
            lock.unlock();
        }

        if (onFatalError && holder != null && holder.getDataSource() != null) {
            ReentrantLock dataSourceLock = holder.getDataSource().lock;
            dataSourceLock.lock();
            try {
                emptySignal();
            } finally {
                dataSourceLock.unlock();
            }
        }

        if (requireDiscard) {
            if (holder.statementTrace != null) {
                holder.lock.lock();
                try {
                    for (Statement stmt : holder.statementTrace) {
                        JdbcUtils.close(stmt);
                    }
                } finally {
                    holder.lock.unlock();
                }
            }

            this.discardConnection(holder);
        }


        // holder.
        LOG.error("{conn-" + holder.getConnectionId() + "} discard", error);
    }

    /**
     * 回收连接
     */
    protected void recycle(DruidPooledConnection pooledConnection) throws SQLException {
        final DruidConnectionHolder holder = pooledConnection.holder;

        if (holder == null) {
            LOG.warn("connectionHolder is null");
            return;
        }

        if (logDifferentThread //
                && (!isAsyncCloseConnectionEnable()) //
                && pooledConnection.ownerThread != Thread.currentThread()//
        ) {
            LOG.warn("get/close not same thread");
        }

        final Connection physicalConnection = holder.conn;

        if (pooledConnection.traceEnable) {
            Object oldInfo = null;
            activeConnectionLock.lock();
            try {
                if (pooledConnection.traceEnable) {
                    //将连接从activeConnections中移除 考虑到多线程场景，要加锁
                    oldInfo = activeConnections.remove(pooledConnection);
                    pooledConnection.traceEnable = false;
                }
            } finally {
                activeConnectionLock.unlock();
            }
            if (oldInfo == null) {
                if (LOG.isWarnEnabled()) {
                    LOG.warn("remove abandonded failed. activeConnections.size " + activeConnections.size());
                }
            }
        }

        final boolean isAutoCommit = holder.underlyingAutoCommit;
        final boolean isReadOnly = holder.underlyingReadOnly;
        final boolean testOnReturn = this.testOnReturn;

        try {
            // check need to rollback?
            if ((!isAutoCommit) && (!isReadOnly)) {
                pooledConnection.rollback();
            }

            // reset holder, restore default settings, clear warnings
            boolean isSameThread = pooledConnection.ownerThread == Thread.currentThread();
            if (!isSameThread) {
                final ReentrantLock lock = pooledConnection.lock;
                lock.lock();
                try {
                    holder.reset();
                } finally {
                    lock.unlock();
                }
            } else {
                holder.reset();
            }

            //如果状态为discard 则直接退出
            if (holder.discard) {
                return;
            }

            //如果超过连接最大的使用次数，那么也将关闭连接
            if (phyMaxUseCount > 0 && holder.useCount >= phyMaxUseCount) {
                discardConnection(holder);
                return;
            }

            // 如果连接已经关闭，则加锁，减去计数器即可。
            if (physicalConnection.isClosed()) {
                lock.lock();
                try {
                    if (holder.active) {
                        activeCount--;
                        holder.active = false;
                    }
                    closeCount++;
                } finally {
                    lock.unlock();
                }
                return;
            }

            // 如果开启了testOnReturn,则发送测试数据，如果测试失败，则关闭连接。
            if (testOnReturn) {
                boolean validate = testConnectionInternal(holder, physicalConnection);
                if (!validate) {
                    JdbcUtils.close(physicalConnection);

                    destroyCountUpdater.incrementAndGet(this);

                    lock.lock();
                    try {
                        if (holder.active) {
                            activeCount--;
                            holder.active = false;
                        }
                        closeCount++;
                    } finally {
                        lock.unlock();
                    }
                    return;
                }
            }
            if (holder.initSchema != null) {
                holder.conn.setSchema(holder.initSchema);
                holder.initSchema = null;
            }

            if (!enable) {
                discardConnection(holder);
                return;
            }

            boolean result;
            final long currentTimeMillis = System.currentTimeMillis();

            if (phyTimeoutMillis > 0) {
                long phyConnectTimeMillis = currentTimeMillis - holder.connectTimeMillis;
                if (phyConnectTimeMillis > phyTimeoutMillis) {
                    discardConnection(holder);
                    return;
                }
            }

            lock.lock();
            try {
                //修改active状态和activeCount计数器
                if (holder.active) {
                    activeCount--;
                    holder.active = false;
                }
                //增加closeCount计数器
                closeCount++;

                //将连接放置到数组的末尾
                result = putLast(holder, currentTimeMillis);
                recycleCount++;
            } finally {
                lock.unlock();
            }

            if (!result) {
                JdbcUtils.close(holder.conn);
                LOG.info("connection recyle failed.");
            }
        } catch (Throwable e) {
            holder.clearStatementCache();

            if (!holder.discard) {
                discardConnection(holder);
                holder.discard = true;
            }

            LOG.error("recyle error", e);
            recycleErrorCountUpdater.incrementAndGet(this);
        }
    }

    public long getRecycleErrorCount() {
        return recycleErrorCount;
    }

    public void clearStatementCache() throws SQLException {
        lock.lock();
        try {
            for (int i = 0; i < poolingCount; ++i) {
                DruidConnectionHolder conn = connections[i];

                if (conn.statementPool != null) {
                    conn.statementPool.clear();
                }
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * close datasource
     */
    public void close() {
        if (LOG.isInfoEnabled()) {
            LOG.info("{dataSource-" + this.getID() + "} closing ...");
        }

        lock.lock();
        try {
            if (this.closed) {
                return;
            }

            if (!this.inited) {
                return;
            }

            this.closing = true;

            if (logStatsThread != null) {
                logStatsThread.interrupt();
            }

            if (createConnectionThread != null) {
                createConnectionThread.interrupt();
            }

            if (destroyConnectionThread != null) {
                destroyConnectionThread.interrupt();
            }

            if (createSchedulerFuture != null) {
                createSchedulerFuture.cancel(true);
            }

            if (destroySchedulerFuture != null) {
                destroySchedulerFuture.cancel(true);
            }

            for (int i = 0; i < poolingCount; ++i) {
                DruidConnectionHolder connHolder = connections[i];

                for (PreparedStatementHolder stmtHolder : connHolder.getStatementPool().getMap().values()) {
                    connHolder.getStatementPool().closeRemovedStatement(stmtHolder);
                }
                connHolder.getStatementPool().getMap().clear();

                Connection physicalConnection = connHolder.getConnection();
                try {
                    physicalConnection.close();
                } catch (Exception ex) {
                    LOG.warn("close connection error", ex);
                }
                connections[i] = null;
                destroyCountUpdater.incrementAndGet(this);
            }
            poolingCount = 0;
            unregisterMbean();

            enable = false;
            notEmpty.signalAll();
            notEmptySignalCount++;

            this.closed = true;
            this.closeTimeMillis = System.currentTimeMillis();

            disableException = new DataSourceDisableException();

            for (Filter filter : filters) {
                filter.destroy();
            }
        } finally {
            this.closing = false;
            lock.unlock();
        }

        if (LOG.isInfoEnabled()) {
            LOG.info("{dataSource-" + this.getID() + "} closed");
        }
    }

    public void registerMbean() {
        if (!mbeanRegistered) {
            AccessController.doPrivileged(new PrivilegedAction<Object>() {

                @Override
                public Object run() {
                    ObjectName objectName = DruidDataSourceStatManager.addDataSource(DruidDataSource.this,
                            DruidDataSource.this.name);

                    DruidDataSource.this.setObjectName(objectName);
                    DruidDataSource.this.mbeanRegistered = true;

                    return null;
                }
            });
        }
    }

    public void unregisterMbean() {
        if (mbeanRegistered) {
            AccessController.doPrivileged(new PrivilegedAction<Object>() {

                @Override
                public Object run() {
                    DruidDataSourceStatManager.removeDataSource(DruidDataSource.this);
                    DruidDataSource.this.mbeanRegistered = false;
                    return null;
                }
            });
        }
    }

    public boolean isMbeanRegistered() {
        return mbeanRegistered;
    }

    boolean putLast(DruidConnectionHolder e, long lastActiveTimeMillis) {
        if (poolingCount >= maxActive || e.discard || this.closed) {
            return false;
        }

        e.lastActiveTimeMillis = lastActiveTimeMillis;
        connections[poolingCount] = e;
        incrementPoolingCount();

        if (poolingCount > poolingPeak) {
            poolingPeak = poolingCount;
            poolingPeakTime = lastActiveTimeMillis;
        }

        notEmpty.signal();
        notEmptySignalCount++;

        return true;
    }

    DruidConnectionHolder takeLast() throws InterruptedException, SQLException {
        try {
            while (poolingCount == 0) {
                emptySignal(); // send signal to CreateThread create connection

                if (failFast && isFailContinuous()) {
                    throw new DataSourceNotAvailableException(createError);
                }

                notEmptyWaitThreadCount++;
                if (notEmptyWaitThreadCount > notEmptyWaitThreadPeak) {
                    notEmptyWaitThreadPeak = notEmptyWaitThreadCount;
                }
                try {
                    notEmpty.await(); // signal by recycle or creator
                } finally {
                    notEmptyWaitThreadCount--;
                }
                notEmptyWaitCount++;

                if (!enable) {
                    connectErrorCountUpdater.incrementAndGet(this);
                    if (disableException != null) {
                        throw disableException;
                    }

                    throw new DataSourceDisableException();
                }
            }
        } catch (InterruptedException ie) {
            notEmpty.signal(); // propagate to non-interrupted thread
            notEmptySignalCount++;
            throw ie;
        }

        decrementPoolingCount();
        //最后获取数组中的最后一个连接。
        DruidConnectionHolder last = connections[poolingCount];
        connections[poolingCount] = null;

        return last;
    }

    private DruidConnectionHolder pollLast(long nanos) throws InterruptedException, SQLException {
        long estimate = nanos;

        for (; ; ) {
            //如果没有可用的连接
            if (poolingCount == 0) {
                emptySignal(); // send signal to CreateThread create connection

                if (failFast && isFailContinuous()) {
                    throw new DataSourceNotAvailableException(createError);
                }

                if (estimate <= 0) {
                    waitNanosLocal.set(nanos - estimate);
                    return null;
                }

                notEmptyWaitThreadCount++;
                if (notEmptyWaitThreadCount > notEmptyWaitThreadPeak) {
                    notEmptyWaitThreadPeak = notEmptyWaitThreadCount;
                }

                try {
                    long startEstimate = estimate;
                    estimate = notEmpty.awaitNanos(estimate); // signal by
                    // recycle or
                    // creator
                    notEmptyWaitCount++;
                    notEmptyWaitNanos += (startEstimate - estimate);

                    if (!enable) {
                        connectErrorCountUpdater.incrementAndGet(this);

                        if (disableException != null) {
                            throw disableException;
                        }

                        throw new DataSourceDisableException();
                    }
                } catch (InterruptedException ie) {
                    notEmpty.signal(); // propagate to non-interrupted thread
                    notEmptySignalCount++;
                    throw ie;
                } finally {
                    notEmptyWaitThreadCount--;
                }

                if (poolingCount == 0) {
                    if (estimate > 0) {
                        continue;
                    }

                    waitNanosLocal.set(nanos - estimate);
                    return null;
                }
            }

            decrementPoolingCount();
            //之后获取最后一个连接
            DruidConnectionHolder last = connections[poolingCount];
            connections[poolingCount] = null;

            long waitNanos = nanos - estimate;
            last.setLastNotEmptyWaitNanos(waitNanos);

            return last;
        }
    }

    private final void decrementPoolingCount() {
        poolingCount--;
    }

    private final void incrementPoolingCount() {
        poolingCount++;
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        if (this.username == null
                && this.password == null
                && username != null
                && password != null) {
            this.username = username;
            this.password = password;

            return getConnection();
        }

        if (!StringUtils.equals(username, this.username)) {
            throw new UnsupportedOperationException("Not supported by DruidDataSource");
        }

        if (!StringUtils.equals(password, this.password)) {
            throw new UnsupportedOperationException("Not supported by DruidDataSource");
        }

        return getConnection();
    }

    public long getCreateCount() {
        return createCount;
    }

    public long getDestroyCount() {
        return destroyCount;
    }

    public long getConnectCount() {
        lock.lock();
        try {
            return connectCount;
        } finally {
            lock.unlock();
        }
    }

    public long getCloseCount() {
        return closeCount;
    }

    public long getConnectErrorCount() {
        return connectErrorCountUpdater.get(this);
    }

    @Override
    public int getPoolingCount() {
        lock.lock();
        try {
            return poolingCount;
        } finally {
            lock.unlock();
        }
    }

    public int getPoolingPeak() {
        lock.lock();
        try {
            return poolingPeak;
        } finally {
            lock.unlock();
        }
    }

    public Date getPoolingPeakTime() {
        if (poolingPeakTime <= 0) {
            return null;
        }

        return new Date(poolingPeakTime);
    }

    public long getRecycleCount() {
        return recycleCount;
    }

    public int getActiveCount() {
        lock.lock();
        try {
            return activeCount;
        } finally {
            lock.unlock();
        }
    }

    public void logStats() {
        final DruidDataSourceStatLogger statLogger = this.statLogger;
        if (statLogger == null) {
            return;
        }

        DruidDataSourceStatValue statValue = getStatValueAndReset();

        statLogger.log(statValue);
    }

    public DruidDataSourceStatValue getStatValueAndReset() {
        DruidDataSourceStatValue value = new DruidDataSourceStatValue();

        lock.lock();
        try {
            value.setPoolingCount(this.poolingCount);
            value.setPoolingPeak(this.poolingPeak);
            value.setPoolingPeakTime(this.poolingPeakTime);

            value.setActiveCount(this.activeCount);
            value.setActivePeak(this.activePeak);
            value.setActivePeakTime(this.activePeakTime);

            value.setConnectCount(this.connectCount);
            value.setCloseCount(this.closeCount);
            value.setWaitThreadCount(lock.getWaitQueueLength(notEmpty));
            value.setNotEmptyWaitCount(this.notEmptyWaitCount);
            value.setNotEmptyWaitNanos(this.notEmptyWaitNanos);
            value.setKeepAliveCheckCount(this.keepAliveCheckCount);

            // reset
            this.poolingPeak = 0;
            this.poolingPeakTime = 0;
            this.activePeak = 0;
            this.activePeakTime = 0;
            this.connectCount = 0;
            this.closeCount = 0;
            this.keepAliveCheckCount = 0;

            this.notEmptyWaitCount = 0;
            this.notEmptyWaitNanos = 0;
        } finally {
            lock.unlock();
        }

        value.setName(this.getName());
        value.setDbType(this.dbTypeName);
        value.setDriverClassName(this.getDriverClassName());

        value.setUrl(this.getUrl());
        value.setUserName(this.getUsername());
        value.setFilterClassNames(this.getFilterClassNames());

        value.setInitialSize(this.getInitialSize());
        value.setMinIdle(this.getMinIdle());
        value.setMaxActive(this.getMaxActive());

        value.setQueryTimeout(this.getQueryTimeout());
        value.setTransactionQueryTimeout(this.getTransactionQueryTimeout());
        value.setLoginTimeout(this.getLoginTimeout());
        value.setValidConnectionCheckerClassName(this.getValidConnectionCheckerClassName());
        value.setExceptionSorterClassName(this.getExceptionSorterClassName());

        value.setTestOnBorrow(this.testOnBorrow);
        value.setTestOnReturn(this.testOnReturn);
        value.setTestWhileIdle(this.testWhileIdle);

        value.setDefaultAutoCommit(this.isDefaultAutoCommit());

        if (defaultReadOnly != null) {
            value.setDefaultReadOnly(defaultReadOnly);
        }
        value.setDefaultTransactionIsolation(this.getDefaultTransactionIsolation());

        value.setLogicConnectErrorCount(connectErrorCountUpdater.getAndSet(this, 0));

        value.setPhysicalConnectCount(createCountUpdater.getAndSet(this, 0));
        value.setPhysicalCloseCount(destroyCountUpdater.getAndSet(this, 0));
        value.setPhysicalConnectErrorCount(createErrorCountUpdater.getAndSet(this, 0));

        value.setExecuteCount(this.getAndResetExecuteCount());
        value.setErrorCount(errorCountUpdater.getAndSet(this, 0));
        value.setCommitCount(commitCountUpdater.getAndSet(this, 0));
        value.setRollbackCount(rollbackCountUpdater.getAndSet(this, 0));

        value.setPstmtCacheHitCount(cachedPreparedStatementHitCountUpdater.getAndSet(this, 0));
        value.setPstmtCacheMissCount(cachedPreparedStatementMissCountUpdater.getAndSet(this, 0));

        value.setStartTransactionCount(startTransactionCountUpdater.getAndSet(this, 0));
        value.setTransactionHistogram(this.getTransactionHistogram().toArrayAndReset());

        value.setConnectionHoldTimeHistogram(this.getDataSourceStat().getConnectionHoldHistogram().toArrayAndReset());
        value.setRemoveAbandoned(this.isRemoveAbandoned());
        value.setClobOpenCount(this.getDataSourceStat().getClobOpenCountAndReset());
        value.setBlobOpenCount(this.getDataSourceStat().getBlobOpenCountAndReset());

        value.setSqlSkipCount(this.getDataSourceStat().getSkipSqlCountAndReset());
        value.setSqlList(this.getDataSourceStat().getSqlStatMapAndReset());

        return value;
    }

    public long getRemoveAbandonedCount() {
        return removeAbandonedCount;
    }

    protected boolean put(PhysicalConnectionInfo physicalConnectionInfo) {
        DruidConnectionHolder holder = null;
        try {
            holder = new DruidConnectionHolder(DruidDataSource.this, physicalConnectionInfo);
        } catch (SQLException ex) {
            lock.lock();
            try {
                if (createScheduler != null) {
                    clearCreateTask(physicalConnectionInfo.createTaskId);
                }
            } finally {
                lock.unlock();
            }
            LOG.error("create connection holder error", ex);
            return false;
        }

        return put(holder, physicalConnectionInfo.createTaskId, false);
    }

    private boolean put(DruidConnectionHolder holder, long createTaskId, boolean checkExists) {
        lock.lock();
        try {
            if (this.closing || this.closed) {
                return false;
            }

            if (poolingCount >= maxActive) {
                if (createScheduler != null) {
                    clearCreateTask(createTaskId);
                }
                return false;
            }

            if (checkExists) {
                for (int i = 0; i < poolingCount; i++) {
                    if (connections[i] == holder) {
                        return false;
                    }
                }
            }

            connections[poolingCount] = holder;
            incrementPoolingCount();

            if (poolingCount > poolingPeak) {
                poolingPeak = poolingCount;
                poolingPeakTime = System.currentTimeMillis();
            }

            notEmpty.signal();
            notEmptySignalCount++;

            if (createScheduler != null) {
                clearCreateTask(createTaskId);

                if (poolingCount + createTaskCount < notEmptyWaitThreadCount //
                        && activeCount + poolingCount + createTaskCount < maxActive) {
                    emptySignal();
                }
            }
        } finally {
            lock.unlock();
        }
        return true;
    }

    public class CreateConnectionTask implements Runnable {

        private int errorCount = 0;
        private boolean initTask = false;
        private final long taskId;

        public CreateConnectionTask() {
            taskId = createTaskIdSeedUpdater.getAndIncrement(DruidDataSource.this);
        }

        public CreateConnectionTask(boolean initTask) {
            taskId = createTaskIdSeedUpdater.getAndIncrement(DruidDataSource.this);
            this.initTask = initTask;
        }

        @Override
        public void run() {
            runInternal();
        }

        private void runInternal() {
            for (; ; ) {

                // addLast
                lock.lock();
                try {
                    if (closed || closing) {
                        clearCreateTask(taskId);
                        return;
                    }

                    boolean emptyWait = true;

                    if (createError != null && poolingCount == 0) {
                        emptyWait = false;
                    }

                    if (emptyWait) {
                        // 必须存在线程等待，才创建连接
                        if (poolingCount >= notEmptyWaitThreadCount //
                                && (!(keepAlive && activeCount + poolingCount < minIdle)) // 在keepAlive场景不能放弃创建
                                && (!initTask) // 线程池初始化时的任务不能放弃创建
                                && !isFailContinuous() // failContinuous时不能放弃创建，否则会无法创建线程
                                && !isOnFatalError() // onFatalError时不能放弃创建，否则会无法创建线程
                        ) {
                            clearCreateTask(taskId);
                            return;
                        }

                        // 防止创建超过maxActive数量的连接
                        if (activeCount + poolingCount >= maxActive) {
                            clearCreateTask(taskId);
                            return;
                        }
                    }
                } finally {
                    lock.unlock();
                }

                PhysicalConnectionInfo physicalConnection = null;

                try {
                    physicalConnection = createPhysicalConnection();
                } catch (OutOfMemoryError e) {
                    LOG.error("create connection OutOfMemoryError, out memory. ", e);

                    errorCount++;
                    if (errorCount > connectionErrorRetryAttempts && timeBetweenConnectErrorMillis > 0) {
                        // fail over retry attempts
                        setFailContinuous(true);
                        if (failFast) {
                            lock.lock();
                            try {
                                notEmpty.signalAll();
                            } finally {
                                lock.unlock();
                            }
                        }

                        if (breakAfterAcquireFailure) {
                            lock.lock();
                            try {
                                clearCreateTask(taskId);
                            } finally {
                                lock.unlock();
                            }
                            return;
                        }

                        this.errorCount = 0; // reset errorCount
                        if (closing || closed) {
                            lock.lock();
                            try {
                                clearCreateTask(taskId);
                            } finally {
                                lock.unlock();
                            }
                            return;
                        }

                        createSchedulerFuture = createScheduler.schedule(this, timeBetweenConnectErrorMillis, TimeUnit.MILLISECONDS);
                        return;
                    }
                } catch (SQLException e) {
                    LOG.error("create connection SQLException, url: " + jdbcUrl, e);

                    errorCount++;
                    if (errorCount > connectionErrorRetryAttempts && timeBetweenConnectErrorMillis > 0) {
                        // fail over retry attempts
                        setFailContinuous(true);
                        if (failFast) {
                            lock.lock();
                            try {
                                notEmpty.signalAll();
                            } finally {
                                lock.unlock();
                            }
                        }

                        if (breakAfterAcquireFailure) {
                            lock.lock();
                            try {
                                clearCreateTask(taskId);
                            } finally {
                                lock.unlock();
                            }
                            return;
                        }

                        this.errorCount = 0; // reset errorCount
                        if (closing || closed) {
                            lock.lock();
                            try {
                                clearCreateTask(taskId);
                            } finally {
                                lock.unlock();
                            }
                            return;
                        }

                        createSchedulerFuture = createScheduler.schedule(this, timeBetweenConnectErrorMillis, TimeUnit.MILLISECONDS);
                        return;
                    }
                } catch (RuntimeException e) {
                    LOG.error("create connection RuntimeException", e);
                    // unknow fatal exception
                    setFailContinuous(true);
                    continue;
                } catch (Error e) {
                    lock.lock();
                    try {
                        clearCreateTask(taskId);
                    } finally {
                        lock.unlock();
                    }
                    LOG.error("create connection Error", e);
                    // unknow fatal exception
                    setFailContinuous(true);
                    break;
                } catch (Throwable e) {
                    lock.lock();
                    try {
                        clearCreateTask(taskId);
                    } finally {
                        lock.unlock();
                    }

                    LOG.error("create connection unexecpted error.", e);
                    break;
                }

                if (physicalConnection == null) {
                    continue;
                }

                physicalConnection.createTaskId = taskId;
                boolean result = put(physicalConnection);
                if (!result) {
                    JdbcUtils.close(physicalConnection.getPhysicalConnection());
                    LOG.info("put physical connection to pool failed.");
                }
                break;
            }
        }
    }

    /**
     * 负责创建连接，起到生产者的作用
     */
    public class CreateConnectionThread extends Thread {

        public CreateConnectionThread(String name) {
            super(name);
            this.setDaemon(true);
        }

        public void run() {
            initedLatch.countDown();

            long lastDiscardCount = 0;
            int errorCount = 0;
            //死循环：
            for (; ; ) {
                // addLast
                try {
                    lock.lockInterruptibly();
                } catch (InterruptedException e2) {
                    break;
                }

                long discardCount = DruidDataSource.this.discardCount;
                boolean discardChanged = discardCount - lastDiscardCount > 0;
                lastDiscardCount = discardCount;

                try {

                    boolean emptyWait = true;

                    if (createError != null
                            && poolingCount == 0
                            && !discardChanged) {
                        emptyWait = false;
                    }

                    if (emptyWait
                            && asyncInit && createCount < initialSize) {
                        emptyWait = false;
                    }

                    //根据emptyWait 判断是否能够创建连接
                    if (emptyWait) {
                        // 必须存在线程等待，才创建连接
                        if (poolingCount >= notEmptyWaitThreadCount //
                                && (!(keepAlive && activeCount + poolingCount < minIdle))
                                && !isFailContinuous()
                        ) {
                            empty.await();
                        }

                        // 防止创建超过maxActive数量的连接
                        if (activeCount + poolingCount >= maxActive) {
                            empty.await();
                            continue;
                        }
                    }

                } catch (InterruptedException e) {
                    lastCreateError = e;
                    lastErrorTimeMillis = System.currentTimeMillis();

                    if ((!closing) && (!closed)) {
                        LOG.error("create connection Thread Interrupted, url: " + jdbcUrl, e);
                    }
                    break;
                } finally {
                    lock.unlock();
                }

                PhysicalConnectionInfo connection = null;

                try {
                    //创建真实连接
                    connection = createPhysicalConnection();
                } catch (SQLException e) {
                    LOG.error("create connection SQLException, url: " + jdbcUrl + ", errorCode " + e.getErrorCode()
                            + ", state " + e.getSQLState(), e);

                    errorCount++;
                    if (errorCount > connectionErrorRetryAttempts && timeBetweenConnectErrorMillis > 0) {
                        // fail over retry attempts
                        setFailContinuous(true);
                        if (failFast) {
                            lock.lock();
                            try {
                                //非空信号，通知消费线程来获取
                                notEmpty.signalAll();
                            } finally {
                                // 解锁
                                lock.unlock();
                            }
                        }

                        if (breakAfterAcquireFailure) {
                            break;
                        }

                        try {
                            Thread.sleep(timeBetweenConnectErrorMillis);
                        } catch (InterruptedException interruptEx) {
                            break;
                        }
                    }
                } catch (RuntimeException e) {
                    LOG.error("create connection RuntimeException", e);
                    setFailContinuous(true);
                    continue;
                } catch (Error e) {
                    LOG.error("create connection Error", e);
                    setFailContinuous(true);
                    break;
                }

                if (connection == null) {
                    continue;
                }

                boolean result = put(connection);
                if (!result) {
                    JdbcUtils.close(connection.getPhysicalConnection());
                    LOG.info("put physical connection to pool failed.");
                }

                errorCount = 0; // reset errorCount

                if (closing || closed) {
                    break;
                }
            }
        }
    }

    public class DestroyConnectionThread extends Thread {

        public DestroyConnectionThread(String name) {
            super(name);
            this.setDaemon(true);
        }

        public void run() {
            //死循环，自旋调用
            initedLatch.countDown();

            for (; ; ) {
                // 从前面开始删除
                try {
                    if (closed || closing) {
                        break;
                    }

                    //sleep时间
                    if (timeBetweenEvictionRunsMillis > 0) {
                        Thread.sleep(timeBetweenEvictionRunsMillis);
                    } else {
                        Thread.sleep(1000); //
                    }

                    if (Thread.interrupted()) {
                        break;
                    }

                    //调用 destroyTask.run()
                    destroyTask.run();
                } catch (InterruptedException e) {
                    break;
                }
            }
        }

    }

    /**
     * 回收连接线程
     */
    public class DestroyTask implements Runnable {
        public DestroyTask() {

        }

        @Override
        public void run() {
            shrink(true, keepAlive);

            if (isRemoveAbandoned()) {
                removeAbandoned();
            }
        }

    }

    /**
     * 打印日志线程
     */
    public class LogStatsThread extends Thread {

        public LogStatsThread(String name) {
            super(name);
            this.setDaemon(true);
        }

        public void run() {
            try {
                for (; ; ) {
                    try {
                        logStats();
                    } catch (Exception e) {
                        LOG.error("logStats error", e);
                    }

                    Thread.sleep(timeBetweenLogStatsMillis);
                }
            } catch (InterruptedException e) {
                // skip
            }
        }
    }

    /**
     * 当开启连接泄露检测机制之后，会定期检测连接是否触发超时时间，如果触发则关闭连接。凡是get之后被使用的连接都放置在activeConnections中。
     * 之后遍历activeConnections，对连接进行判断，如果触发超时时间，则close。
     * @return
     */
    public int removeAbandoned() {
        int removeCount = 0;

        long currrentNanos = System.nanoTime();

        //定义一个abandonedList来存放符合超时时间的连接。
        List<DruidPooledConnection> abandonedList = new ArrayList<DruidPooledConnection>();

        //加锁
        activeConnectionLock.lock();
        try {
            //通过迭代器遍历activeConnections
            Iterator<DruidPooledConnection> iter = activeConnections.keySet().iterator();

            for (; iter.hasNext(); ) {
                //获取到连接
                DruidPooledConnection pooledConnection = iter.next();

                //如果连级为Running状态，说明sql语句正在执行，则跳过当前连接
                if (pooledConnection.isRunning()) {
                    continue;
                }

                //计算超时时间 默认值为5分钟
                long timeMillis = (currrentNanos - pooledConnection.getConnectedTimeNano()) / (1000 * 1000);
                //如果触发超时：
                if (timeMillis >= removeAbandonedTimeoutMillis) {
                    //从iter删除该连接
                    iter.remove();
                    //关闭setTraceEnable
                    pooledConnection.setTraceEnable(false);
                    //添加到abandonedList
                    abandonedList.add(pooledConnection);
                }
            }
        } finally {
            //解锁
            activeConnectionLock.unlock();
        }

        if (abandonedList.size() > 0) {
            for (DruidPooledConnection pooledConnection : abandonedList) {
                // 定义锁
                final ReentrantLock lock = pooledConnection.lock;
                lock.lock();
                try {
                    //如果连接为disiable状态 说明已经不可用 直接跳过
                    if (pooledConnection.isDisable()) {
                        continue;
                    }
                } finally {
                    //解锁
                    lock.unlock();
                }

                //关闭连接
                JdbcUtils.close(pooledConnection);
                //设置abandond状态
                pooledConnection.abandond();
                //增加计数器
                removeAbandonedCount++;
                removeCount++;

                if (isLogAbandoned()) {
                    StringBuilder buf = new StringBuilder();
                    buf.append("abandon connection, owner thread: ");
                    buf.append(pooledConnection.getOwnerThread().getName());
                    buf.append(", connected at : ");
                    buf.append(pooledConnection.getConnectedTimeMillis());
                    buf.append(", open stackTrace\n");

                    StackTraceElement[] trace = pooledConnection.getConnectStackTrace();
                    for (int i = 0; i < trace.length; i++) {
                        buf.append("\tat ");
                        buf.append(trace[i].toString());
                        buf.append("\n");
                    }

                    buf.append("ownerThread current state is " + pooledConnection.getOwnerThread().getState()
                            + ", current stackTrace\n");
                    trace = pooledConnection.getOwnerThread().getStackTrace();
                    for (int i = 0; i < trace.length; i++) {
                        buf.append("\tat ");
                        buf.append(trace[i].toString());
                        buf.append("\n");
                    }

                    LOG.error(buf.toString());
                }
            }
        }

        return removeCount;
    }

    /**
     * Instance key
     */
    protected String instanceKey = null;

    public Reference getReference() throws NamingException {
        final String className = getClass().getName();
        final String factoryName = className + "Factory"; // XXX: not robust
        Reference ref = new Reference(className, factoryName, null);
        ref.add(new StringRefAddr("instanceKey", instanceKey));
        ref.add(new StringRefAddr("url", this.getUrl()));
        ref.add(new StringRefAddr("username", this.getUsername()));
        ref.add(new StringRefAddr("password", this.getPassword()));
        // TODO ADD OTHER PROPERTIES
        return ref;
    }

    @Override
    public List<String> getFilterClassNames() {
        List<String> names = new ArrayList<String>();
        for (Filter filter : filters) {
            names.add(filter.getClass().getName());
        }
        return names;
    }

    public int getRawDriverMajorVersion() {
        int version = -1;
        if (this.driver != null) {
            version = driver.getMajorVersion();
        }
        return version;
    }

    public int getRawDriverMinorVersion() {
        int version = -1;
        if (this.driver != null) {
            version = driver.getMinorVersion();
        }
        return version;
    }

    public String getProperties() {
        Properties properties = new Properties();
        properties.putAll(connectProperties);
        if (properties.containsKey("password")) {
            properties.put("password", "******");
        }
        return properties.toString();
    }

    @Override
    public void shrink() {
        shrink(false, false);
    }

    public void shrink(boolean checkTime) {
        shrink(checkTime, keepAlive);
    }

    public void shrink(boolean checkTime, boolean keepAlive) {
        try {
            //1. 获得锁
            lock.lockInterruptibly();
        } catch (InterruptedException e) {
            return;
        }

        //计算removeCount evictCount keepAliveCount等
        boolean needFill = false;
        int evictCount = 0;
        int keepAliveCount = 0;
        int fatalErrorIncrement = fatalErrorCount - fatalErrorCountLastShrink;
        fatalErrorCountLastShrink = fatalErrorCount;

        try {
            // 2. 要判断初始化状态是否完成，如果采用异步初始化，可能DestoryTask线程已经启动，但是连接池还没有初始化完成。
            if (!inited) {
                return;
            }

            // 3. 对连接池中的连接进行遍历 ，connections中，可连接的连接数记在poolingCount变量。
            //此时要记录一个checkCount，这个变量为 checkCount = poolingCount - minIdle;
            // 也就是checkCount为连接池中连接的数量减去最小空闲连接数设置minIdle。
            final int checkCount = poolingCount - minIdle;
            final long currentTimeMillis = System.currentTimeMillis();
            for (int i = 0; i < poolingCount; ++i) {
                DruidConnectionHolder connection = connections[i];

                if ((onFatalError || fatalErrorIncrement > 0) && (lastFatalErrorTimeMillis > connection.connectTimeMillis)) {
                    keepAliveConnections[keepAliveCount++] = connection;
                    continue;
                }

                // 4. 此后进入checkTime逻辑，checkTime是调用shrink传入的参数，通常DestroyTask的调用这个参数都为true。
                if (checkTime) {
                    // 4.1 判断物理连接是否超时：phyConnectTimeMillis > phyTimeoutMillis。如果超时，则将当前连接标记到evictConnections数组并退出当前循环。
                    if (phyTimeoutMillis > 0) {
                        long phyConnectTimeMillis = currentTimeMillis - connection.connectTimeMillis;
                        if (phyConnectTimeMillis > phyTimeoutMillis) {
                            evictConnections[evictCount++] = connection;
                            continue;
                        }
                    }

                    // 4.2 判断空闲时间是否超时：
                    //如果空闲时间小于最小于配置的minEvictableIdleTimeMillis时间且同时小于配置的keepAliveBetweenTimeMillis(idleMillis < minEvictableIdleTimeMillis && idleMillis < keepAliveBetweenTimeMillis) 则结束循环。
                    //反之，当idleMillis大于minEvictableIdleTimeMillis或者大于maxEvictableIdleTimeMillis都被标记到evictConnections数组。
                    long idleMillis = currentTimeMillis - connection.lastActiveTimeMillis;

                    if (idleMillis < minEvictableIdleTimeMillis
                            && idleMillis < keepAliveBetweenTimeMillis
                    ) {
                        break;
                    }

                    if (idleMillis >= minEvictableIdleTimeMillis) {
                        if (checkTime && i < checkCount) {
                            evictConnections[evictCount++] = connection;
                            continue;
                        } else if (idleMillis > maxEvictableIdleTimeMillis) {
                            evictConnections[evictCount++] = connection;
                            continue;
                        }
                    }

                    // 4.3 判断keepAlive是否超时：如果idleMillis >= keepAliveBetweenTimeMillis，则标记到keepAliveConnections数组。
                    if (keepAlive && idleMillis >= keepAliveBetweenTimeMillis) {
                        keepAliveConnections[keepAliveCount++] = connection;
                    }
                } else {
                    // 5. 如果checkTime为false,则将小于checkCount的全部连接都标记到evictConnections数组。
                    if (i < checkCount) {
                        evictConnections[evictCount++] = connection;
                    } else {
                        break;
                    }
                }
            }

            // 6. 这之后进行removeCount的处理，removeCount = evictCount + keepAliveCount;
            int removeCount = evictCount + keepAliveCount;
            if (removeCount > 0) {
                //将connections从removeCount到poolingCount的连接向前移动poolingCount - removeCount。
                System.arraycopy(connections, removeCount, connections, 0, poolingCount - removeCount);
                //将poolingCount - removeCount后续部分都置为空。
                Arrays.fill(connections, poolingCount - removeCount, poolingCount, null);
                poolingCount -= removeCount;
            }
            keepAliveCheckCount += keepAliveCount;

            if (keepAlive && poolingCount + activeCount < minIdle) {
                needFill = true;
            }
        } finally {
            // 7. 解锁
            lock.unlock();
        }

        //8. 如果evictCount大于0 关闭连接
        if (evictCount > 0) {
            for (int i = 0; i < evictCount; ++i) {
                DruidConnectionHolder item = evictConnections[i];
                Connection connection = item.getConnection();
                //关闭连接
                JdbcUtils.close(connection);
                //更新计数器
                destroyCountUpdater.incrementAndGet(this);
            }
            //将evictConnections清空
            Arrays.fill(evictConnections, null);
        }

        // 9. keepAliveCount连接处理
        if (keepAliveCount > 0) {
            // keep order
            for (int i = keepAliveCount - 1; i >= 0; --i) {
                DruidConnectionHolder holer = keepAliveConnections[i];
                Connection connection = holer.getConnection();
                holer.incrementKeepAliveCheckCount();
                //校验连接是否还可用
                boolean validate = false;
                try {
                    this.validateConnection(connection);
                    validate = true;
                } catch (Throwable error) {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("keepAliveErr", error);
                    }
                    // skip
                }

                boolean discard = !validate;
                if (validate) {
                    holer.lastKeepTimeMillis = System.currentTimeMillis();
                    //如果可用，则直接put到connections中，放置到尾部。
                    boolean putOk = put(holer, 0L, true);
                    if (!putOk) {
                        discard = true;
                    }
                }
                //如果不可用，则关闭连接
                if (discard) {
                    try {
                        connection.close();
                    } catch (Exception e) {
                        // skip
                    }

                    lock.lock();
                    try {
                        //加锁更新计数器
                        discardCount++;

                        //如果回收之后小于最小空闲连接
                        if (activeCount + poolingCount <= minIdle) {
                            //通知可以创建新连接了
                            emptySignal();
                        }
                    } finally {
                        //解锁
                        lock.unlock();
                    }
                }
            }
            this.getDataSourceStat().addKeepAliveCheckCount(keepAliveCount);
            Arrays.fill(keepAliveConnections, null);
        }

        // 10. keepAlive状态，且连接池中的连接加上被使用的连接仍然小于minIdle
        if (needFill) {
            //加锁
            lock.lock();
            try {
                //如果minIdle 减去activeCount + poolingCount + createTaskCount 仍然不满，则通知创建线程创建连接
                int fillCount = minIdle - (activeCount + poolingCount + createTaskCount);
                for (int i = 0; i < fillCount; ++i) {
                    emptySignal();
                }
            } finally {
                //解锁
                lock.unlock();
            }
        } else if (onFatalError || fatalErrorIncrement > 0) {
            lock.lock();
            try {
                emptySignal();
            } finally {
                lock.unlock();
            }
        }
    }

    public int getWaitThreadCount() {
        lock.lock();
        try {
            return lock.getWaitQueueLength(notEmpty);
        } finally {
            lock.unlock();
        }
    }

    public long getNotEmptyWaitCount() {
        return notEmptyWaitCount;
    }

    public int getNotEmptyWaitThreadCount() {
        lock.lock();
        try {
            return notEmptyWaitThreadCount;
        } finally {
            lock.unlock();
        }
    }

    public int getNotEmptyWaitThreadPeak() {
        lock.lock();
        try {
            return notEmptyWaitThreadPeak;
        } finally {
            lock.unlock();
        }
    }

    public long getNotEmptySignalCount() {
        return notEmptySignalCount;
    }

    public long getNotEmptyWaitMillis() {
        return notEmptyWaitNanos / (1000 * 1000);
    }

    public long getNotEmptyWaitNanos() {
        return notEmptyWaitNanos;
    }

    public int getLockQueueLength() {
        return lock.getQueueLength();
    }

    public int getActivePeak() {
        return activePeak;
    }

    public Date getActivePeakTime() {
        if (activePeakTime <= 0) {
            return null;
        }

        return new Date(activePeakTime);
    }

    public String dump() {
        lock.lock();
        try {
            return this.toString();
        } finally {
            lock.unlock();
        }
    }

    public long getErrorCount() {
        return this.errorCount;
    }

    public String toString() {
        StringBuilder buf = new StringBuilder();

        buf.append("{");

        buf.append("\n\tCreateTime:\"");
        buf.append(Utils.toString(getCreatedTime()));
        buf.append("\"");

        buf.append(",\n\tActiveCount:");
        buf.append(getActiveCount());

        buf.append(",\n\tPoolingCount:");
        buf.append(getPoolingCount());

        buf.append(",\n\tCreateCount:");
        buf.append(getCreateCount());

        buf.append(",\n\tDestroyCount:");
        buf.append(getDestroyCount());

        buf.append(",\n\tCloseCount:");
        buf.append(getCloseCount());

        buf.append(",\n\tConnectCount:");
        buf.append(getConnectCount());

        buf.append(",\n\tConnections:[");
        for (int i = 0; i < poolingCount; ++i) {
            DruidConnectionHolder conn = connections[i];
            if (conn != null) {
                if (i != 0) {
                    buf.append(",");
                }
                buf.append("\n\t\t");
                buf.append(conn.toString());
            }
        }
        buf.append("\n\t]");

        buf.append("\n}");

        if (this.isPoolPreparedStatements()) {
            buf.append("\n\n[");
            for (int i = 0; i < poolingCount; ++i) {
                DruidConnectionHolder conn = connections[i];
                if (conn != null) {
                    if (i != 0) {
                        buf.append(",");
                    }
                    buf.append("\n\t{\n\tID:");
                    buf.append(System.identityHashCode(conn.getConnection()));
                    PreparedStatementPool pool = conn.getStatementPool();

                    buf.append(", \n\tpoolStatements:[");

                    int entryIndex = 0;
                    try {
                        for (Map.Entry<PreparedStatementKey, PreparedStatementHolder> entry : pool.getMap().entrySet()) {
                            if (entryIndex != 0) {
                                buf.append(",");
                            }
                            buf.append("\n\t\t{hitCount:");
                            buf.append(entry.getValue().getHitCount());
                            buf.append(",sql:\"");
                            buf.append(entry.getKey().getSql());
                            buf.append("\"");
                            buf.append("\t}");

                            entryIndex++;
                        }
                    } catch (ConcurrentModificationException e) {
                        // skip ..
                    }

                    buf.append("\n\t\t]");

                    buf.append("\n\t}");
                }
            }
            buf.append("\n]");
        }

        return buf.toString();
    }

    public List<Map<String, Object>> getPoolingConnectionInfo() {
        List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
        lock.lock();
        try {
            for (int i = 0; i < poolingCount; ++i) {
                DruidConnectionHolder connHolder = connections[i];
                Connection conn = connHolder.getConnection();

                Map<String, Object> map = new LinkedHashMap<String, Object>();
                map.put("id", System.identityHashCode(conn));
                map.put("connectionId", connHolder.getConnectionId());
                map.put("useCount", connHolder.getUseCount());
                if (connHolder.lastActiveTimeMillis > 0) {
                    map.put("lastActiveTime", new Date(connHolder.lastActiveTimeMillis));
                }
                if (connHolder.lastKeepTimeMillis > 0) {
                    map.put("lastKeepTimeMillis", new Date(connHolder.lastKeepTimeMillis));
                }
                map.put("connectTime", new Date(connHolder.getTimeMillis()));
                map.put("holdability", connHolder.getUnderlyingHoldability());
                map.put("transactionIsolation", connHolder.getUnderlyingTransactionIsolation());
                map.put("autoCommit", connHolder.underlyingAutoCommit);
                map.put("readoOnly", connHolder.isUnderlyingReadOnly());

                if (connHolder.isPoolPreparedStatements()) {
                    List<Map<String, Object>> stmtCache = new ArrayList<Map<String, Object>>();
                    PreparedStatementPool stmtPool = connHolder.getStatementPool();
                    for (PreparedStatementHolder stmtHolder : stmtPool.getMap().values()) {
                        Map<String, Object> stmtInfo = new LinkedHashMap<String, Object>();

                        stmtInfo.put("sql", stmtHolder.key.getSql());
                        stmtInfo.put("defaultRowPrefetch", stmtHolder.getDefaultRowPrefetch());
                        stmtInfo.put("rowPrefetch", stmtHolder.getRowPrefetch());
                        stmtInfo.put("hitCount", stmtHolder.getHitCount());

                        stmtCache.add(stmtInfo);
                    }

                    map.put("pscache", stmtCache);
                }
                map.put("keepAliveCheckCount", connHolder.getKeepAliveCheckCount());

                list.add(map);
            }
        } finally {
            lock.unlock();
        }
        return list;
    }

    public void logTransaction(TransactionInfo info) {
        long transactionMillis = info.getEndTimeMillis() - info.getStartTimeMillis();
        if (transactionThresholdMillis > 0 && transactionMillis > transactionThresholdMillis) {
            StringBuilder buf = new StringBuilder();
            buf.append("long time transaction, take ");
            buf.append(transactionMillis);
            buf.append(" ms : ");
            for (String sql : info.getSqlList()) {
                buf.append(sql);
                buf.append(";");
            }
            LOG.error(buf.toString(), new TransactionTimeoutException());
        }
    }

    @Override
    public String getVersion() {
        return VERSION.getVersionNumber();
    }

    @Override
    public JdbcDataSourceStat getDataSourceStat() {
        return dataSourceStat;
    }

    public Object clone() throws CloneNotSupportedException {
        return cloneDruidDataSource();
    }

    public DruidDataSource cloneDruidDataSource() {
        DruidDataSource x = new DruidDataSource();

        cloneTo(x);

        return x;
    }

    public Map<String, Object> getStatDataForMBean() {
        try {
            Map<String, Object> map = new HashMap<String, Object>();

            // 0 - 4
            map.put("Name", this.getName());
            map.put("URL", this.getUrl());
            map.put("CreateCount", this.getCreateCount());
            map.put("DestroyCount", this.getDestroyCount());
            map.put("ConnectCount", this.getConnectCount());

            // 5 - 9
            map.put("CloseCount", this.getCloseCount());
            map.put("ActiveCount", this.getActiveCount());
            map.put("PoolingCount", this.getPoolingCount());
            map.put("LockQueueLength", this.getLockQueueLength());
            map.put("WaitThreadCount", this.getNotEmptyWaitThreadCount());

            // 10 - 14
            map.put("InitialSize", this.getInitialSize());
            map.put("MaxActive", this.getMaxActive());
            map.put("MinIdle", this.getMinIdle());
            map.put("PoolPreparedStatements", this.isPoolPreparedStatements());
            map.put("TestOnBorrow", this.isTestOnBorrow());

            // 15 - 19
            map.put("TestOnReturn", this.isTestOnReturn());
            map.put("MinEvictableIdleTimeMillis", this.minEvictableIdleTimeMillis);
            map.put("ConnectErrorCount", this.getConnectErrorCount());
            map.put("CreateTimespanMillis", this.getCreateTimespanMillis());
            map.put("DbType", this.dbTypeName);

            // 20 - 24
            map.put("ValidationQuery", this.getValidationQuery());
            map.put("ValidationQueryTimeout", this.getValidationQueryTimeout());
            map.put("DriverClassName", this.getDriverClassName());
            map.put("Username", this.getUsername());
            map.put("RemoveAbandonedCount", this.getRemoveAbandonedCount());

            // 25 - 29
            map.put("NotEmptyWaitCount", this.getNotEmptyWaitCount());
            map.put("NotEmptyWaitNanos", this.getNotEmptyWaitNanos());
            map.put("ErrorCount", this.getErrorCount());
            map.put("ReusePreparedStatementCount", this.getCachedPreparedStatementHitCount());
            map.put("StartTransactionCount", this.getStartTransactionCount());

            // 30 - 34
            map.put("CommitCount", this.getCommitCount());
            map.put("RollbackCount", this.getRollbackCount());
            map.put("LastError", JMXUtils.getErrorCompositeData(this.getLastError()));
            map.put("LastCreateError", JMXUtils.getErrorCompositeData(this.getLastCreateError()));
            map.put("PreparedStatementCacheDeleteCount", this.getCachedPreparedStatementDeleteCount());

            // 35 - 39
            map.put("PreparedStatementCacheAccessCount", this.getCachedPreparedStatementAccessCount());
            map.put("PreparedStatementCacheMissCount", this.getCachedPreparedStatementMissCount());
            map.put("PreparedStatementCacheHitCount", this.getCachedPreparedStatementHitCount());
            map.put("PreparedStatementCacheCurrentCount", this.getCachedPreparedStatementCount());
            map.put("Version", this.getVersion());

            // 40 -
            map.put("LastErrorTime", this.getLastErrorTime());
            map.put("LastCreateErrorTime", this.getLastCreateErrorTime());
            map.put("CreateErrorCount", this.getCreateErrorCount());
            map.put("DiscardCount", this.getDiscardCount());
            map.put("ExecuteQueryCount", this.getExecuteQueryCount());

            map.put("ExecuteUpdateCount", this.getExecuteUpdateCount());

            return map;
        } catch (JMException ex) {
            throw new IllegalStateException("getStatData error", ex);
        }
    }

    public Map<String, Object> getStatData() {
        final int activeCount;
        final int activePeak;
        final Date activePeakTime;

        final int poolingCount;
        final int poolingPeak;
        final Date poolingPeakTime;

        final long connectCount;
        final long closeCount;

        lock.lock();
        try {
            poolingCount = this.poolingCount;
            poolingPeak = this.poolingPeak;
            poolingPeakTime = this.getPoolingPeakTime();

            activeCount = this.activeCount;
            activePeak = this.activePeak;
            activePeakTime = this.getActivePeakTime();

            connectCount = this.connectCount;
            closeCount = this.closeCount;
        } finally {
            lock.unlock();
        }
        Map<String, Object> dataMap = new LinkedHashMap<String, Object>();

        dataMap.put("Identity", System.identityHashCode(this));
        dataMap.put("Name", this.getName());
        dataMap.put("DbType", this.dbTypeName);
        dataMap.put("DriverClassName", this.getDriverClassName());

        dataMap.put("URL", this.getUrl());
        dataMap.put("UserName", this.getUsername());
        dataMap.put("FilterClassNames", this.getFilterClassNames());

        dataMap.put("WaitThreadCount", this.getWaitThreadCount());
        dataMap.put("NotEmptyWaitCount", this.getNotEmptyWaitCount());
        dataMap.put("NotEmptyWaitMillis", this.getNotEmptyWaitMillis());

        dataMap.put("PoolingCount", poolingCount);
        dataMap.put("PoolingPeak", poolingPeak);
        dataMap.put("PoolingPeakTime", poolingPeakTime);

        dataMap.put("ActiveCount", activeCount);
        dataMap.put("ActivePeak", activePeak);
        dataMap.put("ActivePeakTime", activePeakTime);

        dataMap.put("InitialSize", this.getInitialSize());
        dataMap.put("MinIdle", this.getMinIdle());
        dataMap.put("MaxActive", this.getMaxActive());

        dataMap.put("QueryTimeout", this.getQueryTimeout());
        dataMap.put("TransactionQueryTimeout", this.getTransactionQueryTimeout());
        dataMap.put("LoginTimeout", this.getLoginTimeout());
        dataMap.put("ValidConnectionCheckerClassName", this.getValidConnectionCheckerClassName());
        dataMap.put("ExceptionSorterClassName", this.getExceptionSorterClassName());

        dataMap.put("TestOnBorrow", this.isTestOnBorrow());
        dataMap.put("TestOnReturn", this.isTestOnReturn());
        dataMap.put("TestWhileIdle", this.isTestWhileIdle());

        dataMap.put("DefaultAutoCommit", this.isDefaultAutoCommit());
        dataMap.put("DefaultReadOnly", this.getDefaultReadOnly());
        dataMap.put("DefaultTransactionIsolation", this.getDefaultTransactionIsolation());

        dataMap.put("LogicConnectCount", connectCount);
        dataMap.put("LogicCloseCount", closeCount);
        dataMap.put("LogicConnectErrorCount", this.getConnectErrorCount());

        dataMap.put("PhysicalConnectCount", this.getCreateCount());
        dataMap.put("PhysicalCloseCount", this.getDestroyCount());
        dataMap.put("PhysicalConnectErrorCount", this.getCreateErrorCount());

        dataMap.put("DiscardCount", this.getDiscardCount());

        dataMap.put("ExecuteCount", this.getExecuteCount());
        dataMap.put("ExecuteUpdateCount", this.getExecuteUpdateCount());
        dataMap.put("ExecuteQueryCount", this.getExecuteQueryCount());
        dataMap.put("ExecuteBatchCount", this.getExecuteBatchCount());
        dataMap.put("ErrorCount", this.getErrorCount());
        dataMap.put("CommitCount", this.getCommitCount());
        dataMap.put("RollbackCount", this.getRollbackCount());

        dataMap.put("PSCacheAccessCount", this.getCachedPreparedStatementAccessCount());
        dataMap.put("PSCacheHitCount", this.getCachedPreparedStatementHitCount());
        dataMap.put("PSCacheMissCount", this.getCachedPreparedStatementMissCount());

        dataMap.put("StartTransactionCount", this.getStartTransactionCount());
        dataMap.put("TransactionHistogram", this.getTransactionHistogramValues());

        dataMap.put("ConnectionHoldTimeHistogram", this.getDataSourceStat().getConnectionHoldHistogram().toArray());
        dataMap.put("RemoveAbandoned", this.isRemoveAbandoned());
        dataMap.put("ClobOpenCount", this.getDataSourceStat().getClobOpenCount());
        dataMap.put("BlobOpenCount", this.getDataSourceStat().getBlobOpenCount());
        dataMap.put("KeepAliveCheckCount", this.getDataSourceStat().getKeepAliveCheckCount());

        dataMap.put("KeepAlive", this.isKeepAlive());
        dataMap.put("FailFast", this.isFailFast());
        dataMap.put("MaxWait", this.getMaxWait());
        dataMap.put("MaxWaitThreadCount", this.getMaxWaitThreadCount());
        dataMap.put("PoolPreparedStatements", this.isPoolPreparedStatements());
        dataMap.put("MaxPoolPreparedStatementPerConnectionSize", this.getMaxPoolPreparedStatementPerConnectionSize());
        dataMap.put("MinEvictableIdleTimeMillis", this.minEvictableIdleTimeMillis);
        dataMap.put("MaxEvictableIdleTimeMillis", this.maxEvictableIdleTimeMillis);

        dataMap.put("LogDifferentThread", isLogDifferentThread());
        dataMap.put("RecycleErrorCount", getRecycleErrorCount());
        dataMap.put("PreparedStatementOpenCount", getPreparedStatementCount());
        dataMap.put("PreparedStatementClosedCount", getClosedPreparedStatementCount());

        dataMap.put("UseUnfairLock", isUseUnfairLock());
        dataMap.put("InitGlobalVariants", isInitGlobalVariants());
        dataMap.put("InitVariants", isInitVariants());
        return dataMap;
    }

    public JdbcSqlStat getSqlStat(int sqlId) {
        return this.getDataSourceStat().getSqlStat(sqlId);
    }

    public JdbcSqlStat getSqlStat(long sqlId) {
        return this.getDataSourceStat().getSqlStat(sqlId);
    }

    public Map<String, JdbcSqlStat> getSqlStatMap() {
        return this.getDataSourceStat().getSqlStatMap();
    }

    public Map<String, Object> getWallStatMap() {
        WallProviderStatValue wallStatValue = getWallStatValue(false);

        if (wallStatValue != null) {
            return wallStatValue.toMap();
        }

        return null;
    }

    public WallProviderStatValue getWallStatValue(boolean reset) {
        for (Filter filter : this.filters) {
            if (filter instanceof WallFilter) {
                WallFilter wallFilter = (WallFilter) filter;
                return wallFilter.getProvider().getStatValue(reset);
            }
        }

        return null;
    }

    public Lock getLock() {
        return lock;
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
        for (Filter filter : this.filters) {
            if (filter.isWrapperFor(iface)) {
                return true;
            }
        }

        if (this.statLogger != null
                && (this.statLogger.getClass() == iface || DruidDataSourceStatLogger.class == iface)) {
            return true;
        }

        return super.isWrapperFor(iface);
    }

    @SuppressWarnings("unchecked")
    public <T> T unwrap(Class<T> iface) {
        for (Filter filter : this.filters) {
            if (filter.isWrapperFor(iface)) {
                return (T) filter;
            }
        }

        if (this.statLogger != null
                && (this.statLogger.getClass() == iface || DruidDataSourceStatLogger.class == iface)) {
            return (T) statLogger;
        }

        return super.unwrap(iface);
    }

    public boolean isLogDifferentThread() {
        return logDifferentThread;
    }

    public void setLogDifferentThread(boolean logDifferentThread) {
        this.logDifferentThread = logDifferentThread;
    }

    public DruidPooledConnection tryGetConnection() throws SQLException {
        if (poolingCount == 0) {
            return null;
        }
        return getConnection();
    }

    @Override
    public int fill() throws SQLException {
        return this.fill(this.maxActive);
    }

    @Override
    public int fill(int toCount) throws SQLException {
        if (closed) {
            throw new DataSourceClosedException("dataSource already closed at " + new Date(closeTimeMillis));
        }

        if (toCount < 0) {
            throw new IllegalArgumentException("toCount can't not be less than zero");
        }

        init();

        if (toCount > this.maxActive) {
            toCount = this.maxActive;
        }

        int fillCount = 0;
        for (; ; ) {
            try {
                lock.lockInterruptibly();
            } catch (InterruptedException e) {
                connectErrorCountUpdater.incrementAndGet(this);
                throw new SQLException("interrupt", e);
            }

            boolean fillable = this.isFillable(toCount);

            lock.unlock();

            if (!fillable) {
                break;
            }

            DruidConnectionHolder holder;
            try {
                PhysicalConnectionInfo pyConnInfo = createPhysicalConnection();
                holder = new DruidConnectionHolder(this, pyConnInfo);
            } catch (SQLException e) {
                LOG.error("fill connection error, url: " + this.jdbcUrl, e);
                connectErrorCountUpdater.incrementAndGet(this);
                throw e;
            }

            try {
                lock.lockInterruptibly();
            } catch (InterruptedException e) {
                connectErrorCountUpdater.incrementAndGet(this);
                throw new SQLException("interrupt", e);
            }

            try {
                if (!this.isFillable(toCount)) {
                    JdbcUtils.close(holder.getConnection());
                    LOG.info("fill connections skip.");
                    break;
                }
                this.putLast(holder, System.currentTimeMillis());
                fillCount++;
            } finally {
                lock.unlock();
            }
        }

        if (LOG.isInfoEnabled()) {
            LOG.info("fill " + fillCount + " connections");
        }

        return fillCount;
    }

    private boolean isFillable(int toCount) {
        int currentCount = this.poolingCount + this.activeCount;
        if (currentCount >= toCount || currentCount >= this.maxActive) {
            return false;
        } else {
            return true;
        }
    }

    public boolean isFull() {
        lock.lock();
        try {
            return this.poolingCount + this.activeCount >= this.maxActive;
        } finally {
            lock.unlock();
        }
    }

    private void emptySignal() {
        if (createScheduler == null) {
            empty.signal();
            return;
        }

        if (createTaskCount >= maxCreateTaskCount) {
            return;
        }

        if (activeCount + poolingCount + createTaskCount >= maxActive) {
            return;
        }
        submitCreateTask(false);
    }

    @Override
    public ObjectName preRegister(MBeanServer server, ObjectName name) throws Exception {
        if (server != null) {
            try {
                if (server.isRegistered(name)) {
                    server.unregisterMBean(name);
                }
            } catch (Exception ex) {
                LOG.warn("DruidDataSource preRegister error", ex);
            }
        }
        return name;
    }

    @Override
    public void postRegister(Boolean registrationDone) {

    }

    @Override
    public void preDeregister() throws Exception {

    }

    @Override
    public void postDeregister() {

    }

    public boolean isClosed() {
        return this.closed;
    }

    public boolean isCheckExecuteTime() {
        return checkExecuteTime;
    }

    public void setCheckExecuteTime(boolean checkExecuteTime) {
        this.checkExecuteTime = checkExecuteTime;
    }

    public void forEach(Connection conn) {

    }
}
