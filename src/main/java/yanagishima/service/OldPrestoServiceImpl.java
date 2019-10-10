package yanagishima.service;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import io.airlift.units.DataSize;
import io.airlift.units.Duration;
import com.facebook.presto.client.*;
import me.geso.tinyorm.TinyORM;
import okhttp3.OkHttpClient;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.komamitsu.fluency.Fluency;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import yanagishima.config.YanagishimaConfig;
import yanagishima.exception.QueryErrorException;
import yanagishima.result.PrestoQueryResult;
import yanagishima.util.Constants;

import javax.inject.Inject;
import java.io.BufferedWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Verify.verify;
import static com.facebook.presto.client.OkHttpUtil.basicAuth;
import static com.facebook.presto.client.OkHttpUtil.setupTimeouts;
import static java.lang.String.format;
import static java.util.Collections.emptyMap;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;
import static yanagishima.util.DbUtil.insertQueryHistory;
import static yanagishima.util.DbUtil.storeError;
import static yanagishima.util.FluentdUtil.buildStaticFluency;
import static yanagishima.util.PathUtil.getResultFilePath;
import static yanagishima.util.QueryEngine.presto;
import static yanagishima.util.TimeoutUtil.checkTimeout;

public class OldPrestoServiceImpl implements OldPrestoService {
    private static final Logger LOGGER = LoggerFactory.getLogger(OldPrestoServiceImpl.class);

    private final YanagishimaConfig yanagishimaConfig;
    private final OkHttpClient httpClient;
    private final ExecutorService executorService = Executors.newFixedThreadPool(10);
    private final Fluency fluency;
    private final TinyORM db;

    @Inject
    public OldPrestoServiceImpl(YanagishimaConfig yanagishimaConfig, TinyORM db) {
        this.yanagishimaConfig = yanagishimaConfig;
        this.db = db;
        OkHttpClient.Builder builder = new OkHttpClient.Builder();
        setupTimeouts(builder, 5, SECONDS);
        httpClient = builder.build();
        this.fluency = buildStaticFluency(yanagishimaConfig);
    }

    @Override
    public String doQueryAsync(String datasource, String query, String userName, Optional<String> prestoUser, Optional<String> prestoPassword) {
        StatementClient client = getStatementClient(datasource, query, userName, prestoUser, prestoPassword);
        executorService.submit(new Task(datasource, query, client, userName));
        return client.currentStatusInfo().getId();
    }

    public class Task implements Runnable {
        private final String datasource;
        private final String query;
        private final StatementClient client;
        private final String userName;

        public Task(String datasource, String query, StatementClient client, String userName) {
            this.datasource = datasource;
            this.query = query;
            this.client = client;
            this.userName = userName;
        }

        @Override
        public void run() {
            try {
                int limit = yanagishimaConfig.getSelectLimit();
                getPrestoQueryResult(datasource, query, client, true, limit, userName);
            } catch (QueryErrorException e) {
                LOGGER.warn(e.getCause().getMessage());
            } catch (Throwable e) {
                LOGGER.error(e.getMessage(), e);
            } finally {
                if(client != null) {
                    client.close();
                }
            }
        }
    }

    @Override
    public PrestoQueryResult doQuery(String datasource, String query, String userName, Optional<String> prestoUser, Optional<String> prestoPassword, boolean storeFlag, int limit) throws QueryErrorException {
        try (StatementClient client = getStatementClient(datasource, query, userName, prestoUser, prestoPassword)) {
            return getPrestoQueryResult(datasource, query, client, storeFlag, limit, userName);
        }
    }

    private PrestoQueryResult getPrestoQueryResult(String datasource, String query, StatementClient client, boolean storeFlag, int limit, String userName) throws QueryErrorException {
        List<String> prestoSecretKeywords = yanagishimaConfig.getPrestoSecretKeywords(datasource);
        for(String prestoSecretKeyword : prestoSecretKeywords) {
            if(query.indexOf(prestoSecretKeyword) != -1) {
                String message = "query error occurs";
                storeError(db, datasource, presto.name(), client.currentStatusInfo().getId(), query, userName, message);
                throw new RuntimeException(message);
            }
        }

        List<String> prestoMustSpecifyConditions = yanagishimaConfig.getPrestoMustSpecifyConditions(datasource);
        for(String prestoMustSpecifyCondition : prestoMustSpecifyConditions) {
            String[] conditions = prestoMustSpecifyCondition.split(",");
            for(String condition : conditions) {
                String table = condition.split(":")[0];
                if(!query.startsWith(Constants.YANAGISHIMA_COMMENT) && query.indexOf(table) != -1) {
                    String[] partitionKeys = condition.split(":")[1].split("\\|");
                    for(String partitionKey : partitionKeys) {
                        if(query.indexOf(partitionKey) == -1) {
                            String message = format("If you query %s, you must specify %s in where clause", table, partitionKey);
                            storeError(db, datasource, presto.name(), client.currentStatusInfo().getId(), query, userName, message);
                            throw new RuntimeException(message);
                        }
                    }
                }
            }
        }

        Duration queryMaxRunTime = new Duration(yanagishimaConfig.getQueryMaxRunTimeSeconds(datasource), SECONDS);
        long start = System.currentTimeMillis();
        while (client.isRunning() && (client.currentData().getData() == null)) {
            try {
                client.advance();
            } catch (RuntimeException e) {
                QueryStatusInfo results = client.isRunning() ? client.currentStatusInfo() : client.finalStatusInfo();
                String queryId = results.getId();
                String message = format("Query failed (#%s) in %s: presto internal error message=%s", queryId, datasource, e.getMessage());
                storeError(db, datasource, presto.name(), queryId, query, userName, message);
                throw e;
            }

            if(System.currentTimeMillis() - start > queryMaxRunTime.toMillis()) {
                String queryId = client.currentStatusInfo().getId();
                String message = format("Query failed (#%s) in %s: Query exceeded maximum time limit of %s", queryId, datasource, queryMaxRunTime.toString());
                storeError(db, datasource, presto.name(), queryId, query, userName, message);
                throw new RuntimeException(message);
            }
        }

        PrestoQueryResult prestoQueryResult = new PrestoQueryResult();
        // if running or finished
        if (client.isRunning() || (client.isFinished() && client.finalStatusInfo().getError() == null)) {
            QueryStatusInfo results = client.isRunning() ? client.currentStatusInfo() : client.finalStatusInfo();
            String queryId = results.getId();
            if (results.getColumns() == null) {
                throw new QueryErrorException(new SQLException(format("Query %s has no columns\n", results.getId())));
            } else {
                prestoQueryResult.setQueryId(queryId);
                prestoQueryResult.setUpdateType(results.getUpdateType());
                List<String> columns = Lists.transform(results.getColumns(), Column::getName);
                prestoQueryResult.setColumns(columns);
                List<List<String>> rowDataList = new ArrayList<>();
                processData(client, datasource, queryId, query, prestoQueryResult, columns, rowDataList, start, limit, userName);
                prestoQueryResult.setRecords(rowDataList);
                if(storeFlag) {
                    insertQueryHistory(db, datasource, presto.name(), query, userName, queryId, prestoQueryResult.getLineNumber());
                }
                if(yanagishimaConfig.getFluentdExecutedTag().isPresent()) {
                    try {
                        long end = System.currentTimeMillis();
                        String tag = yanagishimaConfig.getFluentdExecutedTag().get();
                        Map<String, Object> event = new HashMap<>();
                        event.put("elapsed_time_millseconds", end - start);
                        event.put("user", userName);
                        event.put("query", query);
                        event.put("query_id", queryId);
                        event.put("datasource", datasource);
                        event.put("engine", presto.name());
                        fluency.emit(tag, event);
                    } catch (IOException e) {
                        LOGGER.error(e.getMessage(), e);
                    }
                }
            }
        }

        checkState(!client.isRunning());

        if (client.isClientAborted()) {
            throw new RuntimeException("Query aborted by user");
        }
        if (client.isClientError()) {
            throw new RuntimeException("Query is gone (server restarted?)");
        }
        verify(client.isFinished());

        if (client.finalStatusInfo().getError() != null) {
            QueryStatusInfo results = client.finalStatusInfo();
            if(prestoQueryResult.getQueryId() == null) {
                String queryId = results.getId();
                String message = format("Query failed (#%s) in %s: %s", queryId, datasource, results.getError().getMessage());
                storeError(db, datasource, presto.name(), queryId, query, userName, message);
            } else {
                Path successDst = getResultFilePath(datasource, prestoQueryResult.getQueryId(), false);
                try {
                    Files.delete(successDst);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                Path dst = getResultFilePath(datasource, prestoQueryResult.getQueryId(), true);
                String message = format("Query failed (#%s) in %s: %s", prestoQueryResult.getQueryId(), datasource, results.getError().getMessage());

                try (BufferedWriter bw = Files.newBufferedWriter(dst, StandardCharsets.UTF_8)) {
                    bw.write(message);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
            if(yanagishimaConfig.getFluentdFaliedTag().isPresent()) {
                try {
                    long end = System.currentTimeMillis();
                    String tag = yanagishimaConfig.getFluentdFaliedTag().get();
                    String queryId = results.getId();
                    String errorName = results.getError().getErrorName();
                    String errorType = results.getError().getErrorType();
                    Map<String, Object> event = new HashMap<>();
                    event.put("elapsed_time_millseconds", end - start);
                    event.put("user", userName);
                    event.put("query", query);
                    event.put("query_id", queryId);
                    event.put("datasource", datasource);
                    event.put("errorName", errorName);
                    event.put("errorType", errorType);
                    event.put("message", results.getError().getMessage());
                    fluency.emit(tag, event);
                } catch (IOException e) {
                    LOGGER.error(e.getMessage(), e);
                }
            }
            throw resultsException(results, datasource);
        }
        return prestoQueryResult;
    }

    private void processData(StatementClient client, String datasource, String queryId, String query, PrestoQueryResult prestoQueryResult, List<String> columns, List<List<String>> rowDataList, long start, int limit, String userName) {
        Duration queryMaxRunTime = new Duration(yanagishimaConfig.getQueryMaxRunTimeSeconds(datasource), SECONDS);
        Path dst = getResultFilePath(datasource, queryId, false);
        int lineNumber = 0;
        int maxResultFileByteSize = yanagishimaConfig.getMaxResultFileByteSize();
        int resultBytes = 0;
        try (BufferedWriter bw = Files.newBufferedWriter(dst, StandardCharsets.UTF_8);
             CSVPrinter csvPrinter = new CSVPrinter(bw, CSVFormat.EXCEL.withDelimiter('\t').withNullString("\\N").withRecordSeparator(System.getProperty("line.separator")));) {
            csvPrinter.printRecord(columns);
            lineNumber++;
            while (client.isRunning()) {
                Iterable<List<Object>> data = client.currentData().getData();
                if (data != null) {
                    for(List<Object> row : data) {
                        List<String> columnDataList = new ArrayList<>();
                        List<Object> tmpColumnDataList = row.stream().collect(Collectors.toList());
                        for (Object tmpColumnData : tmpColumnDataList) {
                            if (tmpColumnData instanceof Long) {
                                columnDataList.add(((Long) tmpColumnData).toString());
                            } else if (tmpColumnData instanceof Double) {
                                if(Double.isNaN((Double)tmpColumnData) || Double.isInfinite((Double) tmpColumnData)) {
                                    columnDataList.add(tmpColumnData.toString());
                                } else {
                                    columnDataList.add(BigDecimal.valueOf((Double) tmpColumnData).toPlainString());
                                }
                            } else {
                                if (tmpColumnData == null) {
                                    columnDataList.add(null);
                                } else {
                                    columnDataList.add(tmpColumnData.toString());
                                }
                            }
                        }
                        try {
                            csvPrinter.printRecord(columnDataList);
                            lineNumber++;
                            resultBytes += columnDataList.toString().getBytes(StandardCharsets.UTF_8).length;
                            if(resultBytes > maxResultFileByteSize) {
                                String message = format("Result file size exceeded %s bytes. queryId=%s, datasource=%s", maxResultFileByteSize, queryId, datasource);
                                storeError(db, datasource, presto.name(), client.currentStatusInfo().getId(), query, userName, message);
                                throw new RuntimeException(message);
                            }
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                        if (client.getQuery().toLowerCase().startsWith("show") || rowDataList.size() < limit) {
                            rowDataList.add(columnDataList);
                        } else {
                            prestoQueryResult.setWarningMessage(format("now fetch size is %d. This is more than %d. So, fetch operation stopped.", rowDataList.size(), limit));
                        }
                    }
                }
                client.advance();
                checkTimeout(db, queryMaxRunTime, start, datasource, presto.name(), queryId, query, userName);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        prestoQueryResult.setLineNumber(lineNumber);
        try {
            long size = Files.size(dst);
            DataSize rawDataSize = new DataSize(size, DataSize.Unit.BYTE);
            prestoQueryResult.setRawDataSize(rawDataSize.convertToMostSuccinctDataSize());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private StatementClient getStatementClient(String datasource, String query, String userName, Optional<String> prestoUser, Optional<String> prestoPassword) {
        String server = yanagishimaConfig.getPrestoCoordinatorServer(datasource);
        String catalog = yanagishimaConfig.getCatalog(datasource);
        String schema = yanagishimaConfig.getSchema(datasource);
        String source = yanagishimaConfig.getSource(datasource);

        if (prestoUser.isPresent() && prestoPassword.isPresent()) {
            ClientSession clientSession = buildClientSession(server, prestoUser.get(), source, catalog, schema);
            checkArgument(clientSession.getServer().getScheme().equalsIgnoreCase("https"), "Authentication using username/password requires HTTPS to be enabled");
            OkHttpClient.Builder clientBuilder = httpClient.newBuilder();
            clientBuilder.addInterceptor(basicAuth(prestoUser.get(), prestoPassword.get()));
            return StatementClientFactory.newStatementClient(clientBuilder.build(), clientSession, query);
        }

        String user = firstNonNull(userName, yanagishimaConfig.getUser(datasource));
        ClientSession clientSession = buildClientSession(server, user, source, catalog, schema);
        return StatementClientFactory.newStatementClient(httpClient, clientSession, query);
    }

    private static ClientSession buildClientSession(String server, String user, String source, String catalog, String schema) {
        return new ClientSession(URI.create(server), user, source, Optional.empty(), ImmutableSet.of(), null, catalog,
                                 schema, null, TimeZone.getDefault().getID(), Locale.getDefault(),
                                 ImmutableMap.of(), ImmutableMap.of(), emptyMap(),null, new Duration(2, MINUTES));
    }

    private static QueryErrorException resultsException(QueryStatusInfo results, String datasource) {
        QueryError error = results.getError();
        String message = format("Query failed (#%s) in %s: %s", results.getId(), datasource, error.getMessage());
        Throwable cause = (error.getFailureInfo() == null) ? null : error.getFailureInfo().toException();
        return new QueryErrorException(results.getId(), new SQLException(message, error.getSqlState(), error.getErrorCode(), cause));
    }
}